# Jiva AI: offline model, OS scheduler, multi-app policy — 17 Nov 2026

## Implemented in this candidate
- Added `llamadart: 0.19.11` for a real in-process, on-device GGUF inference engine. User manually imports GGUF; SHA-256 checked after copying into app-private storage and rechecked before loading. No remote model/AI service required *after* runtime dependency/model installed. UI has Import / Load / Ask Offline AI buttons.
- Local model may draft a conservative phone-step JSON plan; **all** plans pass original `PhoneWorkflow.fromJson`, max 8 steps, operation-ID correlation, checkpoint, and per-step confirmation. Do not construe this as unattended cross-app control. Model cannot directly call Android APIs.
- Added Android WorkManager periodic maintenance job (24h, inexact, persisted) that only cleans a stale abandoned app-private `staged.gguf` and marks a last-run timestamp. Schedule/stop controls via Flutter MethodChannel.
- Preserved existing wake-word, biometric key protection and Flutter-first code; no original files deleted.

## Critical limitations — not release ready
1. **Model provenance & storage**: source GGUF license, expected independent publisher SHA and model behavior require owner review. Copy SHA proves local transfer integrity, not that the publisher is trustworthy. Imported weights are stored in private app storage, not encrypted. Recommend signed model manifest and independent SHA pin. Keep under 2.2 GB, choose RAM-compatible quantization; inference speed and OOM still require physical testing.
2. **UI autonomy**: Android Accessibility may be disabled/revoked; Android secure/private screens remain inaccessible. Operations cannot be safely guaranteed when another app changes the display. UI actions must remain attended, visible, approval-gated, and should never be automatically replayed on restart. The new WorkManager job deliberately **never** executes Accessibility, microphones, or messages.
3. **Background restrictions**: WorkManager is OS-scheduled and inexact; task swipes, force-stop, doze, battery rules and device OEM restrictions apply. No guarantee of arbitrary background execution. No implicit launch of microphone foreground service on Android 14+.
4. **Model runtime**: `flutter pub get` and native runtime downloads require internet during initial build; `llamadart` package and model weights not bundled. Offline AI is useful only after a GGUF is imported and loaded on device. App needs model install progress, storage estimates, and native load/stress testing.
5. **Security**: Human approval for every phone action is retained. This batch is not fully autonomous multi-app operation. Double-check that every native response carries operationId and correct target package/window before execution.
6. **Release blockers**: No Flutter/Dart/Android SDK or physical device in this environment; `flutter analyze`, `flutter test`, `flutter build apk --release`, and ADB scenarios NOT executed. Existing debug signing also must be replaced for production.

## Windows validation
```powershell
cd flutter
flutter pub get
flutter analyze
flutter test
flutter build apk --release --verbose *> ..\\jiva-release-build.log
adb devices
adb install -r build\\app\\outputs\\flutter-apk\\app-release.apk
```
Do not deploy to primary phone until compile/build passes and a spare phone verifies: model import, offline mode (airplane), 5+ repeated load/unload cycles, low-memory behavior, screen pause/resume, approval checks, operation IDs, background scheduler under doze, force-stop behavior, and rollback.

## VULNERABILITY, FLAW & REAL-WORLD FIX REPORT

### 1. Untrusted local model — HIGH
Risk: malicious/incompatible model causes misleading plans or crashes. Fix: publish a signed model allowlist, expected SHA-256 and license per model; validate device RAM/storage before import; refuse missing pin in managed builds. Add an isolated model file import flow with trusted model ID and audit of model provenance.
```dart
if (calculatedSha256 != trustedManifest.expectedSha256) {
  throw StateError('Untrusted model');
}
```
### 2. Out-of-memory on weaker devices — HIGH
Risk: huge model blocks app or kills OS process. Fix: cap runtime threads/context, inspect `ActivityManager.MemoryInfo`, only load models within device budget, unload on low-memory callbacks; test realistic 1B-3B quantizations. Do not run inference in background.
```dart
if (!deviceBudget.canFit(modelBytes)) throw StateError('Model too large');
```
### 3. Sensitive UI action — CRITICAL
Risk: AI could click wrong app button, send or delete content. Fix: require visible app/package allowlist, confirmed text labels, operation ID, separate approval for irreversible actions, final-result check. Never auto-retry after uncertain execution.
```dart
if (!approved || result.operationId != session.operationId) return;
```
### 4. Background job reliability — HIGH
Risk: job runs late, multiple times, or not at all under doze/force-stop. Fix: unique periodic work, idempotent restricted worker, durable status, and no clocks as correctness proof. User-visible sync when app returns to foreground.
```kotlin
WorkManager.getInstance(context).enqueueUniquePeriodicWork(name,
  ExistingPeriodicWorkPolicy.KEEP, request)
```
### 5. Native + Flutter build validation — CRITICAL
Risk: dependency API mismatch/native hook failures may prevent APK building. Fix: run pub get/analyze/tests/release on Flutter 3.44.9+Dart 3.12.2, physically test Android 12-16; no production assertion without results.
