# Jiva AI — Batch C Gemini Security, Auth & Live Token Report

**Language:** Hinglish  
**Original authoritative base:** `fa449d8fd61216dd9ab3a8c82d8df67c100844be` (merged Batch B)  
**PR:** [#9](https://github.com/aiclerksupport-coder/Jiva-AI-Assistance-Android/pull/9)

## Scope aur actual result

Is batch ka purpose existing **client-side Gemini BYOK** ko secure production flow se replace karna tha. Native Android Kotlin aur Flutter ke original debug/development mode ka BYOK behavior preserve hai, lekin **release builds ab hamesha gateway require karte hain**. User ka long-lived Gemini key release build mein HTTP ya WebSocket ko nahi bheja jayega; legacy stored Gemini keys release initialization par delete ho jaate hain.

- `backend/server.mjs`: Firebase ID token + App Check verification, **allowed app ID** whitelist, **email verified, non-anonymous** user requirement, per-user Firestore transactional quota, sanitized actions, HTTPS proxy and per-session Live tokens.
- `backend/gateway_core.mjs` + `.test.mjs`: pure security guard helpers aur Node tests.
- Kotlin: Firebase Auth, Play Integrity, verified email sign-in UI, `JivaGatewayClient` based text calls, short-lived Live tokens via constrained WebSocket, BYOK disabled in secure mode.
- Flutter: Firebase Core/Auth/App Check integration, verified email sign-in UI, no production BYOK, authenticated gateway requests, one-time Live token per retry, redirect rejection tests.
- CI tests aur APK builds ke bina actual device/Cloud Run integration **verified nahi** kaha jayega.

## Exact practical deployment checklist

1. Firebase project mein native app `com.jiva.assistance` aur Flutter app `com.jiva.assistance.flutter` register karo.
2. Email/Password Auth enable karo, verified users provision karo; email verification test karo.
3. Har app ke signing certificate fingerprints register karke App Check Play Integrity enable karo. Registered **Firebase App Check app IDs** ko comma separated `JIVA_ALLOWED_APP_IDS` mein set karo.
4. Google Cloud billing, Firestore, Cloud Run, Secret Manager aur IAM configure karo. Backend service account ko sirf required Firestore/Secret access do; Cloud Run service ko per-user OAuth bypass na samjho (backend tokens independently verify karta hai).
5. `GEMINI_API_KEY` **sirf** Secret Manager mein store karo; app/client Gradle/Dart define/CI log mein kabhi nahi.
6. Backend deploy karo. `GOOGLE_CLOUD_PROJECT`, `JIVA_ALLOWED_APP_IDS`, supported `GEMINI_TEXT_MODEL` aur `GEMINI_LIVE_MODEL` set karo.
7. Native Kotlin release build ke public Gradle props `jivaGatewayUrl`, `jivaFirebaseProjectId`, `jivaFirebaseAppId`, `jivaFirebaseWebApiKey` supply karo. Flutter release ke `--dart-define` values `JIVA_GATEWAY_URL`, `JIVA_FIREBASE_PROJECT_ID`, `JIVA_FIREBASE_APP_ID`, `JIVA_FIREBASE_WEB_API_KEY`, `JIVA_FIREBASE_SENDER_ID` supply karo.
8. Debug APK se release signing ka comparison mat karo; proper release keystore aur Play Integrity ke exact certificate fingerprints use karo.
9. End-to-end verify: valid/invalid ID token, unverified email, anonymous user, wrong App Check app ID, rate limit, bad JSON, token expiry/retry, client offline, account disabled, long TTS, phone action confirmation.
10. Cloud Run/Secret Manager/Firestore ke production usage par billing lag sakti hai; **Firebase Spark-only free plan par complete production gateway guaranteed nahi**.

## VULNERABILITY, FLAW & REAL-WORLD FIX REPORT

### C-01 — Client long-lived Gemini API key (CRITICAL)
**Risk:** APK extraction, rooted phone, debugging ya interception se BYOK/long-lived server key expose ho sakti thi.
**Implemented:** Production native `!BuildConfig.DEBUG || BuildConfig.JIVA_REQUIRE_GATEWAY`, Flutter `bool.fromEnvironment('dart.vm.product') || bool.fromEnvironment('JIVA_REQUIRE_GATEWAY')`. Dono release paths mein direct Gemini key disable, old saved key clear.
```dart
static const requireGateway =
    bool.fromEnvironment('dart.vm.product') ||
    bool.fromEnvironment('JIVA_REQUIRE_GATEWAY');
```
**Remaining:** real release APK inspect + phone verification.

### C-02 — Gemini Live key WebSocket URL (CRITICAL)
**Risk:** Query parameters/logging ke through long-lived Gemini key leak ho sakta tha.
**Implemented:** Server one-use token create karta hai; mobile constrained Live WebSocket with `access_token` per connection/retry.
```kotlin
if (token != null) {
    builder.addPathSegments(
        "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
    ).addQueryParameter("access_token", token)
}
```
**Remaining:** deployed Gemini Live model support confirm and stress test.

### C-03 — Backend unauthorized app/project (HIGH)
**Risk:** Valid Firebase user hone ke bawajood kisi aur App Check registered application se costly endpoint call possible.
**Implemented:** `validateAppId(app.appId, process.env.JIVA_ALLOWED_APP_IDS)` default deny.
```javascript
const allowed = (process.env.JIVA_ALLOWED_APP_IDS || '').split(',');
if (!allowed.includes(app.appId)) throw Object.assign(
  new Error('Untrusted app registration'), {status:403});
```
**Remaining:** register exact native/Flutter signing identity and real-world App Check enforcement.

### C-04 — Anonymous/unverified user abuse (HIGH)
**Risk:** Unlimited anonymous accounts create karke rate limit bypass ho sakta tha.
**Implemented:** Backend checks `user.firebase.sign_in_provider !== 'anonymous'` aur `user.email_verified === true`; client login UI also requires verified email.
**Remaining:** stronger abuse policies (per-device/IP/paid-account limit) and compromised account detection.

### C-05 — Response-based phone action spoofing (HIGH)
**Risk:** AI hallucinated action jaise arbitrary `DELETE_ALL` phone action ban sakti thi.
**Implemented:** server strict action allowlist, bounded text, phone navigation allowlist, unknown values -> `CHAT`; client confirmation unchanged.
```javascript
const action = ALLOWED_ACTIONS.has(value.action) ? value.action : 'CHAT';
```
**Remaining:** phone side final dispatch validation aur malicious prompt injection integration tests.

### C-06 — Gateway redirect credential leak (HIGH)
**Risk:** Server redirect ke zariye bearer token aur App Check token third-party domain tak pahunch sakte the.
**Implemented:** Kotlin `instanceFollowRedirects = false`, Flutter `http.Request(...).followRedirects = false`.
**Remaining:** allowlisted exact deployment host, HTTPS certificate pinning policy decision and rotating endpoints.

### C-07 — Token/retry mismatch (MEDIUM)
**Risk:** One-use Live token ko retry mein reuse kiya jaata to second handshake reject hota.
**Implemented:** Token provider invoked **each websocket attempt**, not once per voice reply.
**Remaining:** timeout/expired token actual service tests.

### C-08 — Request flood / expensive Gemini usage (HIGH)
**Risk:** Cloud billing spikes aur service DoS.
**Implemented:** Existing Firestore transactional 20/min per UID quota retained; strict 8192-byte JSON cap.
**Remaining:** IP/device/payment quota, Cloud Armor or load-shedding, budget alerts, abuse tracing without storing secrets.

### C-09 — Secret/project deployment dependency (CRITICAL open)
**Risk:** Server code committed hone ka matlab live proxy active nahi; bina Google Secret Manager aur Cloud Run backend app requests fail hoti rahengi.
**Fix:** `backend/README.md` ke steps follow karke real deployment, least privilege IAM, Firebase configs, verified users aur live health/integration tests.
**No fake status:** Repo CI green ho tab bhi production deployment **unverified**.

### C-10 — No frozen backend dependency graph (MEDIUM open)
**Risk:** `package-lock.json` absent, backend transitive dependency versions unexpectedly shift ho sakte hain.
**Fix:** Network-connected trusted environment mein `npm install --package-lock-only` run kar ke lockfile commit karo, `npm ci`/audit CI mein enable karo.
```bash
cd backend
npm install --package-lock-only
npm ci
npm test
```
**Note:** Container environment network access nahi tha, isliye fake lockfile generate nahi kiya.

### C-11 — SDK/runtime compatibility (HIGH)
**Risk:** FirebaseAuth Kotlin metadata old KGP ke saath compile fail, Flutter App Check 0.5 changed provider signature.
**Implemented:** Kotlin KGP 2.3.20 aur Firebase BOM 34.19.0; Flutter SDK `providerAndroid: const AndroidPlayIntegrityProvider()`.
**Remaining:** final CI success, full release minification and Android 13–16 device matrix.

### C-12 — Real-world device & credential tests (HIGH open)
**Risk:** Play Integrity on sideload/dev builds, network reconnect, token revocation aur email verification failures differ from CI.
**Fix:** Physical device with real Play Integrity, registered SHA-256, email-verified account and deployed gateway. Test invalid/expired tokens, cancellation, 429, offline, network switching, background voice and action confirmation.

## Base version comparison gate

All Batch C files must be compared byte-by-byte against `fa449d8fd61216dd9ab3a8c82d8df67c100844be` via GitHub compare. No package name/voice wake word/phone-action contract changes intended. **Do not merge** until final PR-head CI 3/3 green. No production-ready claim without deployed backend and real Android sign-in testing.
