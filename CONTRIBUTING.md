# Contributing to Jiva AI

Thanks for helping improve this experimental, Flutter-first Android AI assistant.

## Choose the correct implementation

The **primary** mobile app is under `flutter/` (Dart with native Android plugins). The root `app/` contains a separate Kotlin prototype. State which implementation your issue/PR changes; do not assume they share an APK.

## Before submitting a PR

1. Search existing issues and open a concise proposal for larger features.
2. Base work on current `main`; avoid changes unrelated to the issue.
3. Keep permissions minimal. No hidden recording, unattended sensitive actions, silent messages/calls, credential bypass or unrestricted accessibility automation.
4. Treat Gemini output and screen content as untrusted. Actions require local allowlists, validation, appropriate permission and explicit confirmation.
5. Do not add Firebase, cloud auth, gateways or new user-account dependencies to the owner's BYOK workflow without an explicitly approved architectural decision.
6. Do not commit `.dart_tool/`, `build/`, `.env`, passwords, API keys, keystores, contact data, proprietary wake models or unredacted screenshots.
7. Preserve the approved Jiva visual identity and compare UI changes with `docs/ui-reference/`; the images are concept references, not actual screenshots.
8. When user data, microphone, accessibility or third-party processing changes, update documentation and relevant safeguards.

## Tests

For Flutter changes, run `flutter pub get`, `flutter analyze` and `flutter test` in `flutter/`, and build/test on Android where appropriate. The root `BUILD_FULL.ps1` can run the complete Windows build and installation flow, but its formatter may modify source.

For Kotlin-prototype changes, run `testDebugUnitTest` and relevant instrumentation tests on a real device or emulator. Record exactly what passed and what was not run. CI is useful but does not replace device testing.

## PR checklist

Explain: issue/goal, changed files, permissions/data flows, user-visible behavior, original-base comparison, tests with pass/fail logs, rollback considerations, and screenshots/video for UI changes. Security-sensitive details belong in private vulnerability reports, not public issues.

Contributions are governed by the repository's [MIT License](LICENSE); ensure you have the right to submit any code or assets.
