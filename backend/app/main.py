from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from .config import get_settings
from .database import Base, _engine
from . import models  # noqa: F401
from .routes import router
from .chat_routes import router as chat_router


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


@app.get("/health/live", tags=["operations"])
def live():
    return {"status": "alive", "service": "godhead-api"}


@app.get("/health/ready", tags=["operations"])
def ready():
    from sqlalchemy import text
    with _engine.connect() as connection:
        connection.execute(text("SELECT 1"))
    return {"status": "ready", "database": "connected"}
