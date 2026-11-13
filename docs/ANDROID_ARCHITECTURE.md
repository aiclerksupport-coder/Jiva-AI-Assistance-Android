# Jiva Android — Architecture and Production MVP Plan

This document describes the intended production-ready structure for Jiva AI Assistance. The current repository is an experimental prototype; it should be refactored into these layers and built/tested before it is represented as production-ready. Android permissions and provider capabilities vary by OS version, device, and installed apps.

## Product boundary

Jiva is a user-controlled assistant, not a phone takeover. It can understand requests, prepare actions, and invoke supported Android APIs/intents. Calls, messages, WhatsApp handoffs, protected settings, and other consequential actions must be shown to the user for review/confirmation. No model response is executable code. Jiva must not bypass Android restrictions with Accessibility or broad permissions.

## Architecture

Use a single-activity Jetpack Compose app, MVVM, and a small set of feature modules/packages. Keep Android framework APIs behind repositories/adapters so command logic can be tested without a device.

```text
app/src/main/java/com/jiva/assistance/
  JivaApplication.kt
  MainActivity.kt                    # Compose host, permission/result registration
  navigation/
    JivaNavHost.kt
    Routes.kt
  core/
    model/                            # ConversationTurn, IntentPlan, ActionResult, Reminder
    result/                           # typed success/failure outcomes
    privacy/                          # consent state, redaction, retention policy
    platform/                         # Android version/device info provider
  data/
    preferences/                      # DataStore settings and consent
    secure/                           # Keystore-backed API-key storage
    gemini/                           # GeminiApi, request/response DTOs, parser
    reminders/                        # ReminderDao/repository, scheduler, receiver
    apps/                             # launcher query and app launcher
    contacts/                         # Android contact picker adapter
  domain/
    assistant/                        # AssistantRepository, ProcessCommandUseCase
    parser/                            # LocalCommandParser, IntentPlanValidator
    actions/                          # ActionDispatcher, action handlers
    reminders/                        # ScheduleReminderUseCase
  feature/
    assistant/                        # AssistantScreen, AssistantViewModel, UI state
    settings/                         # SettingsScreen, GeminiKeyScreen
    reminders/                        # Reminder list/editor
    apps/                             # app chooser/search
    consent/                          # permissions and action confirmation UI
  platform/
    speech/                            # SpeechRecognizer adapter, language metadata
    tts/                               # TextToSpeech adapter and voice selection
    contacts/                          # ACTION_PICK launcher and contact lookup
    intents/                           # dialer, SMS, WhatsApp share, Settings panels
    notifications/                     # notification channel and reminder receiver
```

Recommended dependencies: AndroidX Activity/Compose, Material 3, Lifecycle ViewModel/Compose, Kotlin coroutines, Preferences DataStore, AndroidX Core, and optionally Room if users need a durable reminder/conversation database. Avoid adding a dependency until the feature needs it.

### Command/action flow

1. Capture input by typed text or a user-started speech session. Show the transcript for correction.
2. Normalize text and run deterministic local phrase/slot parsing first.
3. If uncertain and the user configured Gemini, send the minimum necessary text plus a short, bounded conversation context. Explain network/provider use in onboarding.
4. Parse model output into a strict `IntentPlan` enum/DTO, validate every field locally, and reject unknown action names or malformed slots.
5. For `CHAT`, return the answer in the user's language. For device actions, show a preview/confirmation screen.
6. Only after explicit user approval, invoke an Android-supported intent/API. Report the result honestly; do not claim success merely because an intent was launched.
7. Keep action audit history local and user-visible, with delete controls. Do not store raw audio by default.

Example domain DTO:

```kotlin
enum class ActionType { CHAT, OPEN_APP, CALL, SMS, WHATSAPP_SHARE, REMINDER, OPEN_SETTINGS }

data class IntentPlan(
    val action: ActionType,
    val reply: String,
    val appName: String? = null,
    val recipientQuery: String? = null,
    val message: String? = null,
    val reminderText: String? = null,
    val reminderAtEpochMillis: Long? = null,
    val settingsTarget: String? = null,
    val needsClarification: Boolean = false,
)
```

## Multilingual voice and text

