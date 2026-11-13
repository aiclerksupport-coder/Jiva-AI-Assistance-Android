# Jiva AI — Batch B Voice, Wake Word & Microphone Resilience

**Base:** `a8a5657cd6e7388dd324e42367468f3c2f43ab1d` (PR #7 merged main)  
**PR:** [#8](https://github.com/aiclerksupport-coder/Jiva-AI-Assistance-Android/pull/8)  
**Scope:** Native Kotlin wake service, Kotlin speech capture, Flutter wake-word voice handoff, Flutter native speech plugin, testable wake policy.

## Batch B mein implemented changes

| Area | Production code patch | Protection |
|---|---|---|
| Kotlin Picovoice service | `WakeHandoffGate` atomic claim, re-arm only after successful start, disarm on pause/stop | One trigger per listening cycle, no duplicate capture handoff from callback storms |
| Kotlin Compose voice | Begin-capture checks enabled, busy, listening and waiting-for-pause state | Rapid double-tap, overlapping user/wake captures reject |
| Kotlin foreground service | Preserve enabled preference on system teardown, explicit user Stop still persists disabled | OS service teardown isn't mistaken for user opting out |
| Kotlin recognition | 60-second watchdog, cancel on completion/error/Stop/destroy, release recognizer | Avoid hanging mic and associated resources indefinitely |
| Flutter wake | Synchronous busy claim before await, generation-based Stop/disable cancellation, pure `WakeEventPolicy` | Stale greeting does not reopen microphone after Stop; duplicate wake rejection |
| Flutter mic pause/resume | Update `_wakeManagerStarted` only after `stop()` success; suppress resume while pause/pending event | Avoid simultaneous wake microphone and speech-recognition microphone |
| Flutter enhanced recognition | Android plugin 60-second watchdog; cleanup recognizer after final/error | Prevent endless enhanced capture; release native resource |
| Flutter speech callback order | Send final result before idle status | Submission claims busy state before the wake listener considers re-arming |
| Android 14+ language hint | `RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION` in native and Flutter enhanced path | Optional multilingual detection; depends on installed recognition provider |
| Unit tests | `WakeHandoffGateTest`, `wake_event_policy_test.dart` | Reject concurrent detections, duplicates, pending/disabled/busy captures |

Important: native `WakeHandoffGate` and Dart `WakeEventPolicy` are **not** the same Android service implementation. Their rules align, but implementations remain separate. No app, model, API, backup, or permission removed.

## Physical device acceptance matrix — still mandatory

1. Install each Kotlin and Flutter debug APK separately on Android 13/14/15/16 supported devices.
2. Import two valid Picovoice `.ppn` files and your own Picovoice AccessKey; verify **Jiva** and **Giva** each wake once.
3. Trigger word repeatedly (10+ times) during the same wake->greeting->listening cycle; ensure only one recognition session starts.
4. Start listening, immediately press Stop, then press Speak; confirm microphone release and no delayed resume; repeat 100 cycles.
5. Disconnect internet during recognition and Gemini speech; ensure user sees error/fallback and no app freeze.
6. Test Android Hindi (`hi-IN`), Gujarati (`gu-IN`), Marathi (`mr-IN`) and English (`en-IN`) recognition; the system/OEM may ignore language-detection hints and need manually selected recognizer language/model downloads.
7. Send app to background and lock phone; test notification, Android background microphone restrictions, restart and power-saving modes. Do **not** claim guaranteed hands-free capture with app killed or OEM restrictions.
8. Revoke mic/notification permission while Jiva runs; verify no loops, black-screen, or false 'still listening'.
9. Test voice interruption while TTS/Live audio is playing, while Gemini is slow, after 60-second timeout and immediately after disabling Jiva.
10. Validate privacy: no recorded audio stored; no API keys or private transcripts in logs.

## VULNERABILITY, FLAW & REAL-WORLD FIX REPORT

### B-01: Duplicate wake-word callbacks — fixed in source
**Risk:** Picovoice can fire multiple callbacks before async microphone stop, resulting in duplicate UI greeting/listen.
**Fix:** Atomic compare-and-set gate for Kotlin; Dart `WakeEventPolicy` and pending/busy check before any await.
```kotlin
if (!wakeHandoff.claim()) return
// stop microphone, send one wake event
// re-arm gate only after successful manager.start()
```

### B-02: Stop button while voice greeting pending — fixed in Flutter
**Risk:** User taps Stop but an awaited greeting completes and opens mic again.
**Fix:** Each accepted wake increments `_wakeGeneration`; Stop/disable invalidates it; check generation after each async await.
```dart
final epoch = ++_wakeGeneration;
await _pauseWakeWordManager();
if (!mounted || !_enabled || epoch != _wakeGeneration) return;
await _speakReply('Ji, boliye');
if (!mounted || !_enabled || epoch != _wakeGeneration) return;
await _listen();
```

### B-03: Wake microphone pause flag changed before stop succeeded — fixed in Flutter
**Risk:** Failed microphone stop appears as stopped, allowing double microphone capture.
**Fix:** Only set `_wakeManagerStarted=false` in successful `stop().then(...)`; keep pending pause future until completion, block resume until settled.

### B-04: Recognizer hang / native resource leak — fixed in source
**Risk:** Some OEM SpeechRecognizer implementations never return final/error callback; AudioRecord/pipe/recognizer may remain alive.
**Fix:** 60s watchdog, cancellation at final/error/stop/destroy and release on timeout. Test with OEM devices.
```kotlin
timeoutHandler.postDelayed(task, 60_000L)
// task: cancel/destroy recognizer, release audio pipe and report timeout
```

### B-05: Multilingual recognition variability — partially mitigated
**Risk:** Hindi/Gujarati/Marathi/English detection accuracy depends on Android recognizer, language models and OEM.
**Fix:** Android 14+ enable optional language detection. For definitive functionality, expose available recognizer locales, allow manual language override and test offline language packs. Do not describe detection as guaranteed.
```kotlin
if (Build.VERSION.SDK_INT >= 34) {
    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
}
```

### B-06: Wake in background / OS service restrictions — release blocker
**Risk:** A foreground notification does not bypass Android's background microphone, Doze, battery or process-death restrictions; an Activity receiver may not exist after full process termination.
**Fix next:** Use Android-permitted foreground service start initiated while app is foreground, persisted wake consent, explicit notification-to-open path, lifecycle monitoring, OEM battery prompts (user-controlled) and physical devices. Do not claim always-on recognition after force-stop or app kill.

### B-07: Direct Gemini key/Live WebSocket credential — release blocker carried forward
**Risk:** Existing BYOK still places a long-lived key in client runtime; voice/Live path still needs deployed authenticated backend and ephemeral token migration.
**Fix next:** Deploy existing Firebase Auth + App Check backend, Secret Manager key, real client wiring and production policy tests. This batch preserves current behavior and does not falsely claim to finish Batch C.

### B-08: Service start/pause/resume concurrency — residual risk
**Risk:** Android may schedule rapid multiple service commands before earlier coroutine finishes; mutex serializes manager calls but device delivery-order stress tests remain necessary.
**Fix next:** Add a single-intent command actor with sequence numbers, explicit pause ACK timeout and lifecycle instrumentation if device tests reproduce stale resume. Do not remove existing working handoff without tests.

## CI acceptance and original-base safety

- Compare all changes to `a8a5657cd6e7388dd324e42367468f3c2f43ab1d` by exact Git diff.
- Run native JVM unit tests + Kotlin debug APK; Flutter analyze, tests and APK; backend syntax check.
- No change to existing Firebase, Gemini model IDs, user secrets, legal identity, app package IDs or core phone actions.
- CI passing verifies build/tests only. Final Play release requires real-device tests, backend hardening, signing and policy review.
