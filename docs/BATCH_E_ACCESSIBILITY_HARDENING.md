# Jiva AI — Batch E: Accessibility Window, Lock Screen & Race Hardening

**Authoritative merged base:** `f278084f8649e12353d34bc83704f5281b28ea94`  
**PR:** [#12](https://github.com/aiclerksupport-coder/Jiva-AI-Assistance-Android/pull/12)

## Real Android references

Official Android Developers:
- [AccessibilityEvent API](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent): event package name and event types.
- [Create your AccessibilityService](https://developer.android.com/guide/topics/ui/accessibility/views/service): changing windows, AccessibilityNodeInfo source, and event timing.
- [KeyguardManager API](https://developer.android.com/reference/android/app/KeyguardManager): `isKeyguardLocked` is Android's own lock-screen state.

Implementation **Jiva ke existing Kotlin/Flutter source** par hai, kisi third-party app ki copying nahi.

## Implemented change inventory

- Native Kotlin `JivaAccessibilityService`: event package AND window ID vs active window root package AND window ID identity check.
- Flutter Android plugin `JivaAccessibilityService`: same package + window identity check.
- Both: no screen assistance when Android keyguard locked. New requests blocked; queued request cancelled on subsequent accessibility event if phone locks.
- Both: event handler validates one `rootInActiveWindow`, then passes that very same node to click/scroll action rather than refetching and potentially acting on another window.
- Native: private-screen traversal exceeding maximum depth now treated as private (fail-closed) instead of safe.
- Pure package matching policy and negative-path JVM tests for Kotlin app and Flutter plugin. Existing owner-only fingerprint/PIN and direct Gemini BYOK remain untouched.
- No new Android permissions, Firebase, Cloud Run, overlay, system root, or background spying.

## VULNERABILITY, FLAW & REAL-WORLD FIX REPORT

### E-01: Stale event acts on a different app (HIGH — SOURCE FIX)
**Risk:** Owner confirmed action for App A. Fast app switching shows App B before handler gets `rootInActiveWindow`. Old code trusted event package and clicked whatever active root it received.
**Fix:** Reject unless event package and active root package match exactly **AND** their window IDs match. Official Android `AccessibilityRecord.getWindowId()` and `AccessibilityNodeInfo.getWindowId()` identify the owning window, so a different dialog/window of the same package must not inherit a prior approval. No second attempt or silent fallback.
```kotlin
check(WindowPackagePolicy.isTrustedTarget(
    eventPackage, root.packageName?.toString(),
    event.windowId, root.windowId
)) { "Active screen changed; review and retry." }
```
**Remaining:** Current event package can match the root while UI content changes within the same app; click policy still requires exact unique visible label.

### E-01a: Different window of the same app (HIGH — SOURCE FIX)
**Risk:** Two activities/dialogs can share the same package. An event from window #10 could trigger a click in active window #11 even though both report the same application package.
**Fix:** Match both window IDs and deny unknown/negative IDs. Kotlin unit tests cover same-app different-window and invalid IDs. [Android AccessibilityRecord](https://developer.android.com/reference/android/view/accessibility/AccessibilityRecord) and [AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo) document the two window identifiers.
```kotlin
if (event.windowId < 0 || event.windowId != root.windowId) return
```

### E-02: Screen changes between privacy check and click (HIGH — SOURCE FIX)
**Risk:** `onAccessibilityEvent` reviewed one root, but `clickVisibleText` requested another root later. That is a time-of-check/time-of-use race.
**Fix:** Keep the one reviewed root through entire synchronous operation.
```kotlin
val root = rootInActiveWindow ?: return
// Privacy guard and source verification...
val clicked = clickVisibleText(request.value.orEmpty(), root)
```
**Remaining:** External app can still change its own node tree asynchronously; Android `performAction` might return false. Never treat it as delivery.

### E-03: Pending request runs after screen lock/unlock (HIGH — SOURCE FIX)
**Risk:** Pending click queued, phone locks, unlock takes place, then old click executes on a newly visible target.
**Fix:** On queue, on any pending event, and before direct phone navigation: check `KeyguardManager.isKeyguardLocked`. Lock -> clear pending request and return cancellation.
```kotlin
if (isPhoneLocked()) {
    pendingRequest = null
    handler.removeCallbacksAndMessages(null)
    reportCancellation()
    return
}
```
**Remaining:** Some OEM devices expose additional privacy overlays after unlock; real physical-device acceptance required.

### E-04: Deep private screen not treated as private (MEDIUM — SOURCE FIX)
**Risk:** Native private text recursion reaching `MAX_DEPTH` previously returned false: large complex login/payment screens could escape text privacy heuristic.
**Fix:** Reaching depth limit now returns true; no read/click on oversized nested screen.
```kotlin
if (depth > MAX_DEPTH) return true
```

### E-05: Accessibility overreach (OPEN — DEVICE/PRODUCT POLICY)
**Risk:** Node heuristics cannot prove all third-party banking/payment UI is safe. Accessibility may expose sensitive screen elements.
**Next steps:** Keep current conservative credential/privacy denylist, explicit owner confirmation for each action, no autonomous bulk reading, no account/credential capture. Test real Google/Samsung/OnePlus/MIUI screens. Do not enable automatic secret filling or payment controls.
**External reference:** Android AccessibilityService documentation above.

### E-06: Real-device and race tests (OPEN — RELEASE GATE)
**Risk:** Unit tests verify pure package policy, not actual multi-window event timing or KeyguardManager OEM behavior.
**Tests to execute:** 
1. Request screen click in App A, instantly switch App B; verify no click and visible warning.
2. Queue click, lock phone, unlock, open another app; queued action must not run.
3. Queue two actions during 30 seconds; second rejected, first remains unchanged until expiry/finish.
4. Password/OTP/payment/banking screen reading and clicking blocked in both Kotlin/Flutter.
5. Exact same-label button ambiguity remains blocked; normal buttons can act after owner confirmation.
6. Rotate screen, split-screen, app overlay, notification shade, delayed Accessibility event, service disabled, and app/phone reboot.
7. Confirm Android 8–16 behavior; record device model, OS, expected/actual result and any failure logs **without private screen text**.

### E-07: Owner-only key security (UNCHANGED)
Gemini key remains entered by owner, encrypted locally and add/remove protected with system fingerprint/phone PIN (Batch C owner-specific PR #10). No Firebase login or Cloud Run. Model requests still use owner's on-device API key, so rooted phone and key-in-network risks remain by owner choice.

## Baseline and merge gate

Compare final Batch E head against `f278084f8649e12353d34bc83704f5281b28ea94`. Only intended Accessibility service, pure policy, tests and report should change. Build and unit tests green required on exact PR head before merge. A green CI means compilation/test success, **not** proof of phone behavior.
