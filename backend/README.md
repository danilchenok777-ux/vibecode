# GODHEAD API

The GODHEAD API currently implements first-party account identity and the initial messaging service: registration, login, authenticated profiles, private conversations, group conversations, message history, and live message events over WebSocket.

## Local development

```bash
cd backend
python -m venv .venv
# Activate the virtual environment.
pip install -e ".[dev]"
cp .env.example .env
# Replace GODHEAD_JWT_SECRET with a unique random value.
uvicorn app.main:app --reload
```

Development mode creates its local SQLite schema automatically. API docs are at `/docs`; run tests with `pytest -q`.

## PostgreSQL deployment with Docker Compose

```bash
cd backend
cp .env.example .env
# Set GODHEAD_JWT_SECRET and a long URL-safe POSTGRES_PASSWORD.
docker compose up --build -d
docker compose logs -f api
```

The API container waits for PostgreSQL, applies versioned Alembic migrations, then starts Uvicorn. PostgreSQL data persists in the `godhead_postgres_data` volume. Back up that volume/database before upgrades.

## HTTP API

- `POST /v1/auth/register` — register an account and return a short-lived access token.
- `POST /v1/auth/login` — authenticate by email/password and create an independently revocable server-side session.
- `POST /v1/auth/logout` — revoke the current access token on the server; copied tokens stop working too.
- `GET /v1/users/me` — retrieve the authenticated account.
- `POST /v1/conversations/direct/{user_id}` — open or retrieve a unique private conversation.
- `POST /v1/conversations/groups` — create a group conversation.
- `GET /v1/conversations` — list the current user's conversations.
- `POST /v1/conversations/{id}/messages` — send a text message.
- `GET /v1/conversations/{id}/messages` — page through message history using `before_id`.
- `GET /health/live` and `GET /health/ready` — process and database checks.

## WebSocket

Connect to `/v1/ws` with an `Authorization: Bearer <access-token>` header. Connections revalidate the server-side session while active and are closed after a token is revoked or expires. A text frame containing `ping` returns a `pong` event. New chat messages are delivered as `message.created` JSON events to all connected members of that conversation. Message creation remains on the authenticated HTTP endpoint.

## Current production gaps

WebSocket fan-out is process-local; horizontal deployments need a shared broker such as Redis. Before public production, add rate limiting, email verification, password reset, refresh-token rotation, device/session inventory and remote sign-out, delivery/read receipts, moderation/abuse controls, attachments/object storage, audit events, observability, backups, deployment hardening, load tests and an independent security review. Voice/video, push notifications, GRAM and Premium services are not implemented by this API yet. Do not expose it using development secrets or a single-process SQLite setup.