- Speech recognition is supplied by the device/provider. Request language detection only when supported, inspect any returned language metadata, and surface recognition/provider failures. A recognizer can ignore language-detection options; never promise every language works on every handset.
- Keep the original transcript and use Unicode-aware normalization. Do not infer language only from script: multiple languages use Latin script, and Hindi may be spoken/transcribed in either Devanagari or Latin transliteration.
- Local command parsing should use per-locale phrase/slot dictionaries and a confidence/ambiguity result, not a long list of `contains()` checks. Cover a small tested set first (Hindi, English, and selected locales); fall through to Gemini only with configured consent.
- Use detected/provider language metadata for TTS if available, verify `TextToSpeech.isLanguageAvailable`, and fall back transparently to the system voice. TTS does not automatically translate a reply; the reply must already be in the requested language.
- Support user interruption by stopping current TTS and opening a new foreground listening turn. Do not keep a microphone hotword/background service in the MVP.
- Test native scripts, transliteration, mixed-language speech, accents, numbers/times, noise, no network, unsupported language, and recognition service errors.

## Gemini API integration

The API key is supplied by the user; never hard-code a developer key. The current generic REST shape for a text request is:

```http
POST https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent?key={API_KEY}
Content-Type: application/json
```

```json
{
  "systemInstruction": {
    "parts": [{"text": "You are Jiva. Reply in the user's language. Return a JSON action plan only; never claim an action ran."}]
  },
  "contents": [{
    "role": "user",
    "parts": [{"text": "Remind me to call Mom at 7 PM"}]
  }],
  "generationConfig": {
    "responseMimeType": "application/json",
    "responseSchema": {
      "type": "OBJECT",
      "properties": {
        "reply": {"type": "STRING"},
        "action": {"type": "STRING", "enum": ["CHAT", "OPEN_APP", "CALL", "SMS", "WHATSAPP_SHARE", "REMINDER", "OPEN_SETTINGS"]},
        "recipientQuery": {"type": "STRING"},
        "message": {"type": "STRING"},
        "reminderText": {"type": "STRING"},
        "reminderAt": {"type": "STRING"}
      },
      "required": ["reply", "action"]
    }
  }
}
```

Use a model name currently enabled for the user's project; model availability changes. Handle HTTP errors, quota/rate limiting, timeouts, malformed JSON, cancellation, and network loss. Treat all generated fields as untrusted. Do not let the model choose arbitrary component names, package names, shell commands, URLs, or permissions. Allowlist actions and resolve contacts/apps locally. Avoid sending contacts, device identifiers, or unrelated conversation history to Gemini. Provide a clear setting to remove the key and clear session context.

**Key security:** Android Keystore encryption protects the saved key at rest, but a client app must decrypt it to make requests; it cannot protect the key from a compromised/rooted device or runtime instrumentation. For a production service, prefer an authenticated backend with quotas and server-side secret management. A user-provided key can be stored locally for a personal-use MVP, with a clear warning and no logging/analytics of the key. Use HTTPS only.

A one-shot `generateContent` request is not a full-duplex real-time voice conversation API. Streaming/Live experiences require a separately designed supported API, network/session lifecycle, interruption handling, provider/model validation, and explicit audio privacy disclosure. Do not claim human-level conversation or universal support.

## Permissions and manifest

Request only permissions tied to a visible feature, at runtime when needed:

- `android.permission.INTERNET` — Gemini/network requests.
- `android.permission.RECORD_AUDIO` — user-triggered microphone input.
- `android.permission.POST_NOTIFICATIONS` — Android 13+ reminder notifications; request at runtime.

For contacts, prefer the system contact picker (`ACTION_PICK` with `CommonDataKinds.Phone.CONTENT_URI`) so Jiva does not need broad `READ_CONTACTS`. For launchable apps, query launcher activities and declare the appropriate `<queries>` intent; avoid `QUERY_ALL_PACKAGES` unless a genuinely qualifying use case is approved under Play policy. Declare the reminder receiver as non-exported. Set cleartext traffic off (the default) and keep backup behavior appropriate for secrets.

Do **not** request `CALL_PHONE` or `SEND_SMS` for this MVP: use `ACTION_DIAL` and `ACTION_SENDTO` so the person reviews and completes the call/SMS. Do not request exact-alarm privileges unless a separately justified product requirement and Play policy review supports them. Do not request Accessibility, notification-listener, device-admin, or broad storage permissions as a shortcut to control the phone.

Manifest outline (verify package names and API-level behavior in the actual app):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
        <intent><action android:name="android.speech.RecognitionService" /></intent>
    </queries>
    <application android:allowBackup="false" android:usesCleartextTraffic="false" ...>
        <activity android:name=".MainActivity" android:exported="true">...</activity>
        <receiver android:name=".platform.notifications.ReminderReceiver" android:exported="false" />
    </application>
