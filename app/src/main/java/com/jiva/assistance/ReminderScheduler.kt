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

class ReminderScheduler(private val context: Context) {
    private val prefs = context.getSharedPreferences("jiva_reminders", Context.MODE_PRIVATE)

    fun schedule(text: String, triggerAtMillis: Long): Result<ReminderItem> = runCatching {
        require(text.isNotBlank()) { "Reminder text cannot be empty." }
        require(triggerAtMillis > System.currentTimeMillis()) { "Choose a time in the future." }
        val id = java.util.UUID.randomUUID().hashCode()
        val reminder = ReminderItem(id, text.trim(), triggerAtMillis)
        val intent = Intent(context, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", reminder.text)
        val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        val entries = (prefs.getStringSet("items", emptySet()) ?: emptySet()).toMutableSet()
        entries.add("$id|$triggerAtMillis|${reminder.text.replace("|", " ")}")
        prefs.edit().putStringSet("items", entries).apply()
        reminder
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
        prefs.edit().putStringSet("items", (prefs.getStringSet("items", emptySet()) ?: emptySet()).filterNot { it.startsWith("$id|") }.toSet()).apply()
    }
}
