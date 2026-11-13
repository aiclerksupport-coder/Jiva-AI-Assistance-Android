package com.jiva.assistance

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jiva.assistance.domain.ReminderItem
import com.jiva.assistance.domain.ReminderIdPolicy

class ReminderScheduler(private val context: Context) {
    private val prefs = context.getSharedPreferences("jiva_reminders", Context.MODE_PRIVATE)

    fun schedule(text: String, triggerAtMillis: Long): Result<ReminderItem> = runCatching {
        require(text.isNotBlank()) { "Reminder text cannot be empty." }
        require(triggerAtMillis > System.currentTimeMillis()) { "Choose a time in the future." }
        require(text.trim().length <= 2000) { "Reminder text is too long." }
        // Synchronize the persistent ID allocation and write: two rapid
        // schedule calls must never reuse a PendingIntent request code.
        synchronized(reminderLock) {
            val existing = (prefs.getStringSet("items", emptySet()) ?: emptySet()).toSet()
            val used = existing.mapNotNull { it.substringBefore('|').toIntOrNull() }.toSet()
            val id = ReminderIdPolicy.next(used)
            val reminder = ReminderItem(id, text.trim(), triggerAtMillis)
            val row = "$id|$triggerAtMillis|${reminder.text.replace("|", " ")}"
            val intent = Intent(context, ReminderReceiver::class.java)
                .putExtra("id", id).putExtra("text", reminder.text)
            val pending = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val newEntries = existing + row
            check(prefs.edit().putStringSet("items", newEntries).commit()) {
                "Could not store reminder on this phone."
            }
            try {
                context.getSystemService(AlarmManager::class.java)
                    .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } catch (error: Exception) {
                // Never leave an apparently scheduled entry when AlarmManager
                // rejects it. Restore the exact previous persistent snapshot.
                prefs.edit().putStringSet("items", existing).commit()
                PendingIntent.getBroadcast(context, id, intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                    ?.let { context.getSystemService(AlarmManager::class.java).cancel(it) }
                throw error
            }
            reminder
        }
    }

    /** Re-register alarms after reboot or package replacement. Past entries are dropped. */
    fun restorePending() = synchronized(reminderLock) {
        val now = System.currentTimeMillis()
        val valid = list().filter { it.triggerAtMillis > now }
        for (reminder in valid) {
            val intent = Intent(context, ReminderReceiver::class.java)
                .putExtra("id", reminder.id)
                .putExtra("text", reminder.text)
            val pending = PendingIntent.getBroadcast(
                context, reminder.id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            context.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pending)
        }
        prefs.edit().putStringSet(
            "items",
            valid.map { "${it.id}|${it.triggerAtMillis}|${it.text.replace("|", " ")}" }.toSet(),
        ).apply()
    }

    companion object {
        // Shared across scheduler instances and the alarm receiver.
        internal val reminderLock = Any()
    }

    fun list(): List<ReminderItem> = (prefs.getStringSet("items", emptySet()) ?: emptySet()).mapNotNull { row ->
        val parts = row.split('|', limit = 3)
        if (parts.size != 3) null else ReminderItem(parts[0].toIntOrNull() ?: return@mapNotNull null, parts[2], parts[1].toLongOrNull() ?: return@mapNotNull null)
    }.sortedBy { it.triggerAtMillis }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel("jiva_reminders", "Jiva reminders", NotificationManager.IMPORTANCE_HIGH))
        }
        val text = intent.getStringExtra("text") ?: "Your reminder"
        val id = intent.getIntExtra("id", text.hashCode())
        val openApp = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, NotificationCompat.Builder(context, "jiva_reminders")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Jiva reminder")
            .setContentText(text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build())
        val prefs = context.getSharedPreferences("jiva_reminders", Context.MODE_PRIVATE)
        synchronized(ReminderScheduler.reminderLock) {
            val existing = prefs.getStringSet("items", emptySet()) ?: emptySet()
            prefs.edit().putStringSet(
                "items", existing.filterNot { it.startsWith("$id|") }.toSet(),
            ).commit()
        }
    }
}

/** Android clears AlarmManager alarms on reboot; restore only future reminders. */
class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
            )
        ) return
        ReminderScheduler(context).restorePending()
    }
}
