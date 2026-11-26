from contextlib import asynccontextmanager
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from .config import get_settings
from .database import Base, _engine
from . import models  # noqa: F401
from .routes import router
from .chat_routes import router as chat_router
from .chat_realtime import connection_manager
from .database import SessionLocal
from .models import User
from .security import decode_access_token
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
    allow_methods=["GET", "POST"],
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
        subject = decode_access_token(authorization[7:].strip())
        user_id = int(subject)
    except (jwt.PyJWTError, ValueError, TypeError):
        await websocket.close(code=4401, reason="Invalid or expired access token")
        return

    with SessionLocal() as db:
        if db.get(User, user_id) is None:
            await websocket.close(code=4401, reason="Account not found")
            return

    await connection_manager.connect(user_id, websocket)
    try:
        while True:
            message = await websocket.receive_text()
            if message == "ping":
                await websocket.send_json({"type": "pong"})
            else:
                await websocket.send_json({
                    "type": "error",
                    "code": "http_required",
                    "detail": "Send chat messages through the authenticated HTTP endpoint.",
                })
    except WebSocketDisconnect:
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
