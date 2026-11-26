# GODHEAD

GODHEAD is an Android communication platform developed from the ExteraGram client codebase. This is the production project, not a demo or disposable prototype.

## Project structure

- Android client: ExteraGram-derived Android application, rebranded with the GODHEAD application ID and name.
- GODHEAD API: registration, authentication, private/group conversations, message history and realtime events.
- PostgreSQL schema and migrations: backend/alembic.
- Upstream build documentation: [UPSTREAM_README.md](UPSTREAM_README.md).

## Product requirements

The complete product target includes independently operated GODHEAD accounts, private and group chats, attachments, media, search, voice/video calls, push notifications, GRAM balances and auditable transactions, Premium entitlements, stickers, gifts, collections, crafting, maps, privacy controls, localization and accessibility. Features must not be described as operational until their real implementation and service integration are complete.

## Upstream, attribution and licensing

- Android client upstream: https://github.com/exteraSquad/exteraGram
- License: GNU GPL v3 or later, subject to upstream additional terms.
- Preserve upstream copyright notices, author attributions, and all applicable license terms.
- Settings footer retains a clickable “Based on ExteraGram” attribution and upstream repository link.
- GODHEAD is a distinct, independently developed product and is not endorsed by ExteraGram authors.

## Build and configuration

Follow [UPSTREAM_README.md](UPSTREAM_README.md) for Android build prerequisites. Supply your own Telegram API ID/hash while Telegram MTProto remains in use, and your own Firebase configuration if Firebase-based upstream features remain enabled. Never commit credentials, Firebase configuration, signing keys, keystores or passwords.

## Service boundary

Telegram/MTProto networking remains upstream functionality until a GODHEAD replacement is implemented, security-reviewed, tested and deliberately enabled. GODHEAD identity and messaging APIs are separate from Telegram account sessions.
