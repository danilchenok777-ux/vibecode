# Build GODHEAD on Windows

This guide builds the Android client locally. It does not require a signing secret from GitHub Actions, but it does require your own API credentials and signing keystore.

## 1. Install build prerequisites

Install and configure:

- Git for Windows
- JDK 17 (set `JAVA_HOME`)
- Android SDK command-line tools / Android Studio
- Android SDK Platform 37 (preview package `android-37.0`), Build Tools 36.0.0
- Android NDK 29.0.14206865
- CMake 4.0.0 or newer
- Python 3.11 (required by the `full` flavor)

Make sure `java`, `python`, and `git` are available in PowerShell. In Android Studio's SDK Manager, install the exact NDK and CMake versions above.

## 2. Clone the repository and all submodules

```powershell
git clone --recursive https://github.com/danilchenok777-ux/vibecode.git
cd vibecode
git submodule sync --recursive
git submodule update --init --recursive
```

If you are building a feature branch or pull request, check it out before building:

```powershell
git fetch origin
git switch feature/android-chat-media-and-sessions
git pull
```

## 3. Configure your own keys

Create a file named `API_KEYS` in the repository root. Do not commit it.

```properties
APP_ID=YOUR_NUMERIC_TELEGRAM_APP_ID
APP_HASH="YOUR_TELEGRAM_APP_HASH"
MAPS_V2_API="YOUR_GOOGLE_MAPS_ANDROID_KEY"
SIGNING_KEY_STORE_PASSWORD=YOUR_KEYSTORE_PASSWORD
SIGNING_KEY_ALIAS=YOUR_KEY_ALIAS
SIGNING_KEY_PASSWORD=YOUR_KEY_PASSWORD
```

Get your own Telegram API ID and hash from https://my.telegram.org/apps. Create your own Android signing keystore and place it at:

```text
TMessagesProj/config/exteraGram.jks
```

The Firebase Gradle plugin is enabled in this upstream-derived client. Put your own Firebase Android configuration at `TMessagesProj/google-services.json` and ensure it contains package name `com.godhead.messenger`. Never commit API_KEYS, the keystore, Firebase configuration, or passwords.

## 4. Build the APK

Open PowerShell in the repository root:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 :TMessagesProj:assembleFullArm64Debug
```

The full flavor includes the Python plugin runtime. To build a smaller client without that runtime, use:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 :TMessagesProj:assembleLiteArm64Debug
```

For a release APK, use the matching release task after configuring your own production signing key:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 :TMessagesProj:assembleFullArm64Release
```

Generated APKs are under:

```text
TMessagesProj/build/outputs/apk/
```

## Troubleshooting

- **Submodule missing:** run `git submodule update --init --recursive` again.
- **Wrong Java version:** check `java -version`; it must report JDK 17.
- **CMake not found / too old:** install CMake 4.0.0+ in Android SDK Manager. If Gradle cannot locate it, set `cmake.dir` in your local `local.properties` to the absolute CMake installation path (use escaped backslashes or forward slashes).
- **Python not found:** install Python 3.11 and ensure `python` is on PATH; configure `python.build` in `local.properties` if multiple Python versions are installed.
- **Firebase package mismatch:** download the Firebase configuration for Android package `com.godhead.messenger`.
- **Signing error:** confirm the keystore file path, password, alias, and key password match the values in `API_KEYS`.

A successful APK build confirms compilation and packaging only. It does not mean the independently operated GODHEAD backend, push notifications, calls, or every product feature is production-ready.
