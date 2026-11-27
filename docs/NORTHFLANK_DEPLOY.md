# Deploy the GODHEAD API to Northflank

The repository already contains the GODHEAD API in `backend/`. It is a Python 3.12 / FastAPI service with SQLAlchemy, Alembic migrations, PostgreSQL support, S3-compatible attachment storage and authenticated WebSockets. The root `Dockerfile` builds the Android app; use **`backend/Dockerfile`** for the API.

## What is included

- Account registration, login, logout and remote session revocation
- User lookup and profiles
- Direct and group conversations
- Message history and sending
- Read cursors and real-time read events
- Per-recipient delivery acknowledgements and real-time delivery events
- Private file uploads with signed download URLs
- Authenticated WebSocket at `/v1/ws`

Not implemented by this backend: end-to-end encryption, push notifications, voice/video calls, GRAM/Premium services, abuse/moderation tooling, and multi-instance WebSocket fan-out. This is an initial backend, not a production security certification.

## Northflank resource layout

The free Sandbox currently allows up to **2 services and 1 database**, with a monthly resource allowance. It is intended for testing/development, not production. Northflank requires a payment method for account verification, even on the free Sandbox. Check the live quotas and billing page before creating resources; do not treat this as guaranteed free production hosting.

Recommended allocation:
1. One Northflank PostgreSQL addon.
2. One Northflank API service built from this repository.
3. Use an external S3-compatible object-storage provider for attachments. The API needs private S3 access and a public endpoint for signed download URLs. Do not store uploads on the container's ephemeral filesystem.

## Create the project and database

1. Open [Northflank](https://northflank.com/) and create an account.
2. Select the **Developer Sandbox** plan.
3. Create a project. Choose the nearest available region to Kazakhstan from the regions actually offered in the dashboard. The project region cannot be changed later.
4. Add a PostgreSQL addon to that project. Keep the database private; the API should connect over Northflank's private network.
5. Copy the internal PostgreSQL connection details. The SQLAlchemy URL must use the `postgresql+psycopg://` driver. URL-encode any special characters in the password.

## Create the API service from GitHub

1. Connect the GitHub account that can access `danilchenok777-ux/vibecode`.
2. Create a deployment service from the repository.
3. Select branch `feature/northflank-godhead-api` while testing this pull request; after it is merged, use the destination branch.
4. Configure the Docker build:
   - Build context: `backend/`
   - Dockerfile path relative to repository root: `/backend/Dockerfile`
   - Build context relative to repository root: `/backend`
5. Expose container port **8000** over HTTP. Northflank supplies HTTPS at the public service endpoint.
6. Configure the health check path as `/health/ready` (HTTP 200).

The Docker image installs the Python dependencies. Its startup command runs `alembic upgrade head` before starting Uvicorn on `0.0.0.0:8000`.

## Runtime variables

Add these as Northflank secrets/environment variables. Never commit actual values to GitHub.

| Variable | Value |
|---|---|
| `GODHEAD_ENV` | `production` |
| `GODHEAD_DATABASE_URL` | Internal PostgreSQL URL, using `postgresql+psycopg://` |
| `GODHEAD_JWT_SECRET` | Unique cryptographically random secret, at least 32 characters |
| `GODHEAD_ACCESS_TOKEN_MINUTES` | `20` |
| `GODHEAD_CORS_ORIGINS` | Leave default for native Android-only clients, or set exact trusted web origins |
| `GODHEAD_S3_ENDPOINT_URL` | Private S3 API endpoint from your storage provider |
| `GODHEAD_S3_PUBLIC_ENDPOINT_URL` | Public HTTPS S3 endpoint used by clients to download signed URLs |
| `GODHEAD_S3_REGION` | Region required by the storage provider |
| `GODHEAD_S3_BUCKET` | Existing private bucket name |
| `GODHEAD_S3_ACCESS_KEY` | Dedicated bucket access key |
| `GODHEAD_S3_SECRET_KEY` | Dedicated bucket secret, at least 32 characters |
| `GODHEAD_S3_PRESIGN_TTL_SECONDS` | `300` |

The S3 credentials must be restricted to the GODHEAD media bucket. Keep the bucket private; clients receive short-lived signed download URLs. Do not use the development values from `.env.example`.

## Verify deployment

After the service deploys, open:

- `https://YOUR-SERVICE/health/live` — process health
- `https://YOUR-SERVICE/health/ready` — database connectivity
- `https://YOUR-SERVICE/docs` — FastAPI API documentation

A healthy `/health/ready` response should be `{"status":"ready","database":"connected"}`. Check service logs for successful Alembic migrations and Uvicorn startup.

## Connect the Android client

Set the GODHEAD API base URL in the app's existing `api_base_url` configuration to the public HTTPS service URL (no trailing slash). The Android realtime client derives `wss://YOUR-SERVICE/v1/ws` from that base URL.

## Before real users

Do not put real user accounts or private conversations on a free Sandbox. Before production, configure database backups and object-storage lifecycle/backup policies, rate limiting, email verification, password reset, abuse controls, malware scanning, monitoring, load tests, independent security review, and a shared WebSocket broker if running multiple API replicas. Northflank's free Sandbox is a development tier and does not provide a production uptime guarantee.
