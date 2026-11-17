# Jiva AI — Flutter/Dart application

This is the **primary Jiva AI Android application**. Android-specific native code is used only for platform integration (such as accessibility and audio). A separate historical Kotlin prototype lives under the repository-root `app/` directory; it is **not** the APK built from this Flutter project.

- Version (from [pubspec.yaml](pubspec.yaml)): `1.0.1+2`
- Approved branding: [assets/branding/](assets/branding/)
- UI reference mockups (not actual app screenshots): [../docs/ui-reference/](../docs/ui-reference/)
- Permissions and integration boundaries: [../docs/ANDROID_PHONE_CAPABILITIES.md](../docs/ANDROID_PHONE_CAPABILITIES.md)

## Existing Android host — do not overwrite

The repository already contains `flutter/android/` with Gradle, manifests, platform sources, and the launcher/splash resources. **Do not routinely run `flutter create .`**, since generated output may overwrite configured Android features.

Use a Flutter SDK compatible with Dart >=3.12. From this `flutter/` folder:

```powershell
flutter pub get
flutter analyze
flutter test
flutter run
```

For the validated Windows pipeline, run `BUILD_FULL.ps1` from the **repository root**, not here. It also builds a release APK and attempts ADB install; see [../BUILD_FULL_README.md](../BUILD_FULL_README.md). It uses `dart format --output=write`, so check `git diff` afterward.

## Android environment

Install Android SDK, ADB, JDK 17, and relevant Flutter/Gradle dependencies. Follow the committed Gradle and Android manifest configuration rather than regenerating it. Verify Android 13+ microphone and notification permissions, Android 14+ foreground-service requirements, scheduled-reminder receivers, and the limited app-discovery query policy on a real phone.

The Android host may bootstrap a pinned Gradle wrapper JAR using [ensure-gradle-wrapper.ps1](android/scripts/ensure-gradle-wrapper.ps1) where necessary. Review third-party downloads before running.

## Capabilities and limitations

- **Voice/typed commands:** Local parser plus optional Gemini; model access/quota and language support depend on providers.
- **Gemini API key:** User-provided key; saving and removing requires a fresh operating-system biometric or device-credential check. `flutter_secure_storage` protects data at rest on supported devices but cannot make a compromised phone secure.
- **Voice conversation:** Device recognition and TTS behavior varies. Some inputs may be processed by the system speech provider. Do not promise always-on microphone streaming or flawless multilingual speech.
- **Phone actions:** Calls/messages are user-reviewed handoffs. Screen assistance and global navigation require explicit Android accessibility consent and confirmations. No unrestricted background control.
- **Wake words:** Optional Picovoice AccessKey, downloaded/imported compatible Android `.ppn` models, microphone notification and foreground service. Battery restrictions or force-stop can interrupt service.
- **Reminders:** Local notification delivery is dependent on notification permission and Android power policies.
- **Offline:** Offline/local components do not make remote Gemini requests work without internet.
- **Client-side credentials:** Gemini Live currently has documented client exposure risk. A separate backend prototype is included elsewhere in the repository but is **not an active requirement or deployed owner workflow**.

Never publish API keys, signing credentials or private screen images. Follow the [security guide](../SECURITY.md) and [owner key authorization audit](../docs/OWNER_ONLY_GEMINI_KEY_SECURITY.md). The project has been locally built and installed on one reported Android device, but wide-device runtime verification remains open.
