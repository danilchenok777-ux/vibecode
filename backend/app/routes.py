from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from .auth import get_current_user
from .database import get_db
from .models import User
from .schemas import LoginRequest, PublicUserResponse, RegisterRequest, TokenResponse, UserResponse
from .security import DUMMY_HASH, create_access_token, hash_password, verify_password

router = APIRouter(prefix="/v1")


@router.post("/auth/register", response_model=TokenResponse, status_code=status.HTTP_201_CREATED)
def register(payload: RegisterRequest, db: Session = Depends(get_db)):
    user = User(
        email=str(payload.email),
        username=payload.username,
        display_name=payload.display_name.strip(),
        password_hash=hash_password(payload.password),
    )
    if not user.display_name:
        raise HTTPException(status_code=422, detail="Display name cannot be blank")
    db.add(user)
    try:
        db.commit()
        db.refresh(user)
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=409, detail="Email or username is already registered")
    token, lifetime = create_access_token(str(user.id))
    return TokenResponse(access_token=token, expires_in=lifetime, user=user)


@router.post("/auth/login", response_model=TokenResponse)
def login(payload: LoginRequest, db: Session = Depends(get_db)):
    user = db.scalar(select(User).where(User.email == str(payload.email)))
    encoded = user.password_hash if user else DUMMY_HASH
    valid = verify_password(payload.password, encoded)
    if user is None or not valid:
        raise HTTPException(status_code=401, detail="Invalid email or password")
    token, lifetime = create_access_token(str(user.id))
    return TokenResponse(access_token=token, expires_in=lifetime, user=user)


@router.get("/users/me", response_model=UserResponse)
def me(user: User = Depends(get_current_user)):
    return user


@router.get("/users/search", response_model=list[PublicUserResponse])
def search_users(
    query: str = Query(min_length=2, max_length=64),
    limit: int = Query(default=20, ge=1, le=50),
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    # Search public identifiers only; email addresses and other private fields
    # are never returned by this endpoint.
    needle = query.strip().lstrip("@").lower()
    if len(needle) < 2:
        raise HTTPException(status_code=422, detail="Search query must contain at least two characters")

    escaped = needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    pattern = f"%{escaped}%"
    matches = db.scalars(
        select(User)
        .where(
            User.id != current_user.id,
            or_(
                User.username.ilike(pattern, escape="\\"),
                User.display_name.ilike(pattern, escape="\\"),
            ),
        )
        .order_by(User.username.asc())
        .limit(limit)
    ).all()
    return [
        PublicUserResponse(id=user.id, username=user.username, display_name=user.display_name)
        for user in matches
    ]
