# Jiva AI Assistance for Android

Jiva is a user-controlled Android assistant starter. The first MVP provides a voice interface, spoken replies, and a few safe Android actions. It is not—and Android does not permit an ordinary app to be—a universal unrestricted controller of the phone.

## MVP included

- Speech input using Android `SpeechRecognizer` and spoken responses using `TextToSpeech`.
- Hindi/English starter command matching (recognition language follows the device locale; language detection and broader multilingual support are future work).
- Open Android Settings and Camera.
- Start a phone call through the system dialer (`ACTION_DIAL`); Jiva never places a call silently.
- Start an SMS through the user's messaging app (`ACTION_SENDTO`); the user reviews and sends it.
- Unsupported/unclear commands receive a response rather than triggering a guessed action.

## Build and run

Open this repository in Android Studio (recent version with Android SDK 35), let Gradle sync, then run the `app` configuration on an Android device or emulator. Microphone permission is requested when voice input is used. Speech recognition may require an installed Android recognition service and network access depending on the device.

## Android capability and privacy boundaries

Android intentionally does not grant normal apps complete, silent access to every feature or other apps' private data. Actions such as changing protected settings, reading notifications, controlling other apps, or operating as the default phone/SMS assistant require specific APIs, roles, runtime permissions, and user consent; some actions are restricted or unavailable. Accessibility services must not be used as a way to bypass these safeguards. Jiva should request only the permissions needed for a feature, explain why, and keep sensitive actions user-confirmed.

## Planned roadmap

1. Add reminders with notifications and user-visible scheduling controls.
2. Add contact selection through Android's contact picker (avoid broad contact access where possible).
3. Add language detection, configurable voice/language, and robust Hindi-English command parsing.
4. Add app launching through user-selected shortcuts and supported intents.
5. Add explicit integrations for additional device controls, each using Android's supported permission/role flow.

The repository is a starting point, not a promise of unrestricted control. Do not store voice recordings or personal data without clear user consent.
