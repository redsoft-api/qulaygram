# Repository Guidelines

This guide provides concise conventions for contributing to this Android Gradle project.

## Project Structure & Module Organization
- Root contains Gradle wrapper and multi-module setup. Modules are declared in `settings.gradle`.
- The Android app module(s) live under `TMessagesProj/` (e.g., `TMessagesProj/src/main/...`, `TMessagesProj/src/test/...`).
- Common folders:
  - `src/main/java/` and/or `src/main/kotlin/` for production code
  - `src/main/res/` for resources
  - `src/test/java/` for unit tests
  - `src/androidTest/java/` for instrumentation tests
- Key files: `gradlew`, `gradlew.bat`, `build.gradle` (root and module), `settings.gradle`, `google-services.json`, `proguard-rules.pro`.

Example paths:
- `TMessagesProj/src/main/kotlin/com/example/...`
- `TMessagesProj/src/test/java/com/example/...`
- `TMessagesProj/src/androidTest/java/com/example/...`

## Build, Test, and Development Commands
- Build all: `./gradlew build`
- Assemble debug APK: `./gradlew assembleDebug` (or `:TMessagesProj:assembleDebug` for a module)
- Assemble release APK: `./gradlew assembleRelease`
- Unit tests: `./gradlew test` (or `./gradlew testDebugUnitTest` for Android tests)
- Instrumentation tests: `./gradlew connectedAndroidTest`
- Lint: `./gradlew lint`

Tip: run module-specific builds with `./gradlew :TMessagesProj:assembleDebug` if you have multiple modules.

## Coding Style & Naming Conventions
- Languages: Java and Kotlin. Follow Android Studio/Google Java/Kotlin conventions.
- Indentation: 4 spaces per level.
- Naming: Java classes in UpperCamelCase; methods/fields in lowerCamelCase.
- Formatting: use Android Studio auto-formatting; if configured, apply Spotless/ktlint locally.

## Testing Guidelines
- Use JUnit for unit tests and Espresso/UiAutomator for UI tests where applicable.
- Place tests under `src/test/java` and `src/androidTest/java`.
- Aim for meaningful coverage per module; run `./gradlew test` and `./gradlew connectedAndroidTest` before PR.

## Commit & Pull Request Guidelines
- Commit messages follow Conventional Commits: `feat:`, `fix:`, `docs:`, `refactor:`.
- PRs should include a descriptive title, a concise description, and reference related issues (#ID).
- Include screenshots or logs for UI changes and ensure tests pass locally.

## Security & Configuration Tips
- Do not commit signing keys or secrets. Use local Gradle properties or environment variables for signing configs.
- Sensitive files like `release.keystore` should be excluded from VCS if possible.

Optional: if you want to add project-specific linters or formatting tools later (e.g., ktlint, spotless), document them here.