</manifest>
```

The `...` is explanatory and must be replaced with actual app attributes/elements; this outline is not a drop-in manifest.

## Contact, call, and message confirmation

1. Jiva extracts an intended recipient and draft; it does not search contacts with broad permission.
2. Launch Android's phone contact picker. Read the selected URI only after the user chose it; handle cancellation, no phone number, and multiple numbers.
3. Show a review card with contact name, selected number, and action. For messages, show the full editable message and destination app.
4. Require an explicit button press.
5. Calls use `ACTION_DIAL` with `tel:`; user taps Call in the dialer.
6. SMS uses `ACTION_SENDTO` with `smsto:` and `sms_body`; user reviews/sends in their SMS app.
7. WhatsApp uses a text share chooser or WhatsApp package handoff when installed. It cannot safely choose a private chat or send automatically; user selects the chat and taps Send.
8. Log a state such as `HandedOffToDialer` or `ComposerOpened`, not `CallCompleted`/`MessageSent` unless a supported API can verify that outcome.

## Reminders and app launching

- Reminder entity: stable ID, text, trigger instant, zone/locale metadata as needed, created time, status.
- MVP: user selects text/date/time, then schedule an inexact `AlarmManager` alarm (`setAndAllowWhileIdle`) or `WorkManager` for deferrable work. Exact delivery is not guaranteed by Android/OEM battery policies. Ask notification permission and show a reminder preview.
- Persist reminders in Room for durable lists and reschedule after reboot only if the product adds the boot receiver/permission and tests it. Give the user edit/delete controls. Avoid alarm ID collisions; use stable unique IDs.
- App launcher: query `ACTION_MAIN` + `CATEGORY_LAUNCHER`, filter/search by label, and launch the selected package's launcher intent. Handle apps not installed or not launchable. Do not enumerate private app data.
- Device info can use documented build fields (manufacturer/model/API level) for a visible diagnostics screen. Settings actions should open public `Settings` screens/panels; Android generally requires the user to perform protected changes there.

## UI and screens

Material 3 dashboard: top Jiva identity/status, conversation transcript, voice button and interrupt, typed composer, quick actions, and recent actions/reminders. Separate screens/dialogs for permission rationale, Gemini key setup/removal, app chooser, reminder editor/list, contact/action review, and privacy/device diagnostics. Provide large touch targets, content descriptions, dynamic color support, dark theme, loading/error/empty states, and a language/voice preference.

## Production MVP milestones

1. **Foundation:** refactor current monolithic activity into packages above; establish Gradle build, lint, unit/instrumentation tests, and CI. Verify app launches on API 26+ devices.
2. **Local assistant:** typed and push-to-talk UI, speech/TTS adapters, tested Hindi/English parser with clear unsupported/ambiguous replies.
3. **Safe actions:** app launcher, contact picker, dialer/SMS/WhatsApp handoffs, settings panels, confirmation UX; test cancellation and unavailable apps.
4. **Reminders:** persistence, scheduling, notification permission, edit/delete, reboot behavior decision, and delivery tests under battery saver.
5. **Gemini:** user key setup/removal, secure-at-rest storage, schema-constrained action plan, privacy disclosure, request timeouts/error UI, local validation, no secrets in logs.
6. **Hardening:** supported-locale matrix, accessibility review, data safety/privacy policy, threat model, Play policy review, API quota guidance, crash/error telemetry without transcript/key collection, release signing and staged rollout.

## Minimum tests before calling it ready

- Parser unit tests for each supported phrase/locale, transliteration, ambiguous requests, and malformed model output.
- No action is dispatched from an unrecognized enum or without user confirmation.
- Contact picker cancellation/no-number cases do nothing; call/SMS intents are built correctly.
- Reminder past-time rejection, stable IDs, notification permission denied, process restart, and duplicate scheduling.
- Gemini timeout, 4xx/5xx, rate limit, invalid JSON, empty response, API-key removal, and no secret in logs.
- UI tests for talk/type/interrupt, confirmation cancel/approve, TalkBack labels, font scaling, and dark theme.

The existing prototype should be treated as a starting point only. Run Gradle build, lint, and tests after refactoring; no architecture document alone establishes that the current source compiles or that device-specific behavior is verified.
