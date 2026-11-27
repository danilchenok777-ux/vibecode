"""Wispbyte entry point for the GODHEAD FastAPI backend.

Uses a persistent SQLite file on the server disk so the free Python server
does not require a separate PostgreSQL service. A strong JWT secret is
generated once and stored locally if the panel does not provide one.
"""
from __future__ import annotations

import os
import secrets
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BACKEND = ROOT / "backend"

if not (BACKEND / "app" / "main.py").is_file():
    raise RuntimeError("GODHEAD backend files are missing; deploy the repository root.")

# Keep runtime data in the server's persistent container storage.
os.environ.setdefault("GODHEAD_ENV", "development")
os.environ.setdefault("GODHEAD_DATABASE_URL", "sqlite:///./godhead.db")

# Never use the repository's public development JWT secret on the hosted server.
if not os.environ.get("GODHEAD_JWT_SECRET"):
    secret_file = ROOT / ".godhead_jwt_secret"
    try:
        jwt_secret = secret_file.read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        jwt_secret = secrets.token_urlsafe(64)
        secret_file.write_text(jwt_secret, encoding="utf-8")
        try:
            secret_file.chmod(0o600)
        except OSError:
            pass
    if len(jwt_secret) < 32:
        raise RuntimeError("Persisted GODHEAD JWT secret is invalid; remove .godhead_jwt_secret to regenerate it.")
    os.environ["GODHEAD_JWT_SECRET"] = jwt_secret

# The backend package and Alembic config are rooted in backend/.
os.chdir(BACKEND)
sys.path.insert(0, str(BACKEND))

from alembic import command
from alembic.config import Config
import uvicorn


def main() -> None:
    config = Config(str(BACKEND / "alembic.ini"))
    command.upgrade(config, "head")

    from app.main import app

    # Wispbyte supplies SERVER_PORT. PORT is a fallback for local testing.
    port = int(os.environ.get("SERVER_PORT") or os.environ.get("PORT") or "8000")
    uvicorn.run(app, host="0.0.0.0", port=port)


if __name__ == "__main__":
    main()
