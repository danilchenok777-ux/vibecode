"""Launch the GODHEAD FastAPI service from the repository root."""
from __future__ import annotations

import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BACKEND = ROOT / "backend"

if not (BACKEND / "app" / "main.py").is_file():
    raise RuntimeError(f"GODHEAD backend entrypoint not found under {BACKEND}")

# The backend's Alembic config and imports are relative to backend/.
os.chdir(BACKEND)
sys.path.insert(0, str(BACKEND))

from alembic import command
from alembic.config import Config
import uvicorn


def main() -> None:
    # Apply database migrations before accepting requests.
    alembic_config = Config(str(BACKEND / "alembic.ini"))
    command.upgrade(alembic_config, "head")

    from app.main import app

    port = int(os.environ.get("SERVER_PORT") or os.environ.get("PORT") or "8000")
    uvicorn.run(app, host="0.0.0.0", port=port)


if __name__ == "__main__":
    main()
