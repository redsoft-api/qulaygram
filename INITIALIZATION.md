# Initialization Settings

This document summarizes all project initialization settings and how to configure them for local builds and CI.

## Required Files
- `TMessagesProj/google-services.json`: Firebase config (replace with your own).
- `TMessagesProj/release.keystore`: signing key for debug/release (replace as needed).
- `gradle.properties`: contains versioning and app ID.

## gradle.properties (App Identity)
- `APP_VERSION_NAME` / `APP_VERSION_CODE`: version metadata.
- `APP_PACKAGE`: applicationId used for publishing (default: `uz.qulaygram.app`).

Example snippet in `gradle.properties`:
```
APP_VERSION_NAME=12.1.1
APP_VERSION_CODE=6211
APP_PACKAGE=uz.qulaygram.app
```

## local.properties or Environment Variables
You can configure secrets via `local.properties` or environment variables (CI). Supported keys:
- Telegram API:
  - `TELEGRAM_APP_ID`
  - `TELEGRAM_APP_HASH`
- Signing:
  - `KEYSTORE_PASS`
  - `ALIAS_NAME`
  - `ALIAS_PASS`
- Build metadata (optional):
  - `BUILD_TIMESTAMP` (epoch ms)
  - `COMMIT_ID` (short SHA applied to versionName)
- CI convenience:
  - `LOCAL_PROPERTIES` (Base64 string of a local.properties file)

Example `local.properties`:
```
TELEGRAM_APP_ID=123456
TELEGRAM_APP_HASH=abcdef0123456789
KEYSTORE_PASS=secret
ALIAS_NAME=my_alias
ALIAS_PASS=secret
```

## ABI and Native Build Controls
- Env `NATIVE_TARGET` controls ABI outputs in `TMessagesProj/build.gradle`:
  - Unset or `universal`/`all`/`*`/`any`: build a universal APK (no splits).
  - Specific ABI (e.g., `arm64-v8a`, `x86_64`): build only that split.
  - `SKIP`: skip native and test tasks to speed CI (disables CMake/JNI/test tasks).

Examples:
```
# Universal APK
NATIVE_TARGET=universal ./gradlew :TMessagesProj:assembleRelease

# Single ABI APK
NATIVE_TARGET=arm64-v8a ./gradlew :TMessagesProj:assembleDebug
```

## Build Commands
- Full build: `./gradlew build`
- Assemble debug: `./gradlew :TMessagesProj:assembleDebug`
- Assemble release: `./gradlew :TMessagesProj:assembleRelease`
- Unit tests: `./gradlew test`
- Instrumentation tests: `./gradlew connectedAndroidTest`

Notes:
- App namespace remains `org.telegram.messenger` to match source imports. Changing namespace requires code-wide refactors.
