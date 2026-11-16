# Jiva AI — Live Voice / Background Integrity Batch (17 November 2026)

## Actual source implementation
- User-initiated PCM 16kHz microphone via native EventChannel, Dart LiveAudioTransport, PCM 24kHz speaker via AudioTrack, lifecycle Stop and interrupt flush.
- Owner-entered model ID, stored in encrypted settings. No obsolete preview model is hardcoded. Availability must be checked before testing.
- Existing wake-word and security classes kept byte-identical; live microphone stops when Jiva leaves foreground.
- Secure deferred-job journal serialization, no unattended UI action, recovery must mark ambiguous leases for review.
- Dart deferred-journal tests added but not run here.

## Verification and blockers
Flutter, Dart, Gradle and adb are unavailable in this container. This is an UNVERIFIED SOURCE candidate. No release APK or connected phone test has been performed.

## VULNERABILITY, FLAW & REAL-WORLD FIX REPORT

1. **Critical: no full offline generative LLM.** Deterministic offline commands are not intelligence. Select licensed quantized model and supported Android inference backend (e.g. MediaPipe LLM Inference, llama.cpp Android), verify model checksum & RAM, implement native-to-Dart tokens with cancellation. Do not send model prompts to cloud without approval.
2. **High: OS background scheduling missing.** Journal is not WorkManager; no independent worker exists. Add Android WorkManager for idempotent cache work only; persist state with atomic durable storage, leases and retry budget, and use user-visible microphone foreground service only when allowed by OS.
3. **High: voice pipeline unverified.** Permission, AudioRecord/AudioTrack I/O and Gemini Live protocol must pass device tests. Test Bluetooth, phone calls, audio focus, echo cancellation, rotation, background, interruption, connection loss. For example: `await session.stop()` on `AppLifecycleState.paused`.
4. **High: model support varies.** The owner specifies a supported native-audio Live model ID; fail closed on invalid ID or server rejection, never auto-switch models or leak API key.
5. **High: multi-app autonomy not verified.** For each user-approved step: bind exact operation ID, verify package/window before/after, pause uncertain steps, never auto-repeat payments, deletes, credential/OTP or confirmation actions.
6. **Medium: audio-output backpressure.** Native AudioTrack single-thread executor preserves order but lacks bounded queued PCM frames. Add small bounded audio queue and drop/pause on backpressure before production.
7. **Medium: privacy.** Microphone PCM must not be logged or persisted. Inform users of cloud audio processing, retain immediate Stop and clear playback on interruption. Screen uploads require separate approval.
8. **Critical: release gate.** On user's Windows machine run `flutter pub get`, `flutter analyze`, `flutter test`, `flutter build apk --release` and ADB cold-start/accessibility/voice tests. Do not promote candidate until all pass and diff is reviewed.

## Proposed sample Dart guard
```dart
if (!appVisible || !userApproved || action.operationId != result.operationId) {
  return; // Never advance an uncertain background action.
}
```
