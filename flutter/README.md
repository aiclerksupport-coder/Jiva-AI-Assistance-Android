# Jiva Flutter app

This directory is the Dart/Flutter counterpart to the Kotlin Android app at
the repository root. The Flutter source is kept separate so the existing
native Android app and its Gradle project remain unchanged.

## Create the Flutter platform host

Install Flutter 3.44 or newer, then from this directory create the platform
runner files and resolve packages:

```sh
flutter create . --platforms android,ios
flutter pub get
flutter test
flutter run
```

The foreground-task plugin also requires Kotlin 2.2.20+ and Gradle 8.11.1+
in the generated Android host.

The checked-in Dart code and tests do not include generated Android/iOS
runner projects. `flutter create` creates those local platform files.

## Android setup

In the generated `android/app/src/main/AndroidManifest.xml`, add the feature
permissions and app-launcher query below inside `<manifest>`. Do not add
`READ_CONTACTS`; the system contact picker is used instead.

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

Inside `<application>`, declare the foreground-task service with the
microphone type required on Android 14+:

```xml
<service
    android:name="com.pravera.flutter_foreground_task.service.ForegroundService"
    android:foregroundServiceType="microphone"
    android:exported="false"
    android:stopWithTask="false" />
```

The application requests notification permission before starting this service.
Start listening while Jiva is open and visible; Android restricts starting a
microphone service from the background. The foreground notification includes a
Stop action. The service cannot restart after force-stop, and some Android
versions or manufacturers may still stop it under battery or background
restrictions.

The installed-apps plugin declares `QUERY_ALL_PACKAGES` in its own manifest.
This app only needs launcher-visible apps, so remove that broad permission in
the generated app manifest by adding the `tools` namespace to the root element
and this entry:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission
        android:name="android.permission.QUERY_ALL_PACKAGES"
        tools:node="remove" />
```

For scheduled local notifications, also register the notification plugin's
scheduled-notification and boot receivers as described in the
[`flutter_local_notifications` Android setup](https://pub.dev/packages/flutter_local_notifications#-android-configuration).
Android 13+ notification permission is requested when scheduling a reminder.
Reminder delivery remains subject to Android and device battery policies.

## Features and boundaries

- Kotlin implementation stays in the root Android project; Flutter has an
  independent Dart parser, action policy, Gemini client, secure key store,
  confirmation UI, voice input, app/contact pickers, and reminders.
- Calls and SMS open the system dialer/composer; WhatsApp uses the system
  share sheet. Jiva never places calls or sends messages silently.
- A selected contact or manually entered number is reviewed before handoff.
- API keys are stored with `flutter_secure_storage`; client-side keys are not
  protected from a compromised device. Gemini requests send user text to Google.
- **Test & save** checks access to the configured model before storing a key.
  It does not guarantee that the project has generation quota or billing
  enabled for later requests.
- Ten Gemini Live voice presets are selectable. The five male-sounding and
  five female-sounding descriptions are subjective, not official Google voice
  gender classifications. Speech input uses the device recognizer and sends
  recognized text to Gemini; it is not continuous Gemini Live microphone
  streaming.
- **Clear voice processing** and **Patient listening** settings improve input
  handling when possible. On Android 13+, clear-voice mode captures
  speech-focused PCM and enables Android NoiseSuppressor/AutomaticGainControl
  when the device supports them, then supplies audio to the system speech
  recognizer. The recognizer may process audio remotely; only recognized text
  is sent to Gemini. Unsupported devices and other platforms use their normal
  speech recognizer. Patient listening increases silence tolerance, but a
  speech provider may ignore the hint; neither setting guarantees accurate
  recognition in very loud noise.
- Background “Jiva” and “Giva” detection requires a Picovoice AccessKey and
  two custom Android `.ppn` models generated in Picovoice Console. Save the key
  and import the models through **Wake-word settings**; do not commit keys or
  private model files. Detection is local. The mic pauses while Jiva captures a
  command or speaks, and the 30-second idle timer does not interrupt work.
  Stop and restart background listening after changing the AccessKey or models.
- Gemini Live uses the configured client key in its WebSocket URL. A
  production app should exchange a backend-issued short-lived token instead.
- Speech recognition and text-to-speech depend on the device's available
  providers. Android app discovery is restricted to launchable apps; the
  feature is Android-only.
- Optional visible-screen assistance and Android enhanced voice input use the
  local `jiva_accessibility` Android plugin. Enable the service in Android
  Accessibility Settings for screen assistance.
  Reading is explicit and on-demand; clicks require an exact unique visible
  label and confirmation, with a second confirmation for sensitive-looking
  labels. Password fields block screen reading and actions. Screen text stays
  local and is not sent to Gemini by this panel. Android does not expose a
  reliable signal for every secure window, so do not use this on login,
  banking, payment, or other private screens. This is not unrestricted device
  control. After confirming a read or action, switch to the intended app
  within 30 seconds; Jiva handles the next non-Jiva app window once and returns
  the result when you come back.
- Confirmed voice commands can use Android global navigation actions: Home,
  Back, Recents, Notifications, and Quick Settings. These require the enabled
  accessibility service; the notification shade may expose private content.
  The feature does not perform arbitrary taps or run unattended app workflows.
- Add the microphone, speech-recognition, and camera usage descriptions to
  `ios/Runner/Info.plist` if building the iOS runner.

The current editing environment does not have Flutter or Dart installed, so
the Flutter test/build commands have not been run here.

Audio-input references: [Android NoiseSuppressor](https://developer.android.com/reference/android/media/audiofx/NoiseSuppressor),
[Android AutomaticGainControl](https://developer.android.com/reference/android/media/audiofx/AutomaticGainControl),
[RecognizerIntent audio source](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE),
[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery), and
[MNN Android ASR](https://github.com/alibaba/MNN).
