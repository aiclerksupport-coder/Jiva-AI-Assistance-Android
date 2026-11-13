# Jiva secure Gemini gateway — Batch C

This source **is not deployed** by merging the PR. Real Firebase credentials, a
Cloud Run server, Play Integrity registration and verified user accounts are required.

## Security contract

1. Backend accepts only Firebase ID-token authenticated, **email-verified,
   non-anonymous** users, AND an independent valid Firebase App Check attestation.
2. App Check app ID must appear in `JIVA_ALLOWED_APP_IDS`; leave it empty to
   **deny all** (fail closed). Use actual Firebase App Check app IDs, not package names.
3. Per-UID Firestore transactions limit the number of calls per minute. Add
   Cloud Run, billing and abuse quotas separately; user IDs alone can be rotated.
4. Long-lived Gemini key stays **only** in Google Secret Manager / Cloud Run.
5. Text uses authenticated `POST /v1/interpret`; server sanitizes the action
   JSON, but Android/Flutter must still validate and confirm actions.
6. Live voice uses `POST /v1/live-token` to obtain a **single-use, short-lived**
   token. Mobile uses Gemini `BidiGenerateContentConstrained` with
   `access_token`, not an ordinary API key.
7. Invalid auth, unregistered App Check app IDs, bad JSON, invalid account,
   excessive quota, and missing deployment settings all fail closed.

## Required one-time project configuration

1. Firebase Console: create project, enable **Email/Password Authentication**,
   and register both `com.jiva.assistance` and
   `com.jiva.assistance.flutter` as separate Android apps.
2. Enable App Check Play Integrity for each app and configure signing
   certificate SHA-256 as appropriate. In Firebase's App Check panel, record
   each exact App Check app ID for `JIVA_ALLOWED_APP_IDS`.
3. Configure genuine verified users. First email/password login attempts send
   an email-verification message where supported. Verify email, then sign in again.
4. Google Cloud: provision Firestore and Cloud Run, deploy this Node service
   under least-privilege identity with Datastore/Firestore access.
5. Store `GEMINI_API_KEY` in Secret Manager and bind **only** to the backend
   runtime service identity. Never commit keys or service-account JSON.
6. Set `GOOGLE_CLOUD_PROJECT`, `JIVA_ALLOWED_APP_IDS`, optionally
   `GEMINI_TEXT_MODEL` and `GEMINI_LIVE_MODEL`, and `PORT`.
7. Deploy in a region close to India; Cloud Run publicly reachable HTTPS
   endpoint relies on this service's mandatory Firebase auth + App Check checks.
8. Android native release Gradle properties: `jivaGatewayUrl`,
   `jivaFirebaseProjectId`, `jivaFirebaseAppId`,
   `jivaFirebaseWebApiKey`. Example:
   `./gradlew :app:assembleRelease -PjivaGatewayUrl=https://your-run-host -PjivaFirebaseProjectId=PROJECT -PjivaFirebaseAppId=1:...:android:... -PjivaFirebaseWebApiKey=PUBLIC_FIREBASE_KEY`.
9. Flutter release uses public `--dart-define=JIVA_GATEWAY_URL=https://...`,
   `JIVA_FIREBASE_PROJECT_ID`, `JIVA_FIREBASE_APP_ID`,
   `JIVA_FIREBASE_WEB_API_KEY`, `JIVA_FIREBASE_SENDER_ID`.
   `JIVA_REQUIRE_GATEWAY` defaults to **true** for production Flutter builds;
   native Kotlin builds use production gateway when `BuildConfig.DEBUG=false`.
10. Configure release signing; replace existing debug-signing placeholders before
    publishing. Android Play Integrity requires a correctly registered app/signature.

**Operational cost:** Firebase/Cloud Run/Secret Manager may require a
billing-enabled Google Cloud project (Firebase Blaze). Do not promise this
production architecture can run on Firebase Spark alone.

## CI & checks

```sh
cd backend
npm run check
```

The check executes `node --check` and `node --test gateway_core.test.mjs`
without Firebase credentials. This does **not** test a deployed server.

Still required before production release: source dependency lockfile, deployed
integration tests (valid/expired Auth, invalid App Check, wrong app IDs, verified
email refusal, 429 concurrency, Live-token expiration, user cancellation),
Android real-device testing and Firebase rules/billing abuse controls.
