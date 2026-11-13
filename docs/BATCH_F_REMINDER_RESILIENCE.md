# Jiva AI — Batch F: Reminders, Reboot & Atomic Local Save

**Base:** `39ef0c55dbeb715a7b0ee49023e5450611719cc5` (merged Batch E)  
**PR:** [#13](https://github.com/aiclerksupport-coder/Jiva-AI-Assistance-Android/pull/13)  
**Implementation:** Native Kotlin app + existing Flutter app. No backend, Firebase, Cloud Run or new login.

## Official real sources used (not copied)

- [Android Developers — Schedule alarms](https://developer.android.com/develop/background-work/services/alarms). `setAndAllowWhileIdle` is an **inexact** notification alarm; Doze and vendor restrictions may delay delivery and no promise of exact time is valid.
- [Android AlarmManager API](https://developer.android.com/reference/android/app/AlarmManager): `setAndAllowWhileIdle`, reboot resets alarms; `PendingIntent` request identity; OS controls delivery time.
- [flutter_local_notifications plugin](https://pub.dev/packages/flutter_local_notifications): `zonedSchedule`, `AndroidScheduleMode.inexactAllowWhileIdle`, reboot manifest receivers and Android OEM alarm restrictions.
- [Android SharedPreferences.Editor#commit](https://developer.android.com/reference/android/content/SharedPreferences.Editor#commit()): synchronous persistence result vs asynchronous `apply`.

Existing code inspected: `app/src/main/java/com/jiva/assistance/ReminderScheduler.kt` and `flutter/lib/services/reminder_service.dart`. No source cloned from another app.

## Implemented fixes

- Native: reject invalid/oversized text, allocate unused positive 31-bit ID rather than unverified UUID hash code, serialize ID allocation and persistence to prevent duplicate pending alarm overrides.
- Native: first commit durable reminder row, then register with AlarmManager; if OS rejects scheduling, rollback prior row and cancel newly-created pending intent.
- Native: new lossless v2 reminder row encoding (UTF-8 Base64) supports "|" pipe, emoji, multiple languages and newlines; original `id|time|text` records remain readable. Receiver removes delivered reminder by decoded ID from either version.
- Native: synchronize receive/reminder list mutation to avoid a lost update during rapid save/delivery.
- Native: on next app open, re-register pending future alarms from durable local records on `Dispatchers.IO`, covering a process death between persistence and OS alarm registration without freezing the Compose UI.
- Flutter: serialize rapid schedule calls; select unused notification ID from live list; save list to FlutterSecureStorage before plugin scheduling and restore original list if scheduling throws.
- Pure Kotlin tests: collision ID, invalid IDs, malformed storage rows, special-character roundtrip and v1 backward compatibility.
- Pure Dart tests: duplicate/invalid ID rejection and fallback; no Firebase dependencies.

## VULNERABILITY, FLAW & REAL-WORLD FIX REPORT

### F-01: PendingIntent ID collision (HIGH — FIXED IN SOURCE)
**Risk:** `UUID.randomUUID().hashCode()` bina local check ke same requestCode de sakta hai. `PendingIntent.FLAG_UPDATE_CURRENT` then ek existing alarm ko doosre reminder se overwrite kar sakta hai; owner ko reminder missing lagega.
**Solution:** Existing persistent IDs ko scan karke unused positive ID allocate karo. 128 candidate retries ke baad deterministic free-slot fallback, repeated collisions tests.
```kotlin
val existingIds = prefs.getStringSet("items", emptySet()).orEmpty()
    .mapNotNull(ReminderRowCodec::decode).map { it.id }.toSet()
val id = ReminderIdPolicy.next(existingIds)
```

### F-02: Reminder save not durable before OS scheduling (HIGH — MITIGATED)
**Risk:** OS ne alarm set kar diya, phir phone/app crash hua toh app ke reminder list mein entry nahi dikhegi. Old code `apply()` async write use karta tha.
**Solution:** `commit()` successful hone ke baad AlarmManager call; failure par previous snapshot restore. Flutter side bhi secure storage before schedule, rollback if plugin throws.
```kotlin
check(prefs.edit().putStringSet("items", newEntries).commit())
try { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending) }
catch (error: Exception) {
    prefs.edit().putStringSet("items", existing).commit()
    throw error
}
```
**Residual:** OS alarm scheduling aur local preference storage ek single database transaction nahi ho sakte. Process death precisely between persistent write and alarm registration may leave a dormant record until next reboot/restore. Startup reconciliation is implemented in `MainActivity` on `Dispatchers.IO` for future records; real-device crash/restart tests still required.

### F-03: Native reminder text truncated at "|" (MEDIUM — FIXED)
**Risk:** User reminder like "Call Raj | confirm order" stored as altered text; multline/emoji encoding unsafe when delimiter is direct.
**Solution:** Versioned Base64 UTF-8 row + compatibility parser. No user-data reset; old v1 format still loaded.
```kotlin
val row = ReminderRowCodec.encode(reminder)
val restored = ReminderRowCodec.decode(row)
```

### F-04: Parallel receiver/save lost update (HIGH — FIXED IN PROCESS)
**Risk:** Alarm receiver and foreground Activity both mutate same `SharedPreferences` string set, overwriting each other's updates.
**Solution:** Shared `ReminderScheduler.reminderLock` around mutations, and synchronous `commit()`. App process remains the same by current manifest. Do not pretend cross-process/distributed atomicity.

### F-05: Double-tap Flutter reminder save (MEDIUM — FIXED)
**Risk:** Two rapidly overlapping `zonedSchedule` tasks could select same ID or write stale list, losing one reminder.
**Solution:** `_schedulePending` guard in existing Flutter ReminderService, with `finally` releasing even after failures. Repeated taps show an explicit retry error rather than silently duplicating.
```dart
if (_schedulePending) throw StateError('A reminder is already being saved');
_schedulePending = true;
try {
  // persist + schedule, rollback on failure
} finally {
  _schedulePending = false;
}
```
**Residual:** In-process guard covers one ReminderService instance, not a second isolate/process. Current app constructs one service; future multi-instance use requires shared serial executor/DB transaction.

### F-06: Android reminder arrival is not exact (PLATFORM LIMIT — OPEN)
**Risk:** User expects 10:00 reminder at exactly 10:00 but inexact `setAndAllowWhileIdle` or `inexactAllowWhileIdle` may deliver late during Doze/OEM battery savings.
**Solution:** Keep current OS-permission-light inexact scheduling and tell owner timing is approximate. Do **not** secretly request exact alarm permission or claim exact delivery. If exact reminders become a core requirement, add a separately reviewed opt-in Android exact alarm permission flow following official docs and Play policy.

### F-07: Reboot + stale persisted reminder (OPEN)
**Risk:** On reboot, old reminder earlier than current time is intentionally dropped by existing code. Unexpected power-off during scheduled interval may mean missed alarm without catch-up. Flutter notification plugin uses its boot receiver but OEM restrictions may still suppress it.
**Next tests:** Reboot before deadline, reboot after deadline, airplane mode, battery saver, notifications denied; confirm list/notification message honestly. Avoid claiming delivery without OS notification shown.

### F-08: Hidden/disabled notification permission (OPEN)
**Risk:** Android 13+ user can disable POST_NOTIFICATIONS, so local persisted reminder may not appear despite successful OS schedule. Existing UI has permission warning; keep it, test permission revoked after scheduling.
**Fix future:** Add explicit permission status to reminder list and alert on disabled notifications, without requesting invasive permissions or bypasses.

### F-09: Client Gemini/owner authentication (PRESERVED)
Owner saves/removes own Gemini key only after real device fingerprint/PIN check. BYOK Gemini voice, Jiva/Giva wake, local actions and Accessibility unaffected. Firebase/Cloud Run not included.

## Release/test checklist

- Native Kotlin: schedule 100 reminders, rapid tap, same-second collisions; verify list and notifications.
- Flutter: rapid save, denial, storage failure, reboot, notifications permission.
- Test UTF-8 Hindi/Gujarati/Marathi, emoji, pipe, newline and old existing reminders survive upgrade.
- Power off/reboot 2 minutes before event; verify receiver restores future alarm; reboot after time should not assert "delivered."
- Change timezone and device clock: verify absolute timestamps and user-visible wall time.
- Airplane mode/offline: local reminders still work if OS permits.
- Android 8–16 device matrix, Samsung/Xiaomi/Oppo battery restriction behavior.
- CI: Kotlin compile/tests/debug APK; Flutter analyze/tests/debug APK; gateway syntax check.
- Original Git diff: reminder scheduler/domain/test/report and targeted MainActivity startup recovery only. No app IDs, Gemini model/keys, permissions, UI shell changed.

**Important:** Green CI proves source builds/tests, not real notification delivery or exact-timing behavior.
