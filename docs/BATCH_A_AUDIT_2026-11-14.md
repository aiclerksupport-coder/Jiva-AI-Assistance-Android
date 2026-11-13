# Jiva AI — Batch A static source audit and safety remediation

**Date:** 14 November 2026  
**Language:** Hinglish  
**Authoritative original base:** `f83b9f1985aa335b7d66f839502ccac45c967bf7`  
**Branch:** `audit/batch-a-source-safety-20261114`  
**Scope:** Existing Kotlin Android application, Flutter application, Flutter Kotlin plugin, Node gateway, build manifests, existing tests.

## Audit scope aur limits

Repository mein **51 Kotlin/Dart files** hain, jinmein production implementation, platform plugin, unit tests aur Android instrumentation tests shamil hain. Neeche har ek ki responsibility aur audit finding capture hai. Audit source/static level par kiya; actual phone, microphone, encrypted-storage restoration, Android OEM background policy, Firebase deployment aur Play compliance ko static CI se verified nahi maana jayega. CI ke successful compile/tests APK ki real-device functionality guarantee nahi karte.

Jiva **personal Android assistant** hai, Enterprise ERP nahi. Is codebase mein SQL inventory/journal, owner/staff sync, ten-day offline reconciliation ya ERP backup architecture nahi hai. In features ko artificially add nahi karna chahiye.

## Kotlin Android — file-by-file

| File (relative to `app/src/`) | Role, findings |
|---|---|
| `main/java/com/jiva/assistance/MainActivity.kt` | Compose UI, microphone, permissions, handoffs, model picker. 10 MB model copy UI thread par hoti thi; **fixed** using IO dispatcher + picker guard. Heavy central activity still needs phone smoke testing and eventual decomposition. |
| `main/java/com/jiva/assistance/AssistantViewModel.kt` | Command state, Gemini requests, idle timer. Single `busy` guard present; callback interleavings, cancellation and `showReply` setting `busy=false` need targeted concurrency instrumentation. |
| `main/java/com/jiva/assistance/ActionConfirmationDialog.kt` | Review-gated phone action proposals, not silent execution. Verify number/recipient editing and double-confirmation on device. |
| `main/java/com/jiva/assistance/FirstRunNotice.kt` | First-run user safety notice stored in preferences; legal notice is disclosure, not security enforcement. |
| `main/java/com/jiva/assistance/LegalNoticeDialog.kt` | Legal/privacy disclosures; release wording must match actual deployed functionality. |
| `main/java/com/jiva/assistance/ReminderScheduler.kt` | AlarmManager reminders, persistence, boot recovery. Risk: alarm and preference write are not atomic; OEM power/reboot/permission caveats. |
| `main/java/com/jiva/assistance/WakeWordService.kt` | Foreground microphone and Picovoice manager. Mutex useful; service coroutine cleanup, Android background launch restrictions and battery policies require runtime validation. |
| `main/java/com/jiva/assistance/accessibility/JivaAccessibilityService.kt` | One-shot screen actions, exact visible target matching, private-screen text and password heuristic. No claim of perfect secure-window detection. |
| `main/java/com/jiva/assistance/data/ApiKeyStore.kt` | Android Keystore AES-GCM at rest. Compromised device still exposes runtime BYOK credentials. |
| `main/java/com/jiva/assistance/data/GeminiRepository.kt` | Direct Google generateContent requests on Dispatchers.IO, retry and cancellation. Production key path not yet migrated to backend. |
| `main/java/com/jiva/assistance/data/GeminiLiveClient.kt` | WebSocket Live audio. Long-lived API key in WS URL; audio cancellation and memory behavior need phone testing. |
| `main/java/com/jiva/assistance/data/GeminiRetryPolicy.kt` | Retries transient exceptions, three attempts. Add deterministic timeout/quota tests for production. |
| `main/java/com/jiva/assistance/data/JivaGatewayClient.kt` | Auth/App Check HTTPS gateway transport **exists**, not yet used by active ViewModel. |
| `main/java/com/jiva/assistance/data/ProcessedAudioInput.kt` | Native AudioRecord/PFD pump with lifecycle close; verify AudioRecord shutdown on interrupted recognition and low-memory devices. |
| `main/java/com/jiva/assistance/data/WakeWordModelImporter.kt` | Previously deleted known-good `.ppn` before renaming candidate; **fixed** using Android AtomicFile rollback-safe write. |
| `main/java/com/jiva/assistance/domain/ActionPolicy.kt` | Local plan preconditions; must always be enforced at final dispatch boundary. |
| `main/java/com/jiva/assistance/domain/AssistantModels.kt` | UI state and allowed action models; no persistence or stock/accounting records. |
| `main/java/com/jiva/assistance/domain/LocalCommandParser.kt` | Multilingual app-command deterministic parser; earlier Romanized launch and Local/call false match patched in PR #6. |
| `main/java/com/jiva/assistance/domain/PhoneCapabilityPolicy.kt` | Explicit policy prevents silent calls/messages/protected operations; verify each real dispatch honors policy. |
| `main/java/com/jiva/assistance/domain/VisibleTargetMatcher.kt` | Exact multilingual matching, ambiguous target reject, private screen heuristic. Native version stronger than old Flutter plugin. |
| `main/java/com/jiva/assistance/domain/VoicePreset.kt` | Voice catalog/preset fallback; model compatibility remains server-dependent. |
| `main/java/com/jiva/assistance/platform/contacts/ContactPicker.kt` | OS-selected contact, no broad contacts permission; permission/handoff tests required. |
| `test/java/com/jiva/assistance/data/GeminiRetryPolicyTest.kt` | Pure retry policy coverage; does not verify live API. |
| `test/java/com/jiva/assistance/domain/LocalCommandParserTest.kt` | English/Indian-language app launch and action confirmation tests. |
| `test/java/com/jiva/assistance/domain/PhoneCapabilityPolicyTest.kt` | Silent action denial tests; secure. |
| `test/java/com/jiva/assistance/domain/VisibleTargetMatcherTest.kt` | Exact locale labels, denylist, high-impact review, ambiguous target handling. |
| `androidTest/java/com/jiva/assistance/AssistantScreenTest.kt` | On-device Compose UI smoke test; **not run in existing CI**. |
| `androidTest/java/com/jiva/assistance/FirstRunNoticeTest.kt` | On-device first-run notice test; **not run in existing CI**. |

