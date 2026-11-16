# Flutter-first phone workflow integration — 2026-11-17

## Scope actually implemented

- Added a Dart allowlisted task specification and fail-closed per-step state machine (`flutter/lib/domain/phone_workflow.dart`).
- Added Gemini planning-only request, returning a JSON structure that is parsed against an allowlist. This does **not** give Gemini direct Android tool execution.
- Added a Flutter card for entering a task, reviewing a plan, approving one step at a time, cancelling and observing Android completion or failure.
- Reused the original Jiva native accessibility service and its security checks, rather than copying Kotlin source from other projects.
- Added Android platform-channel cancellation to clear a queued native screen operation.
- Added Dart tests for malformed steps, maximum plan length, verification and cancellation.

## Uploaded reference inspection

The five source ZIPs contain different stacks and **no Dart files**. Integrating them unchanged would bypass the requested Flutter-first architecture. Their code is not bundled in this source: concepts were independently implemented with the existing Jiva APIs.

| Reference ZIP | Main stack | License observed inside upload | Idea considered |
|---|---|---|---|
| OpenPhoneUse-main.zip | Kotlin | MIT | Visual screen commands |
| tapilot-main.zip | Kotlin / Android modules | MIT (root) | Structured execution and step feedback |
| sannabotapp-main.zip | React Native/TypeScript, Kotlin | Root license not found | Voice-first interaction; no code reused |
| deft-main.zip | React Native + local model modules | MIT | Offline inference architecture (not integrated) |
| closepaw-main.zip | Kotlin | Apache-2.0 | User-controlled assistant session |

## Safety model

Every execution is local and requires user approval for **each** step. Sensitive label keywords demand another confirmation. Native Accessibility still blocks password, login, banking and payment screens where detected; Android cannot guarantee classification for all apps. The AI cannot type, read credentials, make purchases or use arbitrary coordinates with this workflow. AI output should be considered untrusted. User can cancel the workflow; native queued request is cancelled through the platform channel.

## Not implemented / not verified

- Full autonomous control of any app, screenshot/vision model, arbitrary typing, foreground overlay, background-continuing multi-app tasks, push automation, universal phone control, an on-device Gemma model, Whisper offline inference, or continuous duplex realtime audio. These require additional permission, device-specific work, battery testing, and separate integration phases.
- No Flutter/Dart executable is installed in this runtime; **flutter analyze/test/build APK were not run**. Static source comparison is provided, but Android runtime verification remains pending.
- Existing Gemini model IDs are preserved from original, not independently verified against live service. The original voice client sends one text turn and collects audio; it is **not** full continuous streaming microphone duplex.
- No external repo source files were copied. Third-party license notices must be retained if copying code in a later phase.

## Practical validation on user workstation

```
cd flutter
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
flutter build apk --release
```

On a spare test device, enable Accessibility deliberately, plan a 2-step benign workflow in a non-sensitive app, approve step 1, switch to target app, inspect native result, then approve step 2. Test double tapping, timeout, cancelling while pending, lock screen, switching package mid-operation, backgrounding, revoked Accessibility permission, Gemini failure, and device restart. Do not use real banking or sensitive user data in tests.
