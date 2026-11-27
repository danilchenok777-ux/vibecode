from contextlib import asynccontextmanager
import asyncio
from datetime import datetime, timezone

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import select
from .config import get_settings
from .database import Base, _engine
from . import models  # noqa: F401
from .routes import router
from .chat_routes import router as chat_router
from .chat_realtime import connection_manager
from .database import SessionLocal
from .models import AuthSession, User
from .security import decode_access_token_claims
import jwt


@asynccontextmanager
async def lifespan(_: FastAPI):
    # Creates the initial schema for local development. Production deployments
    # must use versioned migrations before serving traffic.
    if get_settings().env == "development":
        Base.metadata.create_all(bind=_engine)
    yield


app = FastAPI(
    title="GODHEAD API",
    version="0.2.0",
    description="GODHEAD identity and messaging API",
    lifespan=lifespan,
)
settings = get_settings()
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.allowed_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
)
app.include_router(router)
app.include_router(chat_router)


@app.websocket("/v1/ws")
async def websocket_endpoint(websocket: WebSocket):
    authorization = websocket.headers.get("authorization", "")
    if not authorization.lower().startswith("bearer "):
        await websocket.close(code=4401, reason="Bearer token required")
        return

    try:
        claims = decode_access_token_claims(authorization[7:].strip())
        user_id = int(claims["sub"])
        token_id = claims["jti"]
    except (jwt.PyJWTError, ValueError, TypeError, KeyError):
        await websocket.close(code=4401, reason="Invalid or expired access token")
        return

    with SessionLocal() as db:
        active_session = db.scalar(
            select(AuthSession).where(
                AuthSession.jti == token_id,
                AuthSession.user_id == user_id,
                AuthSession.revoked_at.is_(None),
                AuthSession.expires_at > datetime.now(timezone.utc),
            )
        )
        if active_session is None:
            await websocket.close(code=4401, reason="Session expired or revoked")
            return
        if db.get(User, user_id) is None:
            await websocket.close(code=4401, reason="Account not found")
            return

    await connection_manager.connect(user_id, websocket)
    try:
        while True:
            # Re-check the server-side session periodically as well as on every
            # client frame, so revocation takes effect on existing sockets too.
            try:
                message = await asyncio.wait_for(websocket.receive_text(), timeout=30)
            except asyncio.TimeoutError:
                message = None

            with SessionLocal() as db:
                still_active = db.scalar(
                    select(AuthSession.id).where(
                        AuthSession.jti == token_id,
                        AuthSession.user_id == user_id,
                        AuthSession.revoked_at.is_(None),
                        AuthSession.expires_at > datetime.now(timezone.utc),
                    )
                ) is not None

            if not still_active:
                await websocket.close(code=4401, reason="Session expired or revoked")
                break

            if message == "ping":
                await websocket.send_json({"type": "pong"})
            elif message is not None:
                await websocket.send_json({
                    "type": "error",
                    "code": "http_required",
                    "detail": "Send chat messages through the authenticated HTTP endpoint.",
                })
    except WebSocketDisconnect:
        pass
    finally:
        await connection_manager.disconnect(user_id, websocket)


@app.get("/health/live", tags=["operations"])
def live():
    return {"status": "alive", "service": "godhead-api"}


@app.get("/health/ready", tags=["operations"])
def ready():
    from sqlalchemy import text
    with _engine.connect() as connection:
        connection.execute(text("SELECT 1"))
    return {"status": "ready", "database": "connected"}
