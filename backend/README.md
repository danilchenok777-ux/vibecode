# GODHEAD API

The GODHEAD API currently implements first-party account identity and messaging: registration, login, authenticated profiles, private and group conversations, paged history, unread counts, delivery/read receipts, private attachments, and live message events over WebSocket.

Attachments use local disk by default at `.godhead_storage/attachments`. This is appropriate for a single Wispbyte server when its files directory is retained across normal restarts and deployments; the directory is kept outside Git and should be backed up. For Docker Compose or a multi-instance deployment, set `GODHEAD_ATTACHMENT_STORAGE_BACKEND=s3` and configure the S3 settings. The example environment and Compose stack use S3/MinIO.

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
- `GET /v1/conversations` — list the current user's conversations with member-scoped unread counts.
- `POST /v1/conversations/{id}/messages` — send a text message.
- `GET /v1/conversations/{id}/messages` — page through message history using `before_id`.
- `PUT /v1/conversations/{id}/read` — advance the current member's read cursor to a message in that conversation.
- `GET /v1/conversations/{id}/read-states` — list each member's read cursor. A null cursor means that member has not marked any message as read.
- `POST /v1/conversations/{id}/messages/{message_id}/delivery` — the recipient records that a message reached an authenticated client. The sender cannot acknowledge their own message; repeated acknowledgements are idempotent.
- `GET /v1/conversations/{id}/messages/{message_id}/delivery-states` — list each recipient's delivery timestamp; a null timestamp means no recipient acknowledgement has arrived yet.
- `POST /v1/conversations/{id}/attachments` — upload a file and create a message containing its metadata. Maximum file size is 25 MiB; MIME types are allow-listed.
- `GET /v1/conversations/{id}/attachments/{attachment_id}/download` — obtain a private local content path or a short-lived signed S3 URL after membership validation.
- `GET /v1/conversations/{id}/attachments/{attachment_id}/content` — stream a local attachment after membership validation; S3 deployments redirect to a short-lived signed URL.


- `GET /health/live` and `GET /health/ready` — process and database checks.

## WebSocket

Connect to `/v1/ws` with an `Authorization: Bearer <access-token>` header. Connections revalidate the server-side session while active and are closed after a token is revoked or expires. A text frame containing `ping` returns a `pong` event. New chat messages are delivered as `message.created` JSON events to all connected members of that conversation. A recipient's first delivery acknowledgement emits a `message.delivered` event to the sender. Advancing a member's read cursor emits a `read.state.updated` event to the same conversation. Message creation, delivery acknowledgements and read-cursor updates remain authenticated HTTP operations.

## Remaining production gaps

WebSocket fan-out and authentication rate limits are process-local; horizontal deployments need a shared broker and shared rate-limit store. Before public production, add email verification, password reset, refresh-token rotation, malware scanning, storage quotas/lifecycle policies, moderation/abuse controls, audit events, observability, backups, deployment hardening, load tests and an independent security review. Voice/video, push notifications, GRAM and Premium services are not implemented by this API yet. Do not expose it using development secrets or a single-process SQLite setup.
