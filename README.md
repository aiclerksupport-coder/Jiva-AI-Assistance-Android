# Jiva AI Assistance for Android

Jiva is an Android assistant MVP built around user control and Android-supported APIs. The app has an assistant dashboard, local command parser, optional user-provided Gemini integration, contact picker, explicit action confirmation, app launching, and local reminders.

## Build and install

1. Install Android Studio and Android SDK Platform 35.
2. Open this repository in Android Studio and allow Gradle sync.
3. Connect an Android phone with USB debugging enabled (or start an emulator).
4. Choose the `app` run configuration and press Run. Android Studio installs the debug build on the selected device.
5. For a standalone APK, use **Build > Build Bundle(s) / APK(s) > Build APK(s)**. The debug APK is usually under `app/build/outputs/apk/debug/`; Android Studio must build it first. You can install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

This repository does not include a prebuilt APK or a signed production release. The source has not been built/tested in this GitHub editing environment; validate the build and runtime on Android Studio/device before relying on it.

## What the MVP implements

- Voice input through the speech service installed on the phone and typed requests.
- Jiva-branded Material 3 dashboard.
- Small deterministic local parser plus Gemini JSON intent interpretation when the user supplies a key.
- User API key encrypted at rest using Android Keystore-backed AES-GCM; API calls go directly to Google's Gemini REST endpoint. The app does not bundle a developer key. A compromised device may expose a client-side key.
- Contact picker without broad contacts permission; call/SMS/WhatsApp flows show review UI and hand off to Android or the chosen app. The user must complete the call/message.
- Explicit confirmation for app launch, camera, and settings actions.
- Local one-time reminders with notification permission request.

## Limits and privacy

Speech/language coverage and TTS voices depend on the Android provider/device. Gemini is not required for local phrase commands; requests sent to Gemini include user text and should be used only after the user configures their key. Jiva does not promise universal language coverage, human-level understanding, guaranteed notification delivery, or unrestricted phone control. Protected device settings are changed by the user in Android Settings. WhatsApp is a share handoff: the user chooses the chat and taps Send.

See [docs/ANDROID_ARCHITECTURE.md](docs/ANDROID_ARCHITECTURE.md) for the architecture and production hardening plan.
