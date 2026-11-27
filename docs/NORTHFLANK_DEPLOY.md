# Deploy the GODHEAD API to Northflank

This repository contains the Android client and the API service in `backend/`. The root `Dockerfile` is the Android build image; **do not use it for the API**.

## Current backend scope

The service implements the HTTP/WebSocket contract used by `GodheadApiClient` and `GodheadRealtimeClient`: registration, login/logout, remote sessions, user lookup, direct/group conversations, message history and sending, read cursors, delivery receipts, attachment upload/download links, and authenticated WebSocket events.

This is an initial self-hosted backend implementation, not a production security certification. Review it, test it, configure backups and persistent file storage, and complete security review before real users or sensitive data are onboarded. The current attachment implementation writes to `UPLOAD_DIR`; use a persistent mounted volume or replace it with object storage before production. Push notifications, voice/video calling, end-to-end encryption, moderation, abuse controls, and horizontal WebSocket fan-out are not implemented by this service.

## Northflank setup

1. Create a Northflank account and a **Developer Sandbox** team.
2. Connect GitHub and select `danilchenok777-ux/vibecode`.
3. Create a project in the region nearest your users. The project region cannot be changed later.
4. Create a PostgreSQL addon. Enable TLS if the platform's connection details require it.
5. Create a deployment service from the repository:
   - branch: `feature/northflank-godhead-api` (or the merged branch)
   - build type: Dockerfile
   - Dockerfile path: `backend/Dockerfile`
   - build context: repository root
   - exposed HTTP port: `8080`
6. Add runtime variables:
   - `DATABASE_URL`: internal PostgreSQL connection string from the Northflank addon
   - `APP_SECRET`: random secret with at least 32 characters
   - `PUBLIC_BASE_URL`: public HTTPS URL assigned to the service, without a trailing slash
   - `UPLOAD_DIR`: `/data/uploads`
   - `PORT`: `8080`
   - `PGSSLMODE`: set to `require` only if required by the selected database connection string
7. Attach persistent storage mounted at `/data` before accepting attachments. If the free plan does not include persistent volumes, do not use local attachment storage for real users; configure external object storage first.
8. Deploy and wait for a healthy build. Verify `https://YOUR-SERVICE/health` returns `{"status":"ok","service":"godhead-api"}`.

## Android client

Set the GODHEAD API base URL in the app's existing `api_base_url` configuration to the service's HTTPS URL. The WebSocket endpoint is derived automatically as `/v1/ws`.

## Free-tier and production warning

Northflank currently describes Sandbox as a free developer/testing tier with up to two services, two jobs, one database, and a monthly resource allowance. Northflank explicitly says this tier is not intended for production. Check current quotas, required payment verification, storage charges, region availability, and resource consumption in your account before deployment. Do not assume this is a guaranteed free production host.
