# Jiva AI Assistance — open-source use and warnings

Jiva is an experimental Android assistant MVP. It is not unrestricted phone-control software, and it is not a safety-critical service. Read [DISCLAIMER.md](DISCLAIMER.md), [TERMS_OF_USE.md](TERMS_OF_USE.md), [SECURITY.md](SECURITY.md), and [LICENSE](LICENSE) before building or using it.

## Responsibility and limitations

You decide whether to download, build, install, modify, or distribute Jiva. Review the code and permissions, verify AI output and every action, and follow applicable laws and third-party terms. Calls/messages are handed off for your review; Android/device providers can restrict or prevent actions. Reminders and language recognition are not guaranteed. Do not rely on Jiva for emergencies or safety-critical/time-critical work. Users remain responsible for their use, subject to rights/liability that cannot legally be waived.

## Privacy and Gemini

Gemini is optional. If you enter your own key, prompts are sent to Google for processing and may consume your account quota or incur charges. Keep the key private; local encryption cannot protect it from every compromised-device attack. Review Google's current terms and privacy documentation. Do not include sensitive information unless you accept the provider's data handling. Speech providers may process voice input according to their own settings and policies.

## Download/build/use

1. Review the source, dependencies, permissions, and legal notices.
2. Build from a commit/tag you trust using Android Studio and the Android SDK; inspect the resulting APK before installing.
3. Grant only the permissions you choose. Revoke them from Android settings when desired.
4. Review every proposed call, message, setting, reminder, app launch, and AI-generated response.
5. For Gemini, add your own API key in the app; the maintainers do not provide a key or cover API charges.

The source is licensed under MIT; the license's terms and disclaimer govern the software. The additional terms/disclaimer explain the project's intended use but cannot override mandatory law or create blanket immunity for any person. Seek legal advice before redistributing Jiva commercially or publishing it in an app store.

## Build and test

Open in Android Studio with Android SDK Platform 35, sync Gradle, then run the `app` configuration. For test sources, run `testDebugUnitTest` and `connectedDebugAndroidTest` on a device/emulator. This repo has not necessarily been built or tested for every Android version, device, language, or fork.

## Community

See [CONTRIBUTING.md](CONTRIBUTING.md) for pull request expectations and [SECURITY.md](SECURITY.md) for private vulnerability reporting guidance.
