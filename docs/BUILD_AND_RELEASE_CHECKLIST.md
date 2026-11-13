# Jiva Android build and release verification

This project contains **two independent implementations**. They should never be treated as one combined APK without an explicit integration project.

## Kotlin / Jetpack Compose (repository root)

- Use JDK 17 and Android SDK API 35.
- The repository currently does not contain Gradle wrapper binaries. GitHub Actions installs a pinned Gradle 8.9 runtime without relying on a wrapper.
- On Windows with Gradle installed: `gradle :app:testDebugUnitTest :app:assembleDebug`.
- Debug APK path: `app/build/outputs/apk/debug/app-debug.apk`.
- Before release, also run the Android instrumentation tests on a real device/emulator and verify microphone foreground service, wake-word lifecycle, permissions, contacts, notifications, reminder restoration after reboot, and accessibility confirmations.

## Flutter / Dart (`flutter/`)

- Requires Dart >=3.12 and Flutter compatible with the specified package constraints.
- The committed tree intentionally lacks generated Flutter platform hosts.
- From `flutter/`: `flutter create . --platforms android`, `flutter pub get`, `flutter analyze`, `flutter test`.
- Before a real APK build, configure the generated Android host per `flutter/README.md`, including microphone foreground service, required receivers, permissions, removal of broad QUERY_ALL_PACKAGES permissions, and Android Gradle/Kotlin versions.
- Then run `flutter build apk --release --verbose`. Do not assume Dart tests prove the Android host builds.

## Production security gates

- Never commit Gemini or Picovoice keys, signing credentials, keystores, or imported proprietary wake-word models.
- Client-side Gemini API keys are still present as a deliberate MVP trade-off. A backend issuing short-lived ephemeral credentials, with authentication, rate limits and revocation, is **required before exposing paid production credentials**.
- Accessibility permission is user opt-in. Verify that private screens and password fields are not read or acted upon; never assume secure-window detection is exhaustive.
- Jiva must not claim calls, messages, payments, or system setting changes completed when it only opened the user-visible handoff.
- Android may suppress background microphone and reminders depending on OEM restrictions, force-stop, notification permission, or power policies.
- CI does not replace on-device tests, privacy/legal review, or Play policy review.

## Compare to base

Baseline `e7017b0dca732dd7c190f18e69ed9bbf08a7e5d5`. Use `git diff --stat e7017b0...HEAD` and `git diff e7017b0...HEAD` to review every changed file before merging.
