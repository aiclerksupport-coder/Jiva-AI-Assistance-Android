
# Jiva Android — build, test, install

Jiva is an Android assistant MVP with a Material 3 dashboard, voice/typed input, a deterministic local command parser, optional user-provided Gemini integration, contact-picker based call/message handoffs, app launch, settings panels, and local reminders.

## Android phone-control boundary

Jiva is not unrestricted phone-control software. Android isolates apps and requires user-visible permissions/roles for sensitive features. The app can open supported apps/system screens, prepare calls/messages for review, and schedule reminders subject to device settings; it cannot silently control every app, send messages or place calls without user confirmation, read other apps' private data, or bypass Android protections. See [docs/ANDROID_PHONE_CAPABILITIES.md](docs/ANDROID_PHONE_CAPABILITIES.md). A unit-tested capability policy is included, but the project must still be built and tested locally.

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

## Flutter / Dart counterpart

The repository also contains a separate Flutter/Dart implementation in
[flutter/](./flutter/README.md). It does not replace the Kotlin Android app.
Install Flutter, follow the Android manifest setup in that guide, and run
`flutter create . --platforms android,ios`, `flutter pub get`, and
`flutter test` from the `flutter/` directory to create and run its platform
host.

Both apps use `gemini-3.8-flash` and send API keys in the `x-goog-api-key`
header. **Test & save** checks that the key can access that model before
storing it. This verifies key/model access, but cannot guarantee remaining
quota or billing availability for future generation requests.

Both apps also include Gemini Live spoken replies, ten selectable Gemini voice
presets (five male-sounding and five female-sounding; these are subjective
voice descriptions, not official gender labels), and a 30-second idle sleep
timer. Both apps provide **Clear voice processing** and **Patient listening**
settings. On Android 13+ the clear-voice mode uses an app-owned speech-focused
microphone input and enables Android NoiseSuppressor/AutomaticGainControl when
available, then supplies PCM audio to the system speech recognizer. The Android
recognizer may use a remote provider; only its recognized text is sent to
Gemini. Older Android versions, unsupported devices, and non-Android Flutter
platforms use their normal system speech recognizer. Patient listening extends
silence tolerance, but recognizer providers can ignore these hints; neither
mode guarantees recognition in very loud noise. This is not continuous Gemini
Live microphone streaming. Live API client-key exposure is called out in each
app. A backend issuing short-lived tokens is recommended for production.

Android background wake listening requires the user to enter a Picovoice
AccessKey, import custom Android `.ppn` models for both “Jiva” and “Giva,”
grant microphone and notification permissions, and start the visible
microphone foreground service from the app. The listener pauses while Jiva
speaks or processes a command. Force-stop, Android background restrictions,
and manufacturer battery controls can stop listening; wake words cannot
launch the app automatically on every device. Flutter-specific generated
manifest instructions are in [flutter/README.md](./flutter/README.md).
Stop the listener before replacing the Picovoice key or either model, then
start it again so the engine reloads the updated configuration.

Both apps provide optional visible-screen assistance through Android
Accessibility. The user must enable the service in Android Settings; Jiva only
reads the active visible accessibility tree after an explicit request and only
clicks a unique exact-label control after confirmation. Sensitive-looking
labels require a second confirmation. Password fields block reading and
actions, and screen text is not stored or sent to Gemini by this feature.
Android does not expose a reliable signal for every app's secure-window state,
so Jiva cannot technically guarantee detection of every secure screen. Do not
use screen assistance on login, banking, payment, or other private screens.
Back and scroll actions are limited to the currently visible screen; this is
not unrestricted device control. After confirming a read or action, switch to
the intended app within 30 seconds; Jiva handles the next non-Jiva app window
once and returns the result when you come back.

After confirming a voice action, Android users can also ask Jiva to go Home,
go Back, open Recents, show Notifications, or open Quick Settings. This uses
Android's documented accessibility global actions and requires the user to
enable Jiva's service. Notification content may be private. This remains an
allowlisted set of system navigation actions—not arbitrary taps, text entry,
app installation, or background control of every app.

## References

- [Gemini Live API](https://ai.google.dev/gemini-api/docs/live)
- [Android microphone foreground-service type](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)
- [Android NoiseSuppressor](https://developer.android.com/reference/android/media/audiofx/NoiseSuppressor)
- [Android AutomaticGainControl](https://developer.android.com/reference/android/media/audiofx/AutomaticGainControl)
- [Android RecognizerIntent audio source](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE)
- [Google AI Edge Gallery Android speech-recognition example](https://github.com/google-ai-edge/gallery)
- [MNN Android ASR example](https://github.com/alibaba/MNN)
- [Android AccessibilityService global actions](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#performGlobalAction(int))
- [DroidRun Agent](https://github.com/hanxi/droidrun-agent) — accessibility-tree/global-action API patterns
- [Klick'r / Smart AutoClicker](https://github.com/Nain57/Smart-AutoClicker) — configurable visual workflows; Jiva does not adopt its unattended click automation
- [Picovoice Porcupine custom wake words](https://picovoice.ai/docs/porcupine/)
- [Flutter foreground-task Android setup](https://pub.dev/packages/flutter_foreground_task)

## Screen flow

1. Launch Jiva dashboard. Type a request or tap **Speak**; microphone access is requested only when needed.
2. Local supported commands generate an action preview. Unknown language/phrasing falls back to Gemini only if the user configured their own API key; otherwise Jiva says it did not understand.
3. Call/message commands open a contact picker. The selected contact is displayed in a review dialog; cancel does nothing.
4. Approve an action to hand off to the Android dialer, SMS composer, WhatsApp/share chooser, app launcher, camera, or Settings. The user completes calls/sends messages and protected settings changes.
5. Reminder opens a date/time editor, schedules a local notification, and asks for notification permission where applicable.

## Test scope and limitations

`LocalCommandParserTest` checks several local intent decisions and that unknown text falls through. `AssistantScreenTest` is a basic Compose device/emulator smoke test for dashboard controls. These tests are added but have **not** been run here. Speech-language coverage depends on the phone's recognition/TTS provider. Android does not permit a normal app to silently control every phone feature. Gemini user text is sent to Google when Gemini is enabled; client-side API keys can be exposed on compromised devices despite encryption at rest.
