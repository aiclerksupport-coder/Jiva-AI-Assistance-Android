# Jiva AI Assistance for Android

Jiva is a user-controlled Android assistant MVP with voice and typed commands, a Material 3 dashboard, contact-picker based call/message flows, reminders, app launching, and an optional user-supplied Gemini API key.

## Features

- Voice recognition using the speech service installed on the phone; on Android 14+ Jiva requests language detection from compatible recognizers. Actual languages/accuracy depend on the device/provider and may require internet.
- Text-to-speech replies use the recognizer's reported language when available, otherwise the device's default voice.
- Local command matching for common English, Hindi, Spanish, French, and Chinese phrases, with Gemini JSON intent parsing for broader natural language when a key is configured.
- Gemini chat and intent parsing use the user's key and the Google Gemini `generateContent` REST API. The key is encrypted locally with an Android Keystore AES-GCM key. Requests are sent from the device directly to Google's API; do not share the key. API usage may incur provider charges and is subject to Google's terms/privacy policy.
- Call/message/WhatsApp flows use Android contact picker, system dialer, SMS composer, and WhatsApp share UI. User must review and confirm; Jiva does not silently call or send messages.
- Schedule local reminders with notifications; Android notification permissions and power-management settings can affect delivery.
- Search and launch installed apps using Android launcher intents. Protected device settings open the corresponding Android settings panel and require the user to make any change.

## Gemini setup

1. Install/run the app and tap **Connect Gemini**.
2. Enter your own Gemini API key and save. This app does not bundle a developer key.
3. Type or speak a request. Gemini handles requests not understood by the local phrase parser. If its structured response requests a supported phone action, Jiva opens the relevant user-confirmation flow.

Model configured in this MVP: `gemini-2.5-flash`. Model availability can depend on the user's Google AI account/region and may change; API errors are shown in the assistant UI. Direct client-side API keys can be extracted from a compromised/rooted device or app process; for a production service, use a backend with authentication, quotas, and key management rather than treating a client key as secret.

## Build and run

Open in Android Studio with Android SDK 35 installed, sync Gradle, and run the `app` configuration. Grant microphone permission when asked; allow notifications to receive reminders. The app needs internet access for Gemini and possibly speech recognition. Test contact selection and settings on a physical device or emulator with appropriate apps installed.

## Honest limitations and privacy

No normal Android app can control every phone feature, read other apps' private data, or interact with every third-party app as if it were the owner. Jiva uses public Android intents and permission/confirmation flows. WhatsApp integration is a share handoff: the user chooses the chat and taps send. Speech recognition, Gemini chat, and voice data handling depend on the selected providers; do not treat this as guaranteed offline, universal language support, or human-level understanding. Conversations in this MVP are held in memory only; the most recent turns are sent to Gemini for context when the user explicitly configures a key.
