from datetime import datetime
from pydantic import BaseModel, Field


class CreateGroupRequest(BaseModel):
    title: str = Field(min_length=1, max_length=120)
    member_ids: list[int] = Field(default_factory=list, max_length=199)


class CreateMessageRequest(BaseModel):
    content: str = Field(min_length=1, max_length=4096)


class MessageResponse(BaseModel):
    id: int
    conversation_id: int
    sender_id: int
    content: str
    created_at: datetime


class ConversationResponse(BaseModel):
    id: int
    kind: str
    title: str | None
    created_by: int
    member_ids: list[int]
    created_at: datetime
    updated_at: datetime
    last_message: MessageResponse | None = None


class MessagePageResponse(BaseModel):
    items: list[MessageResponse]
    next_before_id: int | None = None
