"""Wispbyte entry point when the deployment directory is feature/northflank-godhead-api."""
from __future__ import annotations

import os
import secrets
import sys
from pathlib import Path

DEPLOY_DIR = Path(__file__).resolve().parent
REPO_ROOT = DEPLOY_DIR.parents[1]
BACKEND = REPO_ROOT / "backend"

if not (BACKEND / "app" / "main.py").is_file():
    raise RuntimeError("GODHEAD backend was not found next to the deployment directory.")

os.environ.setdefault("GODHEAD_ENV", "development")
os.environ.setdefault("GODHEAD_DATABASE_URL", "sqlite:///./godhead.db")

# Persist a per-server random JWT signing key on the Wispbyte disk.
if not os.environ.get("GODHEAD_JWT_SECRET"):
    secret_file = REPO_ROOT / ".godhead_jwt_secret"
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
        raise RuntimeError("Persisted GODHEAD JWT secret is invalid.")
    os.environ["GODHEAD_JWT_SECRET"] = jwt_secret

os.chdir(BACKEND)
sys.path.insert(0, str(BACKEND))

from alembic import command
from alembic.config import Config
import uvicorn


def main() -> None:
    command.upgrade(Config(str(BACKEND / "alembic.ini")), "head")
    from app.main import app

    port = int(os.environ.get("SERVER_PORT") or os.environ.get("PORT") or "8000")
    uvicorn.run(app, host="0.0.0.0", port=port)


if __name__ == "__main__":
    main()
