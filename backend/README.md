# GODHEAD API — identity foundation

This is the first real backend service in the GODHEAD repository. It implements account registration, password hashing, login, signed short-lived access tokens, and the authenticated current-user endpoint.

## Run locally

```bash
cd backend
python -m venv .venv
# Activate the environment, then:
pip install -e ".[dev]"
cp .env.example .env
# Set GODHEAD_JWT_SECRET to a unique random value in .env
uvicorn app.main:app --reload
```

Open `/docs` for the OpenAPI contract. Run tests with `pytest`.

## API

- `POST /v1/auth/register` — create an account and return an access token
- `POST /v1/auth/login` — authenticate with email and password
- `GET /v1/users/me` — return the authenticated account
- `GET /health/live` and `GET /health/ready` — process and database checks

## Current limits — not production complete

This is a working starting service, not the complete GODHEAD backend. It currently uses a synchronous SQLAlchemy session and development-time schema creation. Before production: add Alembic migrations, PostgreSQL deployment configuration, distributed rate limiting, email verification, password reset, refresh-token rotation and revocation, session/device management, audit events, abuse monitoring, backup/restore, and security review. Do not expose it to the public internet with the development JWT secret or SQLite configuration.
