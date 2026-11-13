# Jiva AI Assistance for Android

Jiva is a user-controlled Android assistant starter. This MVP adds a voice-first UI, installed-app launcher, and scheduled local reminders. It uses Android speech recognition and text-to-speech services rather than claiming universal language understanding or unrestricted phone control.

## Features in this MVP

- Voice input through the device's Android speech recognition service.
- Requests automatic language detection from compatible recognizers on Android 14+; available languages and detection quality depend on the recognition provider/device and may need internet access.
- Spoken replies use the detected writing system where the device TTS engine has a matching voice; otherwise the device's default voice is used. Script detection is not the same as identifying every spoken language—e.g. Latin-script languages need the recognizer/default locale.
- Hindi/English and common device-language command phrases for settings, camera, calls, messages, reminders, and app opening.
- Search and launch installed apps using Android's launcher intents and package visibility declarations.
- Schedule a local reminder with date/time and a notification. Android 13+ notification permission is requested after scheduling; notifications remain subject to user settings and OEM battery policies.
- Calls open the system dialer; messages open the user's messaging app. Jiva does not silently place calls or send SMS.

## Build and run

Open in Android Studio with Android SDK 35 installed, sync Gradle, and run the `app` configuration on a device/emulator. Grant microphone permission when asked. A compatible speech-recognition service and TTS voice are required; recognition may require network access. To test reminders, allow notifications and schedule a future time.

## Honest language support

There is no single Android API that guarantees accurate, offline, automatic recognition of every language on every phone. Jiva asks compatible speech services to detect a language where the OS/provider supports it, but providers may ignore that request. The app displays this limitation instead of pretending language detection always works. For reliable broad multilingual conversation and intent recognition, a later version needs an explicitly selected speech/AI provider, language coverage, privacy disclosure, network/error handling, and user consent before audio is sent to a service.

## Android capability and privacy boundaries

An ordinary Android app cannot silently control the entire phone or access other apps' private data. Protected actions require supported APIs, roles, runtime permissions, and user consent; some operations are restricted or unavailable. Jiva requests microphone access only for voice use and uses system intents for calls/messages. It does not keep audio recordings. Avoid using accessibility services to bypass Android safeguards.
