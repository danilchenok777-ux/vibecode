# GODHEAD

GODHEAD is an Android communication platform developed from the ExteraGram client codebase. This is the production project, not a demo or disposable prototype.

## Project structure

- Android client: ExteraGram-derived Android application, rebranded with the GODHEAD application ID and name.
- GODHEAD API: account registration, authentication, encrypted Android session storage, private/group conversation APIs, and message history.
- Upstream build documentation: [UPSTREAM_README.md](UPSTREAM_README.md).

## Product requirements

The complete product target includes independently operated GODHEAD accounts, private and group chats, attachments, media, search, voice/video calls, push notifications, GRAM balances and auditable transactions, Premium entitlements, stickers, gifts, collections, crafting, maps, privacy controls, localization and accessibility. Features must not be described as operational until their real implementation and service integration are complete.

## Upstream, attribution, and licensing

- Android client upstream: https://github.com/exteraSquad/exteraGram
- License: GNU GPL v3 or later, subject to the upstream additional terms.
- Preserve upstream copyright notices, author attributions, and all applicable license terms.
- The Settings footer retains a clickable “Based on ExteraGram” attribution and the upstream repository link.
- GODHEAD is a distinct, independently developed product and is not endorsed by ExteraGram authors.

## Build and configuration

Follow [UPSTREAM_README.md](UPSTREAM_README.md) for Android build prerequisites. Supply your own Telegram API ID/hash while Telegram MTProto remains in use, and your own Firebase configuration if Firebase-based upstream features remain enabled. Never commit API credentials, Firebase configuration, signing keys, keystores or passwords.

### Creating a signed Android APK

The manual workflow **GODHEAD Android APK** builds the full ARM64 release variant using your own API configuration and signing key. Before running it, add these repository Actions secrets:

- `TELEGRAM_APP_ID` and `TELEGRAM_APP_HASH` — your own Telegram API credentials while the upstream MTProto transport is still present.
- `MAPS_V2_API` — your Google Maps Android API key.
- `GODHEAD_GOOGLE_SERVICES_JSON` — Firebase configuration JSON containing package `com.godhead.messenger`.
- `GODHEAD_SIGNING_KEYSTORE_BASE64` — base64-encoded JKS keystore.
- `GODHEAD_SIGNING_KEY_STORE_PASSWORD`, `GODHEAD_SIGNING_KEY_ALIAS`, and `GODHEAD_SIGNING_KEY_PASSWORD` — signing details for that keystore.

Then open **Actions → GODHEAD Android APK → Run workflow**. The workflow validates the Firebase package and signing keystore, builds the APK, and publishes the signed release APK as a 14-day artifact. Do not reuse ExteraGram's keystore or publish compile-validation builds as production releases.

The separate **GODHEAD Android compile verification** workflow uses disposable compile-only credentials and a temporary signing key. Its purpose is to catch source/build errors; those keys are not valid production credentials.

## Service boundary

Telegram/MTProto networking remains upstream functionality until a GODHEAD replacement is implemented, security-reviewed, tested and deliberately enabled. The GODHEAD identity endpoints are separate from Telegram account sessions.
