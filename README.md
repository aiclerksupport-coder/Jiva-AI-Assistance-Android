# Jiva Android — build, test, install

Jiva is an Android assistant MVP with a Material 3 dashboard, voice/typed input, a deterministic local command parser, optional user-provided Gemini integration, contact-picker based call/message handoffs, app launch, settings panels, and local reminders.

## Build and install

1. Install Android Studio and Android SDK Platform 35.
2. Open this repository in Android Studio and allow Gradle sync.
3. Connect an Android phone with USB debugging enabled (or start an emulator).
4. Select the `app` run configuration and press **Run** to build/install the debug app.
5. For a debug APK, use **Build > Build Bundle(s) / APK(s) > Build APK(s)**. After a successful build, Android Studio writes it under `app/build/outputs/apk/debug/`. Install using Android Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

No APK has been built or tested in this GitHub editing environment. The repository has unit and instrumentation test sources, but run them locally before trusting a build:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

The second command requires an emulator/device. No Gradle wrapper is currently included; if `./gradlew` is missing, create/download the wrapper from Android Studio's Gradle tooling or run the corresponding Gradle tasks from Android Studio.

## Screen flow

1. Launch Jiva dashboard. Type a request or tap **Speak**; microphone access is requested only when needed.
2. Local supported commands generate an action preview. Unknown language/phrasing falls back to Gemini only if the user configured their own API key; otherwise Jiva says it did not understand.
3. Call/message commands open a contact picker. The selected contact is displayed in a review dialog; cancel does nothing.
4. Approve an action to hand off to the Android dialer, SMS composer, WhatsApp/share chooser, app launcher, camera, or Settings. The user completes calls/sends messages and protected settings changes.
5. Reminder opens a date/time editor, schedules a local notification, and asks for notification permission where applicable.

## Test scope and limitations

`LocalCommandParserTest` checks several local intent decisions and that unknown text falls through. `AssistantScreenTest` is a basic Compose device/emulator smoke test for dashboard controls. These tests are added but have **not** been run here. Speech-language coverage depends on the phone's recognition/TTS provider. Android does not permit a normal app to silently control every phone feature. Gemini user text is sent to Google when Gemini is enabled; client-side API keys can be exposed on compromised devices despite encryption at rest.