## Flutter/Dart aur Kotlin plugin — file-by-file

| File (relative to `flutter/`) | Role, findings |
|---|---|
| `lib/main.dart` | ~2,080-line UI/controller handling voice, wake, notifications, navigation, dialogs. Existing synchronous `_busy` guards; future extraction into controllers recommended, avoid all-at-once rewrite. |
| `lib/domain/action_policy.dart` | Validation of handoff plan and phone action allowlist; keep final dispatch gate. |
| `lib/domain/assistant_models.dart` | Action, pending-plan and reminder model. |
| `lib/domain/local_command_parser.dart` | English/Hindi/simple command routing; **fixed** substring false phone/call match, regression tests added. Multilingual feature parity with native Kotlin still incomplete. |
| `lib/domain/voice_preset.dart` | 10 selectable voice presets and fallback. |
| `lib/services/api_key_store.dart` | FlutterSecureStorage BYOK and wake settings. Secrets still exposed while in use on a compromised phone. |
| `lib/services/gemini_live_client.dart` | WS audio/PCM-to-WAV; direct API key on query string, memory copies for large audio need profiling. |
| `lib/services/gemini_repository.dart` | HTTP Gemini interpretation with retry; client long-lived key until secure backend integration. |
| `lib/services/gemini_retry_policy.dart` | Transient failure retry, 3 attempts; cancellation race/device tests needed. |
| `lib/services/jiva_gateway_client.dart` | Secure transport candidate using Firebase ID and App Check tokens; not wired to active UI. |
| `lib/services/reminder_service.dart` | local notifications and persistence; timezone/reboot/OEM tests needed. |
| `lib/services/wake_word_service.dart` | Foreground task lifecycle and stop button. A visible foreground notification does not guarantee unrestricted background microphone capture. |
| `packages/jiva_accessibility/lib/jiva_accessibility.dart` | MethodChannel one-shot screen actions, handler cleanup. |
| `packages/jiva_accessibility/lib/jiva_speech.dart` | Speech MethodChannel and callback cleanup. |
| `packages/jiva_accessibility/android/src/main/kotlin/com/jiva/accessibility/JivaAccessibilityPlugin.kt` | Native MethodChannel attachment/detachment. Verify Activity-aware lifecycle under rotation. |
| `packages/jiva_accessibility/android/src/main/kotlin/com/jiva/accessibility/JivaAccessibilityService.kt` | Old implementation checked only password fields and allowed disabled clickable ancestor targets; **fixed** conservative private-screen denylist and enabled/visible checks. Real-device security testing still necessary. |
| `packages/jiva_accessibility/android/src/main/kotlin/com/jiva/accessibility/JivaSpeechEngine.kt` | Native speech/AudioRecord/PFD pipeline; release paths need interruption and repeated-start tests. |
| `android/app/src/main/kotlin/com/jiva/assistance/flutter/MainActivity.kt` | Minimal FlutterActivity launcher; no duplicate business logic. |
| `test/gemini_repository_test.dart` | Mocked Gemini verification/retry; no real network. |
| `test/jiva_gateway_client_test.dart` | Mock auth headers and HTTPS rejects; no Firebase backend integration. |
| `test/local_command_parser_test.dart` | Parser/action tests, plus regression for Local/PhonePe false calls. |
| `test/voice_and_live_audio_test.dart` | Voice metadata and WAV header tests; no microphone, networking or playback tests. |
| `test/widget_test.dart` | Only tests a synthetic Scaffold text; **not** actual JivaApp widget, so misleading smoke coverage remains. |

