# Jiva AI Assistance — open-source Android assistant

> **Please read before installing:** Jiva is an experimental assistant, not unrestricted phone-control software and not a safety-critical service. AI, speech recognition, translations, app handoffs, and reminders can be inaccurate or fail. Review every proposed action yourself.

## Quick links

- [MIT License](LICENSE)
- [Disclaimer and safety notice](DISCLAIMER.md)
- [Draft terms of use](TERMS_OF_USE.md) — informational draft, not legal advice
- [Security reporting](SECURITY.md)
- [Contribution guidelines](CONTRIBUTING.md)
- [Architecture and MVP plan](docs/ANDROID_ARCHITECTURE.md)
- [Legal review notice](LEGAL_REVIEW_REQUIRED.md)

## Important use and responsibility notice

You choose whether to download, build, install, modify, or redistribute this project. Before use, review the source, dependencies, requested permissions, and the legal notices above; build only from code you trust; and follow applicable laws and third-party terms. Verify Jiva’s responses, generated text, translations, recipient, reminder, and action before relying on them.

Jiva does **not** have unrestricted access to an Android phone. Android, device manufacturers, app developers, network providers, user settings, and battery policies may restrict features or affect results. Calls open the system dialer; SMS and WhatsApp use app handoffs; the user reviews and completes sending. Settings screens require the user to make protected changes. A reminder being scheduled does not guarantee delivery. Do not rely on Jiva for emergencies or medical, financial, safety-critical, or time-critical tasks.

This notice does not transfer or eliminate legal responsibility, guarantee immunity for maintainers or distributors, or waive rights/liabilities that cannot legally be waived. The software is licensed under the [MIT License](LICENSE), including its own terms and disclaimer. Seek qualified legal advice before commercial distribution, app-store publication, or reliance on any legal notice.

## Privacy and Gemini

Gemini is optional. If you enter your own API key, Jiva sends your prompt to Google for processing. This may use your account quota or incur charges and is governed by Google’s current terms and privacy practices. The key is encrypted at rest using Android Keystore, but client-side encryption cannot guarantee protection on a compromised device. Keep your key private, revoke it if exposed, and do not send sensitive information unless you accept provider handling.

Voice recognition and text-to-speech depend on the Android provider and installed voices; provider data handling may vary. Language detection, accuracy, offline use, and notification delivery are not guaranteed. Review provider privacy policies and your device settings.

## Build and install

1. Install Android Studio and Android SDK Platform 35.
2. Open this repository in Android Studio and allow Gradle sync.
3. Connect an Android phone with USB debugging enabled, or start an emulator.
4. Select the `app` run configuration and press **Run** to build and install the debug app.
5. To create a debug APK, use **Build > Build Bundle(s) / APK(s) > Build APK(s)**. After a successful build, Android Studio writes it under `app/build/outputs/apk/debug/`. You can install with Android Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Review the app permissions and this repository before installing. Only grant microphone/notification permissions when you want those features. You can revoke permissions later in Android Settings.

## App usage flow

1. Read the in-app first-run safety and privacy notice and acknowledge it to continue.
2. Type a command or tap **Speak**. Jiva requests microphone permission only when voice input is used.
3. Common local commands are handled by the local parser. Other text is sent to Gemini only if you configured your own key.
4. For calls and messages, choose a contact and review the destination and draft. Jiva hands off to the dialer or messaging app; you complete the action there.
5. Confirm app, camera, or Settings handoffs. Android may require you to make protected changes in Settings.
6. Choose reminder text/date/time and allow notifications if you want alert notifications.

## Build/test status

This repository contains test sources, but the project has not necessarily been built or tested for every Android version, device, language, or fork. Run the tests on your machine before relying on the app:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

The connected test command requires an emulator or device. If the Gradle wrapper is not present, use the Gradle tasks through Android Studio or set up a wrapper first. Do not assume tests passed unless you ran them and checked the results.

## Community

Read [CONTRIBUTING.md](CONTRIBUTING.md) before proposing changes. For security concerns, follow [SECURITY.md](SECURITY.md); do not post API keys, personal data, or sensitive exploit details publicly.
