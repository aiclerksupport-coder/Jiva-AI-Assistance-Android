# Jiva production Gemini gateway (deployment required)

This is the secure server-side path for **production**. It is not enabled automatically by committing this folder.

## Authentication and security

- Firebase Authentication ID token in `Authorization: Bearer <Firebase ID token>`.
- Firebase App Check token in `X-Firebase-AppCheck`.
- Both tokens are independently verified by the Firebase Admin SDK; identity and App Check are required together.
- Firestore transactional per-user request quota limits repeated calls; additionally configure Cloud Run ingress, IAM/abuse controls and billing quotas.
- Gemini long-lived key is read **only from server environment** (Google Secret Manager recommended).
- `POST /v1/interpret` proxies text generation and returns validated action data. Client must continue to confirm every non-chat action.
- `POST /v1/live-token` returns a 1-session ephemeral Live token (new connection within 60 seconds; lifetime 30 minutes).
- Real Live clients must connect to `v1alpha` constrained Live endpoint using `access_token` or the documented Token authorization scheme; do not place long-lived Gemini keys in websocket query URLs.

## Required deployment steps

1. Configure Firebase project and Firebase Authentication on both Android and Flutter apps, plus Play Integrity App Check provider (debug provider only in development).
2. Create an authenticated Cloud Run service account with Firestore access; set Cloud Run identity policies and restrict deployed endpoints as appropriate.
3. Provision Firestore and deploy this Node 20+ service with GEMINI_API_KEY from Secret Manager (not in git).
4. Configure clients to call the authenticated gateway and request Live token when launching each session.
5. Disable/remove direct user-key production paths and verify no persistent Gemini API key remains on-device. Existing BYOK development code is **not yet migrated** by this backend addition.
6. Test with valid/expired ID token, invalid App Check, concurrent rate limits, quota, 401/429/502, live token expiry, and end-to-end voice playback.
7. Validate Gemini model availability and the Live API preview contract in the deployed project; exact model IDs may change.

Do not claim this mitigates the running APK until client migration, deployed backend, and verification are complete.