## Build and configuration review

- Native Android build via Gradle 8.9 / JDK 17; Flutter pinned Flutter 3.44.9; Android SDK 35/36.
- Current workflow tests native JVM unit suite, Flutter static analysis/tests, two debug APK builds and gateway JS syntax. Instrumented tests, live Firebase and device tests **not covered**.
- Both Android manifests include mic and foreground microphone access. Flutter uses application-level explicit removal of `QUERY_ALL_PACKAGES` and launcher query visibility. Must verify merged manifest before Play release.
- `android:allowBackup=false` already set for native and Flutter apps; this avoids exporting secrets via normal Android backup but means reinstall/device transfer must be explicitly designed.
- `backend/server.mjs` verifies Firebase ID and App Check, has transactional Firestore request quota and short-lived Live token endpoint; backend not deployed or linked to runtime apps.
- `backend/package.json` has no frozen lockfile; build reproducibility/dependency audit needs `npm ci`/lockfile and integration tests.

## VULNERABILITY, FLAW & REAL-WORLD FIX REPORT

### A-01 — Flutter command parser false call (FIX IMPLEMENTED)
Risk: "Open My Local Bank App" ko phone call intent samajh sakta tha. Production mein wrong UI action proposal aa sakta tha.

Fix: plain substring `text.contains('call')` ki jagah Unicode-aware whole-word matching, regression tests. Affected: `flutter/lib/domain/local_command_parser.dart` and parser test. Example:
```dart
bool isPhoneVerb(String text) =>
    RegExp(r'(?<![\p{L}\p{N}])(?:call|phone)(?![\p{L}\p{N}])',
      unicode: true).hasMatch(text);
```
Remaining: multilingual app launch parity test across Kotlin and Dart.

### A-02 — Destructive wake model replacement (FIX IMPLEMENTED)
Risk: old model delete, new rename fail -> app has no valid wake model.

Fix: `android.util.AtomicFile`, `startWrite`, validate, `finishWrite` / `failWrite` on failure. Last valid file retained. Example:
```kotlin
val atomic = android.util.AtomicFile(target)
var out: java.io.FileOutputStream? = null
try {
    out = atomic.startWrite()
    source.copyTo(out)
    atomic.finishWrite(out)
} catch (error: Exception) {
    if (out != null) atomic.failWrite(out)
    throw error
}
```
Production code also enforces 10 MB max and nonempty input. Real force-kill/power-loss test pending.

### A-03 — Wake import main UI freeze / overlapping picks (FIX IMPLEMENTED)
Risk: copying up to 10 MB on Compose MainActivity event callback freezes interaction; parallel imports can overwrite slots.

Fix: `coroutineScope.launch { withContext(Dispatchers.IO) { WakeWordModelImporter.import(...) } }`, one in-flight picker guard, captured model name, reset on cancel and finally. User sees result only after successful file save.

### A-04 — Flutter Accessibility privacy parity gap (FIX IMPLEMENTED)
Risk: Flutter native plugin only blocked `node.isPassword`; login/bank/payment screens without password fields may be read or clicked.

Fix: conservative visible-label denylist and blocked event/read/click/scroll paths, disabled target rejection. Example:
```kotlin
if (containsPassword(root, 0) || looksPrivate(root, 0)) {
    throw IllegalStateException("Private screen: action blocked.")
}
```
Important: this is **heuristic**, not an OS-level certification. Further test with banking/password/custom UI screens and Unicode normalization.

### A-05 — Production Gemini BYOK still exposed (OPEN, CRITICAL)
Risk: Client APK accesses long-lived API key; a compromised device or logs/proxies can expose it; direct WS puts key in URL.

