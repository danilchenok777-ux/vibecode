# GODHEAD API

The GODHEAD API implements first-party account identity and the initial messaging service: registration, login, authenticated profiles, private conversations, group conversations, message history, per-member read cursors, per-recipient delivery receipts, private S3-compatible file attachments, and live message/read/delivery events over WebSocket.

## Local development

```bash
cd backend
python -m venv .venv
# Activate the virtual environment.
pip install -e ".[dev]"
cp .env.example .env
# Replace GODHEAD_JWT_SECRET with a unique random value.
# Start the local object store for attachment testing.
docker compose up -d minio minio-init
uvicorn app.main:app --reload
```

Development mode creates its local SQLite schema automatically. API docs are at `/docs`; run tests with `pytest -q`.

## PostgreSQL deployment with Docker Compose

```bash
cd backend
cp .env.example .env
# Set GODHEAD_JWT_SECRET, GODHEAD_S3_ACCESS_KEY, GODHEAD_S3_SECRET_KEY and a long URL-safe POSTGRES_PASSWORD.
docker compose up --build -d
docker compose logs -f api
```

The API container waits for PostgreSQL, applies versioned Alembic migrations, waits for the MinIO bucket initializer, then starts Uvicorn. PostgreSQL and MinIO data persist in the `godhead_postgres_data` and `godhead_minio_data` volumes. Back up both before upgrades. Configure `GODHEAD_S3_PUBLIC_ENDPOINT_URL` to an address reachable by clients (Android emulator: `http://10.0.2.2:9000`; production: your HTTPS media endpoint).

## HTTP API

- `POST /v1/auth/register` — register an account and return a short-lived access token.
- `POST /v1/auth/login` — authenticate by email/password and create an independently revocable server-side session.
- `POST /v1/auth/logout` — revoke the current access token on the server; copied tokens stop working too.
- `GET /v1/auth/sessions` — list active sessions for the signed-in account and identify the current session without exposing token identifiers.
- `DELETE /v1/auth/sessions/{session_id}` — remotely revoke one of the account's other active sessions.
- `GET /v1/users/me` — retrieve the authenticated account.
- `GET /v1/users/search?query=...` — search public usernames and display names; results never expose email addresses or password data.
- `POST /v1/conversations/direct/{user_id}` — open or retrieve a unique private conversation.
- `POST /v1/conversations/groups` — create a group conversation.
- `GET /v1/conversations` — list the current user's conversations.
- `POST /v1/conversations/{id}/messages` — send a text message.
- `GET /v1/conversations/{id}/messages` — page through message history using `before_id`.
- `PUT /v1/conversations/{id}/read` — advance the current member's read cursor to a message in that conversation.
- `GET /v1/conversations/{id}/read-states` — list each member's read cursor. A null cursor means that member has not marked any message as read.
- `POST /v1/conversations/{id}/messages/{message_id}/delivery` — acknowledge delivery of a received message.
- `GET /v1/conversations/{id}/messages/{message_id}/delivery-states` — list per-recipient delivery acknowledgements.
- `POST /v1/conversations/{id}/attachments` — upload a file and create a message containing its metadata. Maximum file size is 25 MiB; MIME types are allow-listed.
- `GET /v1/conversations/{id}/attachments/{attachment_id}/download` — obtain a short-lived signed download URL after conversation-membership validation.


- `GET /health/live` and `GET /health/ready` — process and database checks.

## WebSocket

Connect to `/v1/ws` with an `Authorization: Bearer <access-token>` header. Connections revalidate the server-side session while active and are closed after a token is revoked or expires. A text frame containing `ping` returns a `pong` event. New chat messages are delivered as `message.created` JSON events to all connected members of that conversation. Advancing a member's read cursor emits a `read.state.updated` event to the same conversation. A recipient delivery acknowledgement emits `message.delivered`, including its conversation ID. Message creation, read-cursor updates and delivery acknowledgements remain authenticated HTTP operations.

## Current production gaps

WebSocket fan-out is process-local; horizontal deployments need a shared broker such as Redis. Before public production, add rate limiting, email verification, password reset, refresh-token rotation, per-message delivery acknowledgements, malware scanning and stricter content inspection, storage quotas/lifecycle policies, moderation/abuse controls, audit events, observability, backups, deployment hardening, load tests and an independent security review. Voice/video, push notifications, GRAM and Premium services are not implemented by this API yet. Do not expose it using development secrets or a single-process SQLite setup.


## Northflank deployment

See [the Northflank deployment guide](../docs/NORTHFLANK_DEPLOY.md). The API image is built from `backend/Dockerfile` with `backend/` as the Docker build context. The root Dockerfile is reserved for Android builds.
