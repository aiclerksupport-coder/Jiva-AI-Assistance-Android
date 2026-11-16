# JIVA — SUPERVISED AUTO-RUN AND VISIBLE-STATE PROOF (2026-11-17)

## What was changed
- **Dart-first single-approval safe-run:** after reviewing the AI-generated plan, user can start a bounded run of safe steps. No fresh dialog for every read, scroll, back or non-sensitive click; typing and sensitive buttons still require manual approval.
- **Native Android current-app binding:** the first action waits for user to switch to a target app, subsequent approved actions can act on the already visible window only if its package matches the previously reviewed target. OS-locked, private, password and restricted screens are rejected.
- **Post-action proof:** on native action acceptance, Android waits 450 ms and reads the target accessibility window again. Click/scroll/back require a visible tree or window change; text input requires the exact requested text to be visible. Unchanged/changed-package/secure/locked outcomes are explicitly **unverified** and auto-run stops. This proves an observable UI transition, **not** that a remote business transaction succeeded.
- **Existing operation IDs, write-ahead encrypted checkpoints, one-flight queue and cancellation are preserved.**
- Voice commands with `Jiva phone task ...`, `phone par ...`, or `mobile par ...` route to reviewed workflow planning. Voice never starts device actions silently.
- Fixed an existing Dart native bridge argument defect: `performPhoneNavigation` referenced an undefined `operationId`.

## Real limitations (do not call this full autonomous multi-app support)
- Only **same-target-app** safe-run is supported in this batch. Cross-app transitions intentionally pause; different package verification and app launch must be a separately reviewed feature.
- Visible text/window change is weak semantic proof. A button may update the UI without completing the requested remote operation; false positives are possible. Production requires per-step goal assertions and explicit semantic confirmation when available.
- Tasks are not guaranteed to execute after Android force-stop, process death or OS service termination. The existing background WorkManager worker only handles safe maintenance, not arbitrary UI automation.
- Android accessibility events are not guaranteed for all apps (custom canvases, browsers, secure windows, OEM differences).
- Flutter SDK, Android SDK, and a connected physical phone are not available in this execution environment: **NO Flutter analyze/test/build, install, or runtime verification has occurred.** This archive is an UNVERIFIED SOURCE CANDIDATE.

## Verification plan on user's development laptop
1. Extract ZIP into a new folder. Do not replace the original working Jiva checkout.
2. In `flutter/` run `flutter pub get`, `dart format --output=none --set-exit-if-changed lib test`, `flutter analyze`, `flutter test`.
3. Run `flutter build apk --debug` and `flutter build apk --release`; retain verbose logs.
4. Test on Android 11, 13, 14 and 15 where available. Explicitly enable accessibility only for a safe testing app.
5. Test one-step read, click that changes a screen, scroll with new items, unchanged click, duplicate labels, typed text in non-password field, password page rejection, lock phone mid-action, cancel during 450 ms verification, repeated rapid taps, app switch to an unexpected package, network loss, app kill, service disable, back and timeout.
6. Never automate bank, payment, OTP, password, purchase, destructive, private communication or unknown secure flows.

## VULNERABILITY, FLAW & REAL-WORLD FIX REPORT

**High — Weak semantic verification**: UI changed is not task success. Add per-step expected package and an expected visible text/role/assertion. If absent, report only `UI transition observed`, not `task completed`. Next version: refine `PhoneStep` with `expectedVisibleText` and check after each transition; pause on mismatch.

**High — Cross-app navigation**: Native package bound to same app, so navigating to another app is rejected (safe default). Next version: allow a user-reviewed ordered app package allowlist and verify each transition via Android package identity, never arbitrary redirects.

**High — Unattended execution risk**: Safe-run still depends on the active AccessibilityService, Android lifecycle and screen visibility. No root/ADB bypass; stop if phone locks or the OS revokes service. Limit to up to eight steps with a user-reviewed plan; no automatic approval for sensitive steps.

**Medium — Dynamic UI race**: 450 ms snapshot may be too early or change for unrelated reasons. Next version: bounded retries (e.g. at 300/700/1200 ms), capture matching operation ID and expected semantics, never repeat the underlying click.

**High — Privacy**: Accessibility tree may hold personal data. Keep snapshots ephemeral, never log/upload full tree, suppress sensitive screens and avoid cross-app persistence. Review and test OCR/vision sharing independently.

**Critical — Compilation/runtime unverified**: Run Flutter and Android builds and phone scenarios before release. Keep original authoritative ZIP until verified.
