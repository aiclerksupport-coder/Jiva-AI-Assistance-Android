# Jiva Flutter-first integration — source audit and remaining blockers

Baseline: `Jiva-AI-Assistance-Android-main.zip` (SHA-256: `84af38b3504820b9c454b6faa122b1e751924e0cad05d9b767e0e1ade808db5f`).
Prior working candidate: `Jiva-AI-FlutterFirst-PhoneWorkflow-CANDIDATE-SOURCE.zip`.

**Status: unverified candidate source, NOT a working production APK.** Flutter/Dart and Android SDKs were not available in the generation environment. All pending features are **not** complete. No downloaded third-party source files have been copied.

## Source references actually inspected
- `OpenPhoneUse-main.zip`: Android Kotlin phone agent, MIT license.
- `tapilot-main.zip`: Android automation agent, MIT license.
- `sannabotapp-main.zip`: React Native + Kotlin Android integration; no root LICENSE file found. Do not copy without checking the appropriate grant.
- `deft-main.zip`: TypeScript/React Native-based local agent integration, MIT license.
- `closepaw-main.zip`: Android Kotlin agent with capsule concepts, Apache-2.0 license.

## Changes relative to previous candidate
1. Added bounded user-approved `type` step to Dart workflow, accessibility channel and Android service. Android action is allowed only if exactly one focused editable visible non-password field exists; private screens block it. Types at most 100 characters. This is NOT unrestricted text input.
2. Added immutable JSON encoding and strict versioned recovery of bounded workflow plans.
3. Added serialized encrypted checkpoint storage through existing `flutter_secure_storage`. Checkpoint saved *before* native action dispatch; restored in-flight actions cannot auto-replay. Acknowledged results are checkpointed before the next dispatch is allowed.
4. Added deterministic offline planner for the exact phrases read screen, back, scroll up and scroll down (including limited Hindi/Hinglish forms). **This is not an offline LLM**.
5. Added an in-app workflow review floating action control. **This is not a system-wide floating overlay.**
6. Native Accessibility result bridge temporarily buffers one capped result while detached and flushes it on activity reattachment. Does not survive Android process death.
7. Added offline planner, checkpoint recovery and strict typing tests; **tests have NOT been executed**.

## VULNERABILITY, FLAW & REAL-WORLD FIX REPORT

### F1 — Native workflow results can be lost when Flutter Activity is detached
Impact: task can appear stuck or wrongly inferred as completed. Fix: temporary capped native buffer; long term add `requestId` to all queue and result methods and persist an acknowledgement journal. Never infer action success from time alone.

### F2 — App-kill during an Android action makes its outcome uncertain
Impact: repeating a click or type action could duplicate changes. Fix: encrypted write-ahead checkpoint and refuse replay of `inFlight=true`. For business transactions, use server-defined idempotency keys with explicit status verification, not generic UI-click retries.

```dart
final recovered = PhoneWorkflowSession.fromCheckpoint(json);
if (recovered.uncertainAfterRestart) {
  // Block retry and require manual review/replanning.
  return;
}
```

### F3 — UI-only task state loses user progress after restart
Impact: incomplete workflow disappears. Fix: `WorkflowCheckpointStore` serializes checkpoint writes and restores a paused plan. Confirm that Android keystore and backup exclusion policies behave as expected on the test phone.

### F4 — Unrestricted text injection into Android fields is unsafe
Impact: accidental sensitive input, password exposure, wrong recipient. Fix: currently limited to one focused non-password editor, 100 chars, user approval, private screen checks. Add per-target package allowlist and explicit transaction-classification before enterprise deployment.

### F5 — Offline AI claim would be misleading
Impact: users expect a generative AI assistant with no network. Fix: narrow deterministic offline planner only. Full on-device LLM requires memory/CPU benchmarking, licensed model, local inference runtime, signed model download and secure lifecycle.

### F6 — Screenshots and computer vision are NOT implemented
Impact: accessibility node labels may miss graphical controls. Future fix: implement explicit-consent Android Accessibility screenshot or MediaProjection with FLAG_SECURE handling; obtain separate approval before transmitting image to provider; redact sensitive regions; prefer local image analysis and avoid retaining screenshots. Never reuse screenshots as silent background capture.

### F7 — Human-like duplex voice remains incomplete
Impact: live speech turn currently uses text-to-audio request; microphone audio interruption, barge-in and session resume are not a complete duplex loop. Future fix: dedicated streaming PCM input/output session, echo cancellation (AEC), interruption generation IDs, backpressure, safe reconnect and real-device latency tests. Preserve owner-protected API key storage.

### F8 — System-wide floating capsule is NOT implemented
Impact: in-app floating button disappears when other app is foreground. Future fix: Android overlay foreground service with user permission and a persistent, dismissible notification. Make it optional, never hide record/capture state.

### F9 — General autonomous multi-app execution is NOT implemented
Impact: workflow handles bounded reviewed steps, not arbitrary Android tasks. Future fix: use app package allowlists, planner/executor split, unique action IDs, per-step before/after screen verification, strict rate and cost limits, cancellation and rollback semantics. Avoid auto-pay or credential workflows.

### F10 — CI/build/device validation missing
Impact: Kotlin method signatures, Android API policies and Dart packages may fail to compile or behave differently on real phone. Fix: run `flutter pub get`, `dart format --output=none --set-exit-if-changed .`, `flutter analyze`, `flutter test`, `flutter build apk --release` from the `flutter` subfolder on a provisioned toolchain. Test with Accessibility enabled/disabled, app backgrounded, process killed, phone locked, screen rotated, network dropped, repeated taps and input on real device. Use failure logs to patch root causes.

### F11 — Privacy heuristics cannot reliably detect every sensitive screen
Impact: graphical banking and login windows may be wrongly classified as safe. Fix: explicit package denylist for known sensitive apps, scoped allowlist for supported apps, never access secure windows, user-in-loop confirmation, independent manual testing, and clear permission disclosures.

### F12 — Third-party license and dependency review incomplete
Impact: copying Sanna code without an explicit license grant creates legal risk; native/runtime dependencies can introduce vulnerabilities. Fix: no third-party sources copied. Audit third-party SBOM, licenses and vulnerabilities before any direct reuse. Obtain permission as necessary.

## Release gate
Do NOT label this candidate as `completed`, `build passed`, `working APK`, `full offline AI`, `screenshot vision`, `full-duplex`, or `system-wide capsule`. No production release until the remaining work and tests above are done.