Exact next implementation: deploy Firebase Auth + App Check gateway and Google Secret Manager, generate short-lived Live credentials per session, replace native `GeminiRepository` and Flutter `GeminiRepository` live call sites with authenticated gateway, remove direct long-lived key transport in release, add expiry/refusal/retry tests. Do not turn on production keys until completed.
```dart
final response = await gateway.interpret(command);
final ephemeralToken = await gateway.createLiveToken();
// Use only ephemeralToken with Gemini Live on a trusted TLS connection.
```

### A-06 — Native reminder persistence consistency (OPEN, MEDIUM)
Risk: `AlarmManager.setAndAllowWhileIdle` and preference `apply()` are not one atomic transaction; process kill between operations may lose recovery metadata.

Fix: commit durable reminder record before scheduling, rollback on scheduling failure; on boot restore future entries with idempotent PendingIntent IDs, test no duplicates.
```kotlin
check(prefs.edit().putStringSet("items", updated).commit())
try {
    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
} catch (e: Exception) {
    prefs.edit().putStringSet("items", previous).commit()
    throw e
}
```
Separate notification permission and OEM power handling needed.

### A-07 — Android instrumentation gap (OPEN, HIGH)
Risk: green CI only compiles and unit tests. Wake capture, Accessibility privacy, background service, reboot and Android 13–16 behavior could regress silently.

Fix: add emulator/device CI lane for `:app:connectedDebugAndroidTest`, Flutter integration tests, Android 13/14/15/16 API matrix, manual physical-device smoke sheet. Never label "production verified" based solely on JVM tests.

### A-08 — Widget-test false coverage (OPEN, LOW)
Risk: Flutter `widget_test.dart` only renders a dummy `Scaffold`, not actual `JivaApp`.

Fix: dependency-injected testable AssistantScreen and Mock MethodChannels, then widget smoke test actual screen; avoid opening mic or network in tests.
```dart
await tester.pumpWidget(JivaApp(/* injected fake services */));
expect(find.text('Jiva'), findsOneWidget);
```

### A-09 — Audio and wake service lifecycle (OPEN, MEDIUM)
Risk: Android service cleanup and audio-buffer disposal under repeated stop/restart may race. Flutter Live PCM WAV buffer copies can create jank on large response.

Fix: structured cancellation, bound audio response buffers, run heavy WAV conversion on isolate when profiling supports it, stress 100 start/stop cycles and instrument memory.
```dart
final wave = await compute(convertPcmToWav, pcmBytes);
```
Snippet is an integration sketch; converter must be top-level and preserve current WAV behavior.

### A-10 — Backend dependency and release reproducibility (OPEN, MEDIUM)
Risk: `backend/package-lock.json` absent, CI only executes syntax check. Unpinned runtime dependency resolution can change shipped behavior.

Fix: create committed npm lockfile, `npm ci` with supported Node, unit/integration tests with mocked Firebase, dependency audit; configure Cloud Run authentication and quotas before deployment.
```bash
cd backend
npm install --package-lock-only
npm ci
npm test
```

### A-11 — Flutter/Kotlin parity gap (OPEN, MEDIUM)
Risk: native parser supports more Indic scripts and alias canonicalization than Flutter parser. User sees differing behavior depending on APK.

Fix: shared language/command fixtures and separate parity tests for app launch, actions, confirmations, and denial behavior; choose primary implementation only after the user explicitly decides. No production feature removed in Batch A.

### A-12 — Local backup / offline gap expectation (NOT APPLICABLE)
Jiva currently stores reminders/credentials/configuration, not ERP journals or multi-user SQL. Ten-day owner/staff inventory reconciliation, ERP role permissions, SQLCipher stock and cloud backup cannot be verified because Jiva has no such subsystem. Offline commands are locally parsed; Gemini requires network. Do not claim otherwise.

## Baseline comparison & release gates

1. Compare exact baseline commit `f83b9f1985aa335b7d66f839502ccac45c967bf7` with final PR head using GitHub Compare and `git diff --name-status`.
2. No change to app IDs, native/Flutter features, Gemini model selection, Firebase project, user credentials, pricing, or sensitive data.
3. CI must pass on **final PR head** (not an earlier commit) before merge.
4. Physical device tests, production Firebase deployment, live audio, sensitive-screen Accessibility and signed release build remain separate acceptance gates.
5. **Do not merge Batch A** if any unit/compile test is red. All open high-risk items need explicit mitigation and separate follow-up validation.
