from datetime import datetime, timezone
from uuid import uuid4
import logging

from fastapi import (
    APIRouter, BackgroundTasks, Depends, File, Form, HTTPException,
    Query, UploadFile, status,
)
from fastapi.encoders import jsonable_encoder
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .auth import get_current_user
from .config import get_settings
from .database import get_db
from .models import Attachment, Conversation, ConversationMember, ConversationReadState, Message, User
from .chat_schemas import (
    ConversationResponse, CreateGroupRequest, CreateMessageRequest,
    MessagePageResponse, MessageResponse, MarkConversationReadRequest,
    ConversationReadStateResponse, AttachmentDownloadResponse, AttachmentResponse,
)
from .chat_realtime import connection_manager
from .storage import (
    create_attachment_download_url,
    delete_attachment_object,
    upload_attachment_object,
)

router = APIRouter(prefix="/v1", tags=["messaging"])
MAX_GROUP_MEMBERS = 200
MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
ALLOWED_ATTACHMENT_TYPES = {
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/gif",
    "video/mp4",
    "video/webm",
    "video/quicktime",
    "audio/mpeg",
    "audio/mp4",
    "audio/aac",
    "audio/ogg",
    "audio/wav",
    "application/pdf",
}
logger = logging.getLogger(__name__)


def _require_membership(db: Session, conversation_id: int, user_id: int) -> Conversation:
    conversation = db.scalar(
        select(Conversation)
        .join(ConversationMember, ConversationMember.conversation_id == Conversation.id)
        .where(Conversation.id == conversation_id, ConversationMember.user_id == user_id)
    )
    if conversation is None:
        # Do not reveal whether a conversation exists to a non-member.
        raise HTTPException(status_code=404, detail="Conversation not found")
    return conversation


def _message_response(message: Message | None):
    if message is None:
        return None
    attachment = message.attachment
    return MessageResponse(
        id=message.id,
        conversation_id=message.conversation_id,
        sender_id=message.sender_id,
        content=message.content,
        created_at=message.created_at,
        attachment=(
            AttachmentResponse(
                id=attachment.id,
                filename=attachment.filename,
                content_type=attachment.content_type,
                size_bytes=attachment.size_bytes,
            )
            if attachment is not None else None
        ),
    )


def _conversation_response(db: Session, conversation: Conversation) -> ConversationResponse:
    members = db.scalars(
        select(ConversationMember.user_id)
        .where(ConversationMember.conversation_id == conversation.id)
        .order_by(ConversationMember.user_id.asc())
    ).all()
    last_message = db.scalar(
        select(Message)
        .where(Message.conversation_id == conversation.id)
        .order_by(Message.id.desc())
        .limit(1)
    )
    return ConversationResponse(
        id=conversation.id,
        kind=conversation.kind,
        title=conversation.title,
        created_by=conversation.created_by,
        member_ids=list(members),
        created_at=conversation.created_at,
        updated_at=conversation.updated_at,
        last_message=_message_response(last_message),
    )


