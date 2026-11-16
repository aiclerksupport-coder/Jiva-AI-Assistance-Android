# Jiva AI — Pending Capability Audit / Vulnerability, Flaw & Real-World Fix Report

**Status: SOURCE CANDIDATE ONLY — NOT BUILT OR RUN ON ANDROID.** Flutter/Dart stays primary; Kotlin exists only for native Android accessibility/window APIs. Prior original project and preceding candidates retained. No GitHub project source copied into this batch.

## Verified code changes in this batch

- Android `JivaAccessibilityService` provides opt-in `TYPE_ACCESSIBILITY_OVERLAY` floating controls while user has explicitly enabled Jiva Accessibility. Controls: **Jiva** (opens app), **Stop** (cancels pending request and reports failure), **×** (closes). No mic, screenshot, cloud call or automated app clicks are started by these controls.
- Overlay is removed on service unbind/destroy, hidden when Jiva is disabled, and its toggle is exposed in Flutter. Previous in-app floating workflow indicator remains unchanged. No additional overlay permission required because this overlay is owned by an enabled AccessibilityService, unlike a general SYSTEM_ALERT_WINDOW overlay.
- Previous screenshot, vision, checkpoint, voice and phone-action sources retained. Their actual Android behavior has not been runtime-verified.

## Pending table — no claim of complete integration

| Requested feature | Actual source assessment | Remaining validation/work |
| --- | --- | --- |
| Screenshot AI visual reasoning | One-time user-approved screenshot + Gemini image explanation exists in preceding candidate | Actual-device screenshot + remote consent audit, content redaction, image prompt injection, before/after action grounding |
| Continuous full-duplex voice | `GeminiLiveClient.respond` is text -> audio per turn, not continuous microphone streaming | Capture PCM16 microphone in visible foreground session, stream to Live, interruption/barge-in, VAD, TTS/echo handling, reconnect tests |
| Full offline on-device LLM | Deterministic `OfflinePhonePlanner` only | Actual licensed on-device runtime/models, verified offline install/inference, RAM/thermal/storage profiles |
| System-wide floating assistant | **Opt-in accessibility overlay control code added in this batch** | Compile, test settings/off/on, rotate, lock/unlock, service restart, OEM battery restrictions; it does not provide autonomous speech or tasks |
| Autonomous multi-app workflows | User-confirmed 1-step-at-a-time `PhoneWorkflowSession`; 8-step plan maximum | Cross-app orchestration, stable request IDs, per-app allowlists, sensitive-action approval, state verification, timeout handling |
| Dedicated background task engine | Wake foreground service exists; in-memory work + encrypted paused checkpoints | Durable work scheduler, explicit user resumed sessions, Android 14-16 FGS policy compliance, process-kill tests; force-stop must not be bypassed |
| Flutter analyze/test/build & physical phone | Environment lacks Flutter CLI/Android SDK | Run commands on configured Windows workstation/device. Passing ZIP/hash checks are **not** build verification |

## VULNERABILITY, FLAW & REAL-WORLD FIX REPORT

### 1. Critical: async result ambiguity and stale results
**Risk:** Old async screen-result can be confused with a subsequent identical action, especially after cancellation. The existing Dart callback correlates *type* but not a unique operation ID. **Fix:** Generate cryptographically random operation ID in Dart, persist before native dispatch, pass it into every Kotlin request and result; reject mismatch and do not retry uncertain writes automatically. Update the callback signature and explicit resume UI, and test cancel/replan, duplicate events, process death, timeout.

```dart
// Proposed future fix; not implemented in this candidate.
if (nativeResult.operationId != session.currentOperationId) return;
if (nativeResult.status != ActionStatus.verified) {
  session.pauseForManualReview();
}
```

### 2. High: Accessibility events can be absent or delayed
**Risk:** Service currently acts after TYPE_WINDOW_STATE_CHANGED and can time out even when target app already foreground. **Fix:** Build a bounded foreground-window verification handshake with stable package/window ID and explicit result. Never bypass password/locked-window checks; prefer no action over false success.

### 3. High: overlay accessibility and policy restrictions
**Risk:** Overlay may obstruct apps or be killed by OEM, and it is not a guaranteed background execution engine. **Fix:** Keep opt-in/off controls, non-focusable small surface, remove on service teardown and Jiva off. Test Android 11-16 and vendor builds. Provide user-visible notification/status when actual background worker is active; don't silently restart microphone from background.

### 4. Critical: continuous voice not implemented
**Risk:** `GeminiLiveClient` is one-turn text-to-speech; saying it is "human-like always-on" would mislead. **Fix:** Dart session state machine -> native AudioRecord foreground capture, PCM chunks, Live realtime input, playback jitter buffer, VAD/interruptions, mic privacy controls, max session duration, reconnect with fresh session handshake. No audio capture unless visible consent and OS-permitted foreground microphone service.

```dart
// Proposed interface, requires native implementation + model compatibility tests.
abstract interface class RealtimeAudioSession {
  Future<void> startAfterUserGesture();
  Future<void> sendPcm16(Uint8List frames);
  Future<void> interruptOutput();
  Future<void> stopAndRelease();
}
```

### 5. High: screenshot data and prompt injection
**Risk:** Screenshots may contain credentials and untrusted text that instructs the AI to act. **Fix:** Prefer local redact/allowlist; user approval per upload; model output is untrusted observation, never an executable instruction. Before any action revalidate focused window, package, controls, and approval.

### 6. High: on-device AI unavailable
**Risk:** Offline planner supports only simple hard-coded commands; marketing it as local intelligence is incorrect. **Fix:** select redistribution-compatible LLM/inference runtime, download weights with checksum and storage quotas, benchmark on actual phone, isolate inference, enforce thermal/memory ceilings, revoke model on integrity failure. Confirm license for all external assets.

### 7. High: workflows interrupted by Android lifecycle
**Risk:** Checkpoint protects against replay, but Android force-stop and background limits mean no guaranteed task continuation. **Fix:** use WorkManager for deferrable eligible tasks, explicit foreground service for user-visible ongoing permitted work, encrypt operation journal, use idempotency keys for remote actions. After force-stop rely on user reopening; never circumvent platform controls.

### 8. Medium: floating control Stop button reliability
**Risk:** Stop cancels queued native request, but Flutter callback may be detached. **Fix in this candidate:** report with original request type; existing Dart can treat it as failure. **Further fix:** persist result ID/status and explicitly reconcile on reopen, with stale-result rejection above.

### 9. High: unverified integration and dependencies
**Risk:** No APK, unit tests or physical phone run in current environment; existing plugins may have Android/Gradle incompatibilities. **Fix:** `flutter pub get`, `dart format --output=none --set-exit-if-changed`, `flutter analyze`, `flutter test`, `flutter build apk --release`; Android instrumentation tests for Accessibility and process recovery; cold-start, 10-day offline, app-switch, mic interruption, force-stop/manual-reopen. Verify all on authoritative Flutter toolchain and test device before using production data.

**Rule:** No claim that all gaps are closed until each feature passes its actual runtime acceptance tests. Never upgrade this candidate to an authoritative base solely due to successful ZIP packaging.
