# Jiva AI — Batch D Real Android Phone Actions

**Original working code:** owner-only PR #10, SHA `771ee70e645e40652bebf3f67d7770e4051adb1a`  
**PR:** [#11](https://github.com/aiclerksupport-coder/Jiva-AI-Assistance-Android/pull/11), now based on merged `main` at `88374e20ec2143955a9236fd29f34502a31923ba`. PR #10 is merged; owner fingerprint/PIN is preserved.  
**Language:** Hinglish.

## Real, working implementation references (not copied)

This batch is derived from the *existing Jiva source*, cross-checked against:
- [Android Developers — Common intents](https://developer.android.com/guide/components/intents-common): dialer `ACTION_DIAL`, messaging `ACTION_SENDTO` with `smsto:`, user-approved camera/Settings intents.
- [Android Developers — Package visibility](https://developer.android.com/training/package-visibility/declaring): Android 11+ requires appropriate `<queries>` declarations for discovery; launching another app is different from enumerating all installed packages.
- [Android Developers — Limited package visibility](https://developer.android.com/training/package-visibility/use-cases): call `startActivity()` with exception handling rather than trusting a filtered `resolveActivity()` test.
- [Android Developers — Accessibility services](https://developer.android.com/guide/topics/ui/accessibility/service): `AccessibilityNodeInfo.performAction` is scoped to AccessibilityService and must remain user-initiated.

**No copying** of third-party app source, branding, UI, IDs or exact private workflow.

## Implemented existing-code fixes

1. Native Kotlin + Flutter **safe app name matching**: normalize whitespace/case; accept one exact match, else only one unique prefix of 4+ characters. No empty app name, no first arbitrary substring match, no ambiguous selection. Kotlin shows its existing installed-app chooser; Flutter now presents a bounded launcher chooser (name + package) so the owner explicitly selects the intended app. These are conservative existing-UX-compatible choices.
2. Kotlin app enumeration no longer throws away installed packages that share the same label; deduping uses launcher component rather than display label.
3. Native Kotlin `launch(intent)` now propagates missing Activity or Android security denial to the confirmation UI instead of silently dropping a failure.
4. Kotlin and Dart phone policy permits ordinary formatted phone numbers, rejects `*#` USSD/MMI and URI schemes/injection. Call and SMS still open system dialer/composer, **never silently call or send**.
5. WhatsApp share action now accepts a nonempty draft **without requiring a phone number**. It opens the existing share sheet where owner chooses messaging app/chat and taps Send.
6. Native + Flutter Accessibility services reject a second pending request until the first resolves or times out. This prevents rapid taps silently replacing an earlier confirmed action.
7. Dedicated pure tests in Kotlin/Dart cover same-label collision, exact/prefix precedence, empty-name rejection, phone-number/USSD safety, and message-only share.

## VULNERABILITY, FLAW & REAL-WORLD FIX REPORT

### D-01 Wrong installed app launched by substring match (FIXED)
**Production risk:** `Open Phone` galti se `PhonePe` ya `Open Maps` galat alternative launch kar sakta tha.
**Fix:** Pure deterministic matcher, one exact or one unique prefix; otherwise ask owner to choose explicit app. Example:
```kotlin
val apps = queryApps()
val index = AppLaunchMatch.select(requested, apps.map { it.first })
if (index != null) launch(apps[index].second)
else showInstalledAppChooser()
```
Dart equivalent: `AppLaunchMatch.select(query, apps.map((a) => a.name).toList())`; ambiguous matches open a user-controlled chooser, not an automatic launch.

### D-02 Duplicate app labels hidden / ambiguous selection (FIXED)
**Production risk:** Different apps same human-readable label rakh sakte hain; `distinctBy { label }` ek installed app suppress karta tha.
**Fix:** Kotlin launcher `component.packageName + className` se dedupe; matcher duplicate names par null deta hai. Owner chooser shows options.

### D-03 Native intent failure silently ignored (FIXED)
**Production risk:** User ko lagega camera/call/Settings open hua, lekin Android ne reject kiya; app ne failure hide kiya.
**Fix:** `startActivity` `ActivityNotFoundException` / `SecurityException` ko safe messages mein convert karke existing UI `vm.showReply` tak propagate karo.
```kotlin
try { startActivity(intent) }
catch (_: ActivityNotFoundException) {
    throw IllegalStateException("No installed app can handle this action.")
}
```

### D-04 Unsafe number/USSD command allowed (FIXED)
**Production risk:** Local/Gemini interpretation se untrusted `tel:`, MMI/USSD `*#` ya URI-like values dispatch ho sakte hain.
**Fix:** Pure `PhoneNumberPolicy` enforces digits 3–15, length cap, ordinary formatting, no star/hash/schema/semicolon. Both `ActionPolicy` implementations now guard call/SMS.
```dart
if (!PhoneNumberPolicy.isSafe(plan.phoneNumber)) return false;
```
**Note:** Country-specific validity is not implied; owner still reviews number in dialer.

### D-05 Share requires nonexistent recipient number (FIXED)
**Production risk:** Intent-based share chooser ko `phoneNumber` field ki zaroorat nahi, isliye WhatsApp action disabled hota tha jab message available tha.
**Fix:** Share nonempty text via `Intent.ACTION_SEND` / `Share.share`; destination manually choose; owner manually taps Send. No silent messaging.

### D-06 Rapid Accessibility taps replace pending action (FIXED)
**Production risk:** Two user clicks may overwrite a previously confirmed one-shot action without owner noticing.
**Fix:** `check(pendingRequest == null)` before enqueue. Existing 30-second expiry still clears it. No repeated click without fresh confirmation.
```kotlin
check(pendingRequest == null) {
    "Finish the previous screen action before starting another."
}
```

### D-07 Full installed-app list / OEM package visibility (OPEN, DEVICE VALIDATION)
**Production risk:** Different Android 11–16 devices or OEMs filter which launcher apps can be enumerated.
**Next practical step:** Use tested `<queries>` launcher declaration, verify app list on Google/Samsung/Oppo/Vivo/OnePlus, handle filtered/hidden apps honestly. Do not add `QUERY_ALL_PACKAGES` without policy justification.

### D-08 Accessibility sensitive-screen text heuristic (OPEN, DEVICE VALIDATION)
**Production risk:** Banking/payment custom UI might not expose expected labels to Accessibility. Static denylist cannot guarantee privacy.
**Next:** Real-device password, banking, payment, account recovery and notification tests; reject screens flagged private, retain two-step confirmation for risky clicks, avoid unattended automation.

### D-09 User confirmation is not OS completion (OPEN, BY DESIGN)
**Production risk:** `startActivity()` returns after launching a composer but does not confirm actual call, SMS delivery, WhatsApp send or camera capture.
**Next:** Show 'Opened composer/dialer' rather than 'Sent'. Never mark dispatched message as delivered. User reviews and taps Call/Send.

### D-10 Phone lock auth / Gemini BYOK (PRESERVED FROM PR #10)
Fingerprint/system PIN needed for **Gemini key save/remove**, not for each normal device handoff. Android Keystore/Flutter Secure Storage remain unchanged. No Firebase, Cloud Run or backend. Compromised/rooted phone can still expose BYOK key while in use; keep Google key restrictions and quotas.

## Acceptance matrix (must run on real devices)

- Say `Open Phone`, `Open PhonePe`, `Open Google Maps`; force two same-name apps to ensure chooser/explicit-name behavior.
- Attempt empty app input and ambiguous prefix; Jiva must not launch a random app.
- Dial `+91 98765 43210` and review number; attempt `*21*123#` and `tel:123`; both must fail before OS handoff.
- SMS draft with valid number; don't tap Send; confirm no background transmission.
- Share text without phone; choose WhatsApp; confirm explicit chat selection and manual Send.
- Deny camera/Settings app availability; ensure visible error, not fabricated success.
- Tap Accessibility click twice quickly while first pending; second rejected without replacing first.
- Background/reboot/offline test of reminders/wake and Gemini BYOK key add/remove; regression of PR #10 fingerprint/PIN.
- Run native Kotlin and Flutter debug APK separately, then physical device. Green CI cannot prove these behaviors.

## Merge and source safety

PR #10 is merged into `main`, and its merged tree SHA was verified identical to the owner PR head before reconciling Batch D with main. Only Batch D files remain changed. PR #11 must have exact final-head CI green before merge.

Check exact diff versus PR #10 head, Kotlin JVM unit suite, Dart analyzer/tests and two debug APK builds. No app ID/version bump, no Gemini key security rewrites, no new cloud account.