@router.post(
    "/conversations/direct/{target_user_id}",
    response_model=ConversationResponse,
    status_code=status.HTTP_200_OK,
)
def open_direct_conversation(
    target_user_id: int,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    if target_user_id == current_user.id:
        raise HTTPException(status_code=422, detail="Cannot create a direct chat with yourself")
    if db.get(User, target_user_id) is None:
        raise HTTPException(status_code=404, detail="User not found")

    low, high = sorted((current_user.id, target_user_id))
    direct_key = f"{low}:{high}"
    conversation = db.scalar(
        select(Conversation).where(Conversation.direct_key == direct_key)
    )
    if conversation is None:
        conversation = Conversation(
            kind="direct",
            direct_key=direct_key,
            created_by=current_user.id,
        )
        db.add(conversation)
        db.flush()
        db.add_all([
            ConversationMember(conversation_id=conversation.id, user_id=low, role="member"),
            ConversationMember(conversation_id=conversation.id, user_id=high, role="member"),
        ])
        try:
            db.commit()
            db.refresh(conversation)
        except IntegrityError:
            db.rollback()
            conversation = db.scalar(
                select(Conversation).where(Conversation.direct_key == direct_key)
            )
            if conversation is None:
                raise
    return _conversation_response(db, conversation)


@router.post(
    "/conversations/groups",
    response_model=ConversationResponse,
    status_code=status.HTTP_201_CREATED,
)
def create_group_conversation(
    payload: CreateGroupRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    title = payload.title.strip()
    if not title:
        raise HTTPException(status_code=422, detail="Group title cannot be blank")
    member_ids = set(payload.member_ids)
    member_ids.add(current_user.id)
    if len(member_ids) < 2:
        raise HTTPException(status_code=422, detail="A group needs at least two members")
    if len(member_ids) > MAX_GROUP_MEMBERS:
        raise HTTPException(status_code=422, detail=f"A group can have at most {MAX_GROUP_MEMBERS} members")

    existing_ids = set(db.scalars(select(User.id).where(User.id.in_(member_ids))).all())
    missing_ids = sorted(member_ids - existing_ids)
    if missing_ids:
        raise HTTPException(status_code=404, detail={"unknown_user_ids": missing_ids})

    conversation = Conversation(
        kind="group",
        title=title,
        created_by=current_user.id,
    )
    db.add(conversation)
    db.flush()
    db.add_all(
        ConversationMember(
            conversation_id=conversation.id,
            user_id=user_id,
            role="owner" if user_id == current_user.id else "member",
        )
        for user_id in sorted(member_ids)
    )
    db.commit()
    db.refresh(conversation)
    return _conversation_response(db, conversation)


@router.get("/conversations", response_model=list[ConversationResponse])
def list_conversations(
    limit: int = Query(default=50, ge=1, le=100),
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    conversations = db.scalars(
        select(Conversation)
        .join(ConversationMember, ConversationMember.conversation_id == Conversation.id)
        .where(ConversationMember.user_id == current_user.id)
        .order_by(Conversation.updated_at.desc(), Conversation.id.desc())
        .limit(limit)
    ).all()
    return [_conversation_response(db, conversation) for conversation in conversations]


@router.post(
    "/conversations/{conversation_id}/messages",
    response_model=MessageResponse,
    status_code=status.HTTP_201_CREATED,
)
def send_message(
    conversation_id: int,
    payload: CreateMessageRequest,
    background_tasks: BackgroundTasks,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    conversation = _require_membership(db, conversation_id, current_user.id)
    content = payload.content.strip()
    if not content:
        raise HTTPException(status_code=422, detail="Message cannot be blank")

    message = Message(
        conversation_id=conversation.id,
        sender_id=current_user.id,
        content=content,
    )
    conversation.updated_at = datetime.now(timezone.utc)
    db.add(message)
    db.commit()
    db.refresh(message)

    recipients = db.scalars(
        select(ConversationMember.user_id)
        .where(ConversationMember.conversation_id == conversation.id)
    ).all()
    event = {
        "type": "message.created",
        "data": jsonable_encoder(_message_response(message)),
    }
    background_tasks.add_task(connection_manager.broadcast, list(recipients), event)
    return _message_response(message)


@router.post(
    "/conversations/{conversation_id}/attachments",
    response_model=MessageResponse,
    status_code=status.HTTP_201_CREATED,
)
def upload_conversation_attachment(
    conversation_id: int,
    background_tasks: BackgroundTasks,
    file: UploadFile = File(...),
    caption: str = Form(default="", max_length=4096),
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    conversation = _require_membership(db, conversation_id, current_user.id)

    filename = (file.filename or "attachment").replace("\\", "/").split("/")[-1]
    filename = filename.replace(chr(0), "").replace("\r", "").replace("\n", "").strip()
    if not filename or filename in {".", ".."}:
        raise HTTPException(status_code=422, detail="A valid attachment filename is required")
    filename = filename[:255]

    content_type = (file.content_type or "").split(";", 1)[0].strip().lower()
    if content_type not in ALLOWED_ATTACHMENT_TYPES:
        raise HTTPException(status_code=415, detail="This attachment content type is not supported")

    size_bytes = file.size
    if size_bytes is None:
        file.file.seek(0, 2)
        size_bytes = file.file.tell()
    file.file.seek(0)
    if size_bytes <= 0:
        raise HTTPException(status_code=422, detail="Attachment cannot be empty")
    if size_bytes > MAX_ATTACHMENT_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"Attachment exceeds the {MAX_ATTACHMENT_BYTES // (1024 * 1024)} MiB limit",
        )

    object_key = f"conversations/{conversation.id}/{uuid4().hex}"
    try:
        upload_attachment_object(file.file, object_key, content_type, filename)
    except Exception as error:
        logger.exception("GODHEAD attachment storage upload failed")
        raise HTTPException(status_code=503, detail="Attachment storage is unavailable") from error

    message = Message(
        conversation_id=conversation.id,
        sender_id=current_user.id,
        content=caption.strip() or filename,
    )
    message.attachment = Attachment(
        object_key=object_key,
        filename=filename,
        content_type=content_type,
        size_bytes=size_bytes,
    )
    conversation.updated_at = datetime.now(timezone.utc)
    db.add(message)
    try:
        db.commit()
        db.refresh(message)
    except Exception:
        db.rollback()
        try:
            delete_attachment_object(object_key)
        except Exception:
            logger.exception("Failed to remove an orphaned GODHEAD attachment object")
        raise

    recipients = db.scalars(
        select(ConversationMember.user_id)
        .where(ConversationMember.conversation_id == conversation.id)
    ).all()
    event = {
        "type": "message.created",
        "data": jsonable_encoder(_message_response(message)),
    }
    background_tasks.add_task(connection_manager.broadcast, list(recipients), event)
    return _message_response(message)


@router.get(
    "/conversations/{conversation_id}/attachments/{attachment_id}/download",
    response_model=AttachmentDownloadResponse,
)
def get_conversation_attachment_download(
    conversation_id: int,
    attachment_id: int,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    _require_membership(db, conversation_id, current_user.id)
    attachment = db.scalar(
        select(Attachment)
        .join(Message, Message.id == Attachment.message_id)
        .where(
            Attachment.id == attachment_id,
            Message.conversation_id == conversation_id,
        )
    )
    if attachment is None:
        raise HTTPException(status_code=404, detail="Attachment not found")

    try:
        url = create_attachment_download_url(
            attachment.object_key,
            attachment.filename,
            attachment.content_type,
        )
    except Exception as error:
        logger.exception("GODHEAD attachment download URL generation failed")
        raise HTTPException(status_code=503, detail="Attachment storage is unavailable") from error

    settings = get_settings()
    return AttachmentDownloadResponse(
        attachment=AttachmentResponse(
            id=attachment.id,
            filename=attachment.filename,
            content_type=attachment.content_type,
            size_bytes=attachment.size_bytes,
        ),
        url=url,
        expires_in=settings.s3_presign_ttl_seconds,
    )


@router.get(
    "/conversations/{conversation_id}/messages",
    response_model=MessagePageResponse,
)
def list_messages(
    conversation_id: int,
    before_id: int | None = Query(default=None, gt=0),
    limit: int = Query(default=50, ge=1, le=100),
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    _require_membership(db, conversation_id, current_user.id)
    statement = select(Message).where(Message.conversation_id == conversation_id)
    if before_id is not None:
        statement = statement.where(Message.id < before_id)
    rows = db.scalars(statement.order_by(Message.id.desc()).limit(limit + 1)).all()
    has_more = len(rows) > limit
    page = list(reversed(rows[:limit]))
    next_before_id = page[0].id if has_more and page else None
    return MessagePageResponse(
        items=[_message_response(message) for message in page],
        next_before_id=next_before_id,
    )

@router.put(
    "/conversations/{conversation_id}/read",
    response_model=ConversationReadStateResponse,
)
def mark_conversation_read(
    conversation_id: int,
    payload: MarkConversationReadRequest,
    background_tasks: BackgroundTasks,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    _require_membership(db, conversation_id, current_user.id)
    message_exists = db.scalar(
        select(Message.id).where(
            Message.id == payload.last_read_message_id,
            Message.conversation_id == conversation_id,
        )
    )
    if message_exists is None:
        raise HTTPException(status_code=404, detail="Message not found in this conversation")

    state = db.get(ConversationReadState, (conversation_id, current_user.id))
    now = datetime.now(timezone.utc)
    cursor_advanced = False
    if state is None:
        state = ConversationReadState(
            conversation_id=conversation_id,
            user_id=current_user.id,
            last_read_message_id=payload.last_read_message_id,
            updated_at=now,
        )
        db.add(state)
        try:
            db.commit()
            cursor_advanced = True
        except IntegrityError:
            # Two devices can mark the same conversation as read at once.
            # Resolve the unique-key race by keeping the furthest read cursor.
            db.rollback()
            state = db.get(ConversationReadState, (conversation_id, current_user.id))
            if state is None:
                raise
            if (
                state.last_read_message_id is None
                or payload.last_read_message_id > state.last_read_message_id
            ):
                state.last_read_message_id = payload.last_read_message_id
                state.updated_at = now
                db.commit()
                cursor_advanced = True
    elif (
        state.last_read_message_id is None
        or payload.last_read_message_id > state.last_read_message_id
    ):
        # Read cursors are monotonic: stale devices cannot move them backwards.
        state.last_read_message_id = payload.last_read_message_id
        state.updated_at = now
        db.commit()
        cursor_advanced = True

    db.refresh(state)
    result = ConversationReadStateResponse(
        conversation_id=state.conversation_id,
        user_id=state.user_id,
        last_read_message_id=state.last_read_message_id,
        updated_at=state.updated_at,
    )
    if cursor_advanced:
        recipients = db.scalars(
            select(ConversationMember.user_id)
            .where(ConversationMember.conversation_id == conversation_id)
        ).all()
        event = {
            "type": "read.state.updated",
            "data": jsonable_encoder(result),
        }
        background_tasks.add_task(
            connection_manager.broadcast,
            list(recipients),
            event,
        )
    return result


@router.get(
    "/conversations/{conversation_id}/read-states",
    response_model=list[ConversationReadStateResponse],
)
def list_conversation_read_states(
    conversation_id: int,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    _require_membership(db, conversation_id, current_user.id)
    rows = db.execute(
        select(
            ConversationMember.user_id,
            ConversationReadState.last_read_message_id,
            ConversationReadState.updated_at,
        )
        .outerjoin(
            ConversationReadState,
            (ConversationReadState.conversation_id == ConversationMember.conversation_id)
            & (ConversationReadState.user_id == ConversationMember.user_id),
        )
        .where(ConversationMember.conversation_id == conversation_id)
        .order_by(ConversationMember.user_id.asc())
    ).all()
    return [
        ConversationReadStateResponse(
            conversation_id=conversation_id,
            user_id=row.user_id,
            last_read_message_id=row.last_read_message_id,
            updated_at=row.updated_at,
        )
        for row in rows
    ]

