# JIVA — FIVE REPOSITORY BACKGROUND & CAPABILITY AUDIT (17 Nov 2026)

## Scope and evidence
Original: `Jiva-AI-Assistance-Android-main.zip`; incoming candidate: `Jiva-AI-FlutterFirst-ReviewedScreenshot-UNVERIFIED-CANDIDATE.zip`; external reference ZIPs: `OpenPhoneUse-main.zip`, `tapilot-main.zip`, `sannabotapp-main.zip`, `deft-main.zip`, `closepaw-main.zip`. These are source inspections, **not live-device demonstrations**. No direct third-party source was copied. Jiva remains Flutter/Dart first; Kotlin remains an Android native bridge.

## Comparison — how background operation actually works

1. **OpenPhoneUse:** `app/src/main/AndroidManifest.xml` declares an AccessibilityService, VoiceInteractionService, VoiceInteractionSessionService, notification listener and boot receiver. `assist/PhoneUseVoiceService.kt` subclasses `VoiceInteractionService`; `service/PhoneUseAccessibilityService.kt` implements service lifecycle and accessibility events. Idea: native voice integration + event-driven UI access, but voice role must be explicitly granted by Android; boot receivers do not guarantee unconditional background microphone access.
2. **Tapilot:** `app/src/main/AndroidManifest.xml` declares `VoiceRuntimeService` with `microphone|mediaPlayback`, `TaskRuntimeService` with `dataSync`, overlay permission, accessibility service, and other optional services. `voice/VoiceRuntimeService.kt` starts foreground with a notification and handles STOP. `TaskRuntimeService.kt` maintains a foreground runtime. Idea: split voice runtime and task runtime, each with lifecycle and cancellation, instead of one always-on unrestricted daemon.
3. **Sanna:** React Native source, not Dart. Manifest declares wake-word microphone foreground service, notification listener, AccessibilityService and separate `HeadlessJsTaskService` handlers for scheduler, notifications and accessibility. `native/AccessibilityHeadlessService.kt` starts a notification foreground service. Idea: event-to-queued-task dispatch while UI is detached. **License not established:** root license file absent; do not copy its source or assets without verified rights.
4. **Deft:** `plugins/android/DeftAgentService.kt` uses notification-based foreground service (including a `specialUse` type on newer Android). `src/agent/foregroundService.ts` describes the runtime. Idea: optional offline model and explicit running state, but a working offline model, device RAM benchmark and packaging are NOT present in Jiva.
5. **ClosePaw:** `app/src/main/AndroidManifest.xml` declares `SYSTEM_ALERT_WINDOW`, but principal `app/AgentService.kt` is an AccessibilityService with `onServiceConnected`, `onAccessibilityEvent` and `onDestroy`. Idea: accessibility-driven, user-visible floating agent plus clear shutdown. It cannot circumvent Android accessibility or background start policies.

## Jiva's observed gaps (code confirmed)
- Jiva already declares `flutter_foreground_task` microphone service in `flutter/android/app/src/main/AndroidManifest.xml` and runs Porcupine in `flutter/lib/main.dart`. **It does not have an equivalent separate persistent task foreground service, Android VoiceInteractionService, notification-listener workflow, or system-overlay service in the Flutter host.**
- The microphone service currently starts from `_startBackgroundWakeWord` in `main.dart`. Android 14+ while-in-use permission rules prohibit a new microphone FGS started from an ordinary background state. Starting while an Activity is visible and sustaining the notification is the intended compliant pattern, but it can still be terminated by the OS/device battery policy.
- Jiva's AccessibilityService processes pending requests from `TYPE_WINDOW_STATE_CHANGED` events only; same-screen actions after state changes may time out. It is request-based, not a persistent full background autonomous agent.
- Screenshot capture in the previous candidate was local preview only. This batch adds **per-image consented Gemini explanation**, not automated screen control, and does not keep screenshots persistently.
- Gemini Live in `flutter/lib/services/gemini_live_client.dart` sends one text turn and collects audio output, not continuous 16kHz microphone PCM with barge-in or session-resumption.
- `flutter/lib/domain/offline_phone_planner.dart` is deterministic; there is no downloaded on-device LLM with model-license/size compatibility verification.
- The workflow's encrypted checkpoint and no-auto-replay policy are valuable, but multi-app autonomous execution, background task scheduler and cross-app retry verification are not complete.

## Android restrictions that must be respected
- Android 12+: background foreground-service starts are restricted.
- Android 14+: new microphone FGS needs `RECORD_AUDIO` while-in-use availability, with narrow documented exemptions. `BOOT_COMPLETED` cannot freely launch a microphone FGS.
- Screenshot and accessibility must be user-approved; secure windows and device-specific UI expose limitations.
- Do not request `specialUse`, Accessibility or overlay permissions solely as a workaround to Android restrictions. Clearly tell the user why any permission is needed.

Official references: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start ; https://developer.android.com/about/versions/14/changes/fgs-types-required ; https://developer.android.com/reference/android/accessibilityservice/AccessibilityService ; https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket

