# Jiva — two-feature bounded integration (2026-11-17)

## Scope (no unrelated feature changes)

1. **Reviewed cross-app workflows:** `open_app` is an explicit workflow step that resolves an installed launcher name via existing `AppLaunchMatch` and `installed_apps`. Ambiguous or absent names fail closed. Native accessibility service launches the exact resolved package and only returns success after the *foreground root and window event* identify that package. Each requested operation uses the existing unique operation ID and journal checkpoint. Only explicitly reviewed plan steps auto-run; sensitive click/type still require approval. No arbitrary package names or background blind clicking.
2. **End-goal UI verification:** `verify_text` is a final-only, exact visible-text assertion. Cross-app workflows without it are rejected by the Dart parser. The native AccessibilityService checks the active allowed non-private window, the bound app identity, and a single exact visible line. A successful tap/launch alone never marks the goal verified. Only a successful matching operation ID on the final `verify_text` step sets `goalVerified=true`. A failure, timeout, interrupted action or restart cannot auto-complete the goal.

## What the feature does NOT prove

* Visible UI state is evidence, **not a backend transaction receipt**. A generic message like "Saved" could be stale. For server-backed ERP tasks, verify authoritative transaction IDs via the app's own authenticated API/receipt, not accessibility-only text; do not auto-run irreversible actions.
* Android may delay/rescind accessibility events, restrict app launch, kill background processes, or obscure sensitive screens. Phone force-stop cannot be bypassed. Actual cross-app execution requires phone-level testing.
* Other tasks (continuous voice, offline LLM, background scheduler) were not modified in this batch.

## Manual device acceptance checklist

- Install debug/release APK, enable Jiva accessibility with explicit permission; start from a clean Jiva session.
- Positive: plan `open_app` A → read → `open_app` B → `verify_text` exact B label. Review single plan; confirm Android foreground package matches each switch; final displays goal verified ONLY if exact text exists.
- Negative: nonexistent app, two same-name apps, non-launchable system settings, package changes mid-action, private/password screen, locked phone, missing result text, late result with wrong operation ID, rotation, screenshot privacy, repeated fast taps, process kill during open step.
- Simulate failure after launch but before result: checkpoint should remain uncertain; never auto-replay action or claim success.
- Run `flutter pub get`, `flutter analyze`, `flutter test`, `flutter build apk --release`; then actual device tests (`adb logcat`, launch switching and network/UI latency). Commands have **not** been run in this environment (Flutter SDK/ADB unavailable).

## Risks to patch before production

1. **UI false positives**: require backend receipts for business transactions. Do not infer server persistence from mere text.
2. **App launcher visibility**: Android OEM/package visibility differences; use app picker fallback if matching fails and require re-approval.
3. **Result continuity**: Native result buffer is process-local. If app is killed, journal must remain uncertain and the user must re-plan.
4. **Focus/latency**: Some targets will not expose accessibility trees or WINDOW_STATE_CHANGED. Bound observation can time out; never retry clicks automatically.

### Dart end-goal guard (included in the candidate)
```dart
if (session.isComplete && !session.goalVerified) {
  // Steps executed; do NOT claim the user's end goal was achieved.
}
```
