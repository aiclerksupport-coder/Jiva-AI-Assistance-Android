# Jiva AI — Flutter-first phone agent, reviewed screenshot batch (2026-11-17)

## Scope
This candidate extends the previously supplied `Jiva-AI-FlutterFirst-RecoveryOffline-UNVERIFIED-CANDIDATE.zip` **without replacing any existing base file with an external repository**. Flutter/Dart owns UI, session state, policy and approvals. Kotlin only performs Android Accessibility API calls.

### Implemented in this batch
- Android 11+ AccessibilityService `takeScreenshot()` one-shot capture after **explicit user approval** and a target app switch within 30 seconds.
- Android accessibility metadata declares `canTakeScreenshot=true`; Android still refuses protected windows.
- Native checks for lock screen, private content, target package and current window consistency before accepting image bytes.
- Downscaled JPEG local preview (<=960 pixels wide, <=600 KB), never written to local database or sent to Gemini. No image is retained in native detached-activity text buffer.
- Dart MethodChannel `reviewedScreenshot` carries a bounded base64 image and source package to `Image.memory` local UI; user can clear the preview. Disabling Jiva clears the reference.
- Screenshot is not a fully autonomous visual agent; it does NOT automatically inspect the image, formulate click coordinates, or transmit any image to an AI endpoint.

### Inherited from earlier candidate, still runtime-unverified
- bounded 8-step phone workflow, local-only visible text reading, per-step approvals, single-flight native queue, checkpoint-based uncertain-action recovery, basic deterministic offline commands.

### Not implemented/verified (do not claim production complete)
1. A true computer-vision step verifier and visual grounding with robust protected-screen redaction.
2. Continuous two-way microphone/audio streaming, reliable interruptions, and live conversational task continuation.
3. Android system overlay with foreground lifecycle and policy-compliant permissions. Existing floating button is in-app only.
4. Fully autonomous cross-app agent with durable receipts, idempotent external actions and user-trust management.
5. Full on-device LLM inference: current offline commands are rule-based, not model inference.
6. Flutter analyze/test, Android Gradle build, emulator or physical device tests (not available in execution environment).

## VULNERABILITY, FLAW & REAL-WORLD FIX REPORT

### S1: Screenshot privacy — high impact if screen is sensitive
**Risk:** A screenshot might display PII or payment details even when accessibility text does not identify them. Android secure-window failure is not sufficient for all private apps.
**Immediate mitigation:** Per-capture confirmation; password/private-text checks; no upload; local memory only; limit image size; explicit deletion. **Residual risk remains.**
**Next-version implementation:** Implement per-app allowlist, user-configurable exclusions, a user confirmation of a static local thumbnail before ANY cloud image analysis; prohibit banking, payments, authenticator and password managers. Reject screenshot if package/active window changes.

```dart
if (!userApprovedCapture || isSensitivePackage(packageName)) {
  return; // Fail closed. Never upload the image.
}
final approvedUpload = await requestExplicitVisualUploadApproval();
if (approvedUpload != true) return;
```

### S2: Native screenshot result can arrive after navigation
**Risk:** Another app may become active while capture is pending, resulting in a mismatch.
**Mitigation:** Validate target package, window ID and private-screen status again in `onSuccess`. Discard changed screen.
**Next-version fix:** Add monotonic action/request IDs propagated across Dart/Android events, time-limited one-shot results and cancel-on-screen-off generation counters. Confirm the screenshot action completion with the matching request ID, not only the type string.

```dart
if (result.requestId != activeCaptureRequestId) return;
if (result.packageName != approvedPackageName) return;
```

### S3: Only basic offline automation exists
**Risk:** Users may expect broad natural-language AI without network, but rules cover only a small set of commands.
**Fix:** Add a licensed on-device model with explicit user opt-in, hardware capability probes, background isolate for preprocessing, model checksum and bounded memory/thermal budgets. Do not label existing deterministic parser "offline AI".

```dart
if (!await modelFiles.verifySha256() || !deviceSupportsModel) {
  return OfflineMode.basicCommands;
}
return OfflineMode.onDeviceModel;
```

### S4: Voice is not full-duplex
**Risk:** Current Gemini Live method sends a text turn and receives audio; microphone interruption and continuous sessions are absent.
**Fix:** Implement PCM microphone streaming session, barge-in cancellation, audio-focus state machine, teardown on OS microphone permission revocation, bounded reconnect with explicit user confirmation for resumed operations. Validate actual Gemini Live SDK/protocol with device tests.

```dart
if (userInterrupted) {
  await playback.stop();
  await liveSession.cancelCurrentOutput();
}
```

### S5: Multi-app actions can repeat after process death
**Risk:** Platform callbacks can be lost, and a successful click may appear unresolved after restart.
**Existing mitigation:** Original candidate checkpoints in-flight actions as uncertain and blocks auto replay.
**Fix:** Add durable action receipt IDs and independently verified postconditions; irreversible actions always require user confirmation and should never blindly retry.

```dart
if (checkpoint.inFlight && !postconditionVerified) {
  return WorkflowState.needsHumanReconciliation;
}
```

### S6: System-wide overlay not shipped
**Risk:** The in-app floating button cannot stay visible over other apps, and Android overlay requires additional permissions and lifecycle/privacy controls.
**Fix:** Build a separate opt-in overlay service, protect against screen lock/secure app exposure, use foreground notification where mandated, and verify Android/OEM battery policies. Never silently enable `SYSTEM_ALERT_WINDOW`.

### S7: Security policy heuristics can miss sensitive screens
**Risk:** Keyword checks cannot recognize every banking/payment/login view or WebView.
**Fix:** Use allowlisted packages and deny unknown actions by default, not only sensitive keywords. Maintain Android secure-window restrictions and separate confirmation for any destructive step.

### S8: No SDK/runtime validation
**Risk:** Compile-time API incompatibilities and OEM-specific Accessibility differences remain unknown.
**Fix:** Windows verification: `flutter pub get`, `dart format --output=none --set-exit-if-changed .`, `flutter analyze`, `flutter test`, `flutter build apk --release`; then Android 11, 13, 15 device and secure-window negative tests, external-app screenshot deny tests, lock/unlock and process-kill recovery tests.

## Source integrity
Detailed original-versus-candidate and previous-candidate-versus-candidate SHA-256 comparisons are packaged as JSON in `/docs/` alongside this note. File untouched = exact SHA-256 identity, not a claim of runtime behavior.
