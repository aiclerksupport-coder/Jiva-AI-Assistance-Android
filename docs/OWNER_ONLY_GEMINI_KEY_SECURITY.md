# Jiva AI — Owner-only Gemini key security (14 November 2026)

**Authoritative base:** `fa449d8fd61216dd9ab3a8c82d8df67c100844be` (merged Batch B)  
**New PR:** [#10](https://github.com/aiclerksupport-coder/Jiva-AI-Assistance-Android/pull/10)  
**Old PR #9:** CLOSED, unmerged, Firebase gateway unwanted.

## Owner-approved workflow

- Sirf owner apne phone par Gemini API key manually enter kar sakta hai.
- **Test & save** se pehle Android system ka fresh fingerprint ya actual phone screen-lock PIN/pattern/password verification zaroori hai.
- **Remove key** se pehle alag se fresh system verification zaroori hai.
- Wrong fingerprint, Cancel, timeout, device lock disabled, biometric lockout, ya prompt error mein key untouched rahegi.
- Jiva app **PIN/password ko read, collect, store ya transmit nahi karti**; OS authentication prompt result true/false deta hai.
- Har key mutation ke liye fresh authentication required hai. Successful authentication ka reusable long-lived session nahi banega.
- Native Kotlin key encrypted at rest with existing Android Keystore AES-GCM; Flutter key existing Secure Storage mein rakhi jaati hai.
- Existing own-key Gemini text+voice flow, Jiva/Giva wake word, reminders, local actions and assistant UX preserved.
- **No Firebase Auth, App Check, Cloud Run, email registration, external backend**.

## Important reality

Screen-lock authentication phone ke registered biometric/credential holders ko validate karta hai. Android OS app ko yeh nahi batata ki kaunsa specific human/finger use hua. Isliye phone par aur logon ke fingerprints registered hain ya PIN share hai to woh bhi authorize kar sakte hain. Android 8–10 pe AndroidX device compatibility ke liye weak biometric + device credential ka fallback possible hai; Android 11+ pe strong biometric + device credential.

Long-lived Gemini key owner ke phone par encrypted at rest hai, lekin **Gemini request ke waqt app key use karegi**. Rooted/compromised device, traffic interception, debug instrumentation ya unsafe logging se client key ka absolute protection guarantee nahi hota. Key ko Google AI Studio me quota/restrictions do aur leak hone par rotate karo.

## VULNERABILITY, FLAW & REAL-WORLD FIX REPORT

### C-OWNER-01 — Unauthorized key add (FIXED IN SOURCE)
**Risk:** Phone unlock milne par koi bhi Jiva Gemini key overwrite kar sakta tha.
**Fix:** Kotlin `OwnerKeyAuthorization.authenticate("save")`, Flutter `OwnerKeyAuthorization.confirm("save")`, uske baad hi model verification aur secure store.
```kotlin
if (!ownerKeyAuthorization.authenticate("save")) {
    keyError = "Phone authentication required"
    return@launch
}
vm.verifyAndSaveApiKey(key)
```

### C-OWNER-02 — Unauthorized key remove (FIXED IN SOURCE)
**Risk:** API key silently erase karke Jiva AI voice disable kiya ja sakta tha.
**Fix:** Each Remove press triggers a NEW fingerprint/device credential challenge; cancel leaves previous key untouched.
```dart
if (!await _ownerKeyAuth.confirm('remove')) return;
await _keyStore.clear();
```

### C-OWNER-03 — Simultaneous biometric prompts (FIXED IN SOURCE)
**Risk:** Rapid double click multiple auth requests/ambiguous results.
**Fix:** Kotlin `testingKey` UI lock; Flutter `_keySettingsOpen` + `OwnerKeyAuthorization._inProgress` and tests. Denied actions never mutate storage.

### C-OWNER-04 — Fake in-app PIN entry (FIXED BY DESIGN)
**Risk:** Custom PIN text field would leak credentials or allow hardcoded bypass.
**Fix:** AndroidX `BiometricPrompt` / Flutter official `local_auth`, `biometricOnly=false` (OS handles device PIN/password/pattern). No fake PIN overlay.

### C-OWNER-05 — Old Firebase deployment assumptions (FIXED)
**Risk:** Unneeded billing, login complexity, failed user setup.
**Fix:** Closed PR #9 without merging and started PR #10 directly from stable merged `main`. Firebase dependencies remain absent in both apps. Do not merge #9.

### C-OWNER-06 — Compromised phone/API key (RESIDUAL)
**Risk:** BYOK is fundamentally a client secret at runtime; rooted/compromised phones may expose it. Gemini Live currently uses key in WSS URL.
**Fix next:** Avoid logging query URLs or credentials, use Google-side quotas and alerting; rotate key after suspected leakage. A backend would mitigate but owner explicitly does not want one; do not silently add it.

### C-OWNER-07 — Real phone verification (PENDING)
**Risk:** Android 8–16 biometric and lockscreen behavior varies. CI only verifies build + unit tests, not real Android prompt.
**Exact test plan:** Set phone PIN and fingerprint; Test & Save with correct fingerprint; cancel (key remains unchanged); wrong fingerprint; PIN fallback; Save invalid Gemini key (old key retained); Remove correct auth; Remove canceled (old key retained); rapid taps; background lock; phone reboot; no network; Jiva speech and local command regression. Repeat on Kotlin and Flutter versions.

## Verification gate

Run Kotlin `:app:testDebugUnitTest :app:assembleDebug`, Flutter `flutter analyze; flutter test; flutter build apk --debug` and compare `fa449d8f...HEAD`. Never merge if red. No production-ready claim until real phone fingerprint and PIN tests.
