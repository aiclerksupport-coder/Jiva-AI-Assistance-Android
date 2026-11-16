# Jiva pending implementation, source audit and validation (2026-11-17)

## Verified in this candidate
- Workflow action correlation: per-step 128-bit random operation ID generated in Dart, persisted in encrypted checkpoint before dispatch, sent through MethodChannel and Kotlin AccessibilityService, returned from service, and validated against current workflow before advancing.
- Stale or cancelled native events are ignored, including same-action-type results. A process crash during dispatch still results in manual-review state, never auto replay.
- Three Dart unit tests added for mismatch, restart, and cancellation. **Not executed**, since Flutter/Dart/Android SDKs and physical Android device are absent in the current runtime.

## User requested, NOT fully delivered
- Human-like full-duplex voice: existing Gemini Live code is text-in/audio-out. Need live mic PCM stream, audio-focus lifecycle, barge-in, interruption-safe playback, model capability verification and instrumentation.
- Real on-device offline LLM: no downloaded/verified supported model, offline inference binary, model integrity verification or sufficient device RAM assessment. Existing offline actions are fixed-rule commands only.
- Autonomous multi-app: current bounded action workflow requires approval before each step, Android secure targets are blocked. This is intentional until result audit, UI diff verification, and safe task boundaries pass real-device tests.
- Dedicated background task engine: app has wake-word foreground service and secure workflow checkpoints, **not** a persistent platform-compliant scheduler/worker for arbitrary phone UI tasks. Android forbids assuming post-force-stop work or background microphone start.
- Release/actual phone testing: cannot claim passed without SDK, signed build, ADB device and explicit multi-device test matrix.

## Background patterns, five reference projects
- OpenPhoneUse: accessibility+voice interaction patterns; do not assume background UI access on locked or stopped device.
- Tapilot: independent task/voice runtimes can isolate lifecycles; requires explicit Android foreground conditions.
- Sanna: event queue and wake-word/scheduler ideas; headless JS cannot be copied into Flutter without rewrite.
- Deft: optional local inference requires model and native runtime; no offline LLM embedded in this archive.
- ClosePaw: accessibility service overlay is already the limited control model. It cannot bypass secure surfaces.

## VULNERABILITY, FLAW & REAL-WORLD FIX REPORT

### 1. Critical: old action results were accepted by type only
Real risk: two `click` callbacks could be indistinguishable, producing phantom completion after cancellation/restart.
Fix implemented: bind generated `operationId` through Dart -> native queue -> result -> Dart and check exact ID prior to `acceptResult`. Test on device with repeated fast clicks and app switches.
```dart
if (resultOperationId != session.operationId) return;
session.acceptResult(success: success, status: message,
  resultOperationId: resultOperationId);
```

### 2. High: background assistant cannot run unconditionally
OS stops/kills/foreground microphone restrictions prevent guaranteed hands-free operation. Fix: user-visible foreground mic service started while app foreground, background deferred *non-UI* jobs via WorkManager, explicit consent, no phantom execution claims. **Not implemented or tested here**.

### 3. High: voice disconnect/half-duplex
A text turn to Gemini Live does not constitute continuous mic conversation. Implement audio stream and interruption protocol only after model+protocol verification; ensure audio buffer bounds, user-visible mic state and battery tests. **Pending**.

### 4. High: offline LLM not actually present
Existing deterministic offline commands are not generative reasoning. Select licensed model, compatible inference runtime, measured phone memory budget, SHA256 model validation, and privacy-safe opt-in install. **Pending**.

### 5. High: multi-app execution and unknown UI state
Native actions may succeed even if Dart is killed before response; operation IDs prevent stale status but cannot guarantee exactly-once side effects. Never auto-retry unknown execution; require user review and fresh plan. Enforce package allowlist, screen validation, critical-action blocklist. **Partly mitigated**.

### 6. High: no release validation
Static syntax/source checks cannot substitute for `flutter analyze`, `flutter test`, `flutter build apk --release`, Kotlin unit tests, ADB cold-start, offline 10-day and lifecycle tests. **Pending**.