## Implemented in THIS batch
- `flutter/lib/services/gemini_repository.dart`: bounded 600 KB JPEG multimodal Gemini request using API key only in request header; user-selected question; explicitly no action execution; 30s network timeout; bounded answer text.
- `flutter/lib/main.dart`: per-screenshot consent dialog explicitly naming external sharing, in-memory result, busy state and delete action; no silent cloud upload.
- `flutter/test/gemini_screen_vision_test.dart`: mock-request checks that image is sent only by explicit method call, API key is never passed in URL, size/empty-key guards fail before network. **Not executed:** Dart/Flutter SDK absent.
- No native background service, actual continuous audio streaming, LLM weights, overlay, or autonomous multi-app engine was deceptively described as implemented.

## VULNERABILITY, FLAW & REAL-WORLD FIX REPORT

### P1. Background microphone restart
**Risk:** Background se microphone FGS start karne par Android 14+ SecurityException ya foreground-service-start exception aa sakta hai.
**Fix:** 1) User foreground UI par permission de. 2) Visible Activity se microphone FGS start karo. 3) Notification mein Stop action do. 4) App resume hone par service status recheck karo. 5) Boot par microphone automatically start mat karo. Android native policy example:
```kotlin
if (!isActivityVisible) {
    // Prompt user via notification to reopen Jiva before starting microphone FGS.
    return
}
// Only start after RECORD_AUDIO permission, notification prerequisites
// and current OS foreground-service requirements have been checked.
```

### P1. Sensitive image exposure
**Risk:** Screenshot mein passwords, contacts, bank balances ya personal messages ho sakte hain.
**Fix:** 1) One-off capture permission. 2) Explicit external AI sharing dialog. 3) Local-only by default. 4) Any private data containing screen par user ko share karne se mana karo. 5) Server-side logging/misuse controls aur per-app allowlist add karo. 6) AI ke response ko command na samjho.
```dart
if (!userConfirmedExternalImageShare || jpeg.length > 600000) return;
final explanation = await gemini.explainReviewedScreen(key, jpeg, question);
// Never dispatch a native phone action based solely on this text.
```

### P1. Unverified autonomous tap/typing
**Risk:** AI galat app ya stale screen par action le sakta hai; same action duplicate ho sakta hai.
**Fix:** 1) Request ID, target package, screen hash and expiry track karo. 2) Action ke turant pehle target window confirm karo. 3) Action ke baad naya state re-read karo. 4) Sensitive click, submit, transfer, delete hamesha explicit approve. 5) Crash par uncertain step ko automatically retry mat karo.
```dart
if (session.uncertainAfterRestart || session.awaitingResult) return;
// Verify target app + user approval before issuing exactly one native action.
```

### P1. Full-duplex voice absent
**Risk:** 'Natural human-like continuous conversation' ka UX current text-to-audio flow mein nahi milega.
**Fix:** 1) 16kHz mono PCM audio capture with a microphone foreground service started compliantly. 2) Send bounded realtime audio chunks through Gemini Live. 3) Playback 24kHz output with jitter buffer and echo control. 4) Handle `interrupted`, barge-in, session resume, reconnect and stop. 5) Device + network tests. Streaming payload example (API and model dependent):
```dart
final chunk = <String, Object>{
  'realtimeInput': {
    'audio': {
      'mimeType': 'audio/pcm;rate=16000',
      'data': base64Encode(pcm16MonoChunk),
    }
  },
};
// Requires a connected Gemini Live session and real microphone PCM capture.
```

### P2. Offline LLM absent
**Risk:** 'Offline AI' bolkar deterministic command matching ko full reasoning samajhna misleading hoga.
**Fix:** 1) Licensed, quantized on-device model select karo. 2) RAM, CPU, storage capability detection. 3) Hash/size verification of model weights. 4) Background native inference runtime with cancellation, thermal/memory limits. 5) Dart FFI or MethodChannel interface. 6) Offline accuracy and hallucination tests. Until then describe as 'offline basic commands'.

### P2. System overlay absent
**Risk:** In-app floating button doosri apps ke upar visible nahi rahega.
**Fix:** 1) Distinct user-opt-in `SYSTEM_ALERT_WINDOW` permission and service. 2) `WindowManager` overlay with minimal non-sensitive controls, no tapjacking. 3) Stop control and immediate teardown. 4) Android 12+ overlay obscuring limits and app-specific restrictions. 5) Real-device OEM tests.

### P2. Missing build and device proof
**Risk:** Compile errors, plugin API mismatch, battery optimizations aur background kill behavior source inspection se prove nahi hote.
**Fix:** `flutter pub get`, `flutter analyze`, `flutter test`, `flutter build apk --release`; install on Android 11/14/15+; test screen off, force-stop, background switch, 1h wake service, voice interruption, denied permissions, locked screen and slow network; preserve full logs and APK SHA256.

## Release verdict
**UNVERIFIED SOURCE CANDIDATE**. Do not replace the working Jiva APK until build/tests and physical-device verification pass. External sources are inspiration only. Every release must byte-compare against the original ZIP and last candidate.
