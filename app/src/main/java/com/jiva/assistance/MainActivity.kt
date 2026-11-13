package com.jiva.assistance

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale

private const val REMINDER_CHANNEL = "jiva_reminders"
private const val REMINDER_PREFS = "jiva_reminders"

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var onRecognized: ((String) -> Unit)? = null
    private var onListeningError: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createReminderChannel()
        tts = TextToSpeech(this, this)
        setContent { JivaScreen() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
    }

    private fun speak(text: String, language: Locale = Locale.getDefault()) {
        val engine = tts ?: return
        val available = engine.isLanguageAvailable(language)
        if (available >= TextToSpeech.LANG_AVAILABLE) engine.language = language
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jiva-reply")
    }

    private fun listen(onResult: (String) -> Unit, onError: () -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onResult("Speech recognition is not available on this device. Please install or enable a speech recognition service.")
            onError()
            return
        }
        onRecognized = onResult
        onListeningError = onError
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    onRecognized?.invoke(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onError(error: Int) { onListeningError?.invoke() }
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // Ask compatible recognition services to detect the spoken language. Actual support varies by device/service.
                if (Build.VERSION.SDK_INT >= 34) {
                    putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
                }
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak naturally / Apni bhasha mein boliye")
            }
            speech.startListening(request)
        }
    }

    private fun replyLocale(text: String): Locale {
        val script = text.codePoints().toArray().firstOrNull { Character.UnicodeScript.of(it) != Character.UnicodeScript.COMMON }
            ?.let { Character.UnicodeScript.of(it) }
        return when (script) {
            Character.UnicodeScript.DEVANAGARI -> Locale.forLanguageTag("hi-IN")
            Character.UnicodeScript.ARABIC -> Locale.forLanguageTag("ar")
            Character.UnicodeScript.BENGALI -> Locale.forLanguageTag("bn-BD")
            Character.UnicodeScript.TAMIL -> Locale.forLanguageTag("ta-IN")
            Character.UnicodeScript.TELUGU -> Locale.forLanguageTag("te-IN")
            Character.UnicodeScript.GUJARATI -> Locale.forLanguageTag("gu-IN")
            Character.UnicodeScript.GURMUKHI -> Locale.forLanguageTag("pa-IN")
            Character.UnicodeScript.KANNADA -> Locale.forLanguageTag("kn-IN")
            Character.UnicodeScript.MALAYALAM -> Locale.forLanguageTag("ml-IN")
            Character.UnicodeScript.THAI -> Locale.forLanguageTag("th-TH")
            Character.UnicodeScript.HAN -> Locale.SIMPLIFIED_CHINESE
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Locale.JAPANESE
            else -> Locale.getDefault()
        }
    }

    private fun installedApps(): List<AppEntry> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(query, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val label = info.loadLabel(packageManager)?.toString() ?: return@mapNotNull null
                val launch = packageManager.getLaunchIntentForPackage(info.activityInfo.packageName) ?: return@mapNotNull null
                AppEntry(label, info.activityInfo.packageName, launch)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun runCommand(text: String, onReply: (String) -> Unit, onOpenReminders: () -> Unit, onOpenApps: () -> Unit) {
        val command = text.trim().lowercase(Locale.ROOT)
        val reply = when {
            command.contains("remind") || command.contains("reminder") || command.contains("याद") || command.contains("रिमाइंड") || command.contains("recordatorio") -> {
                onOpenReminders(); "Reminder banane ke liye time aur message choose karein."
            }
            command.contains("open app") || command.contains("apps") || command.contains("ऐप") || command.contains("app kholo") -> {
                onOpenApps(); "Installed apps dikhata hoon. App choose karke khol sakte hain."
            }
            command.contains("settings") || command.contains("setting") || command.contains("सेटिंग") || command.contains("configuración") -> {
                startActivity(Intent(Settings.ACTION_SETTINGS)); "Opening Settings."
            }
            command.contains("camera") || command.contains("कैमरा") || command.contains("cámara") -> {
                openIntent(Intent("android.media.action.IMAGE_CAPTURE")); "Opening the camera."
            }
            command.contains("call") || command.contains("फोन") || command.contains("कॉल") || command.contains("llama") -> {
                val number = Regex("[+]?[- 0-9]{6,}").find(text)?.value?.trim()
                if (number.isNullOrBlank()) "Please say a phone number. Jiva opens the dialer so you can review and place the call."
                else {
                    openIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
                    "Dialer opened. Check the number and place the call yourself."
                }
            }
            command.contains("message") || command.contains("sms") || command.contains("मैसेज") || command.contains("संदेश") -> {
                openIntent(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
                "Your messaging app is open. Review and send your message there."
            }
            else -> {
                val appName = text.replace(Regex("(?i)\\b(open|launch|start|खोलो|खोल|abre|abrir)\\b"), "").trim()
                val app = installedApps().firstOrNull { it.label.equals(appName, ignoreCase = true) }
                    ?: installedApps().firstOrNull { appName.isNotBlank() && it.label.contains(appName, ignoreCase = true) }
                if (app != null) { openIntent(app.launchIntent); "Opening ${app.label}." }
                else "I didn't understand that command yet. Try opening an installed app, Settings, Camera, or ask to set a reminder."
            }
        }
        onReply(reply)
        speak(reply, replyLocale(text))
    }

    private fun openIntent(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { /* Explain unavailable action in future UI. */ }
    }

    private fun createReminderChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL, "Jiva reminders", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun scheduleReminder(message: String, triggerAt: Long): Boolean {
        if (triggerAt <= System.currentTimeMillis()) return false
        val id = (triggerAt xor message.hashCode().toLong()).toInt()
        val intent = Intent(this, ReminderReceiver::class.java).putExtra("message", message).putExtra("id", id)
        val pending = PendingIntent.getBroadcast(this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = getSystemService(AlarmManager::class.java)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        val prefs = getSharedPreferences(REMINDER_PREFS, MODE_PRIVATE)
        prefs.edit().putStringSet("items", (prefs.getStringSet("items", emptySet()) ?: emptySet()) + "$id|$triggerAt|$message").apply()
        return true
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun JivaScreen() {
        var transcript by remember { mutableStateOf("") }
        var response by remember { mutableStateOf("Namaste! I’m Jiva. Tap the mic and speak naturally.") }
        var listening by remember { mutableStateOf(false) }
        var showReminder by remember { mutableStateOf(false) }
        var showApps by remember { mutableStateOf(false) }
        var reminderText by remember { mutableStateOf("") }
        var appSearch by remember { mutableStateOf("") }
        val scheduled = remember { mutableStateListOf<String>().apply { addAll(getSharedPreferences(REMINDER_PREFS, MODE_PRIVATE).getStringSet("items", emptySet()) ?: emptySet()) } }
        val apps = remember(showApps) { if (showApps) installedApps() else emptyList() }

        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) listen({ phrase ->
                listening = false
                transcript = phrase
                if (phrase.isBlank()) response = "I didn't catch that. Please try again."
                else runCommand(phrase, { response = it }, { showReminder = true }, { showApps = true })
            }, { listening = false; response = "Speech recognition failed. Check that a speech service is available and try again." })
            else response = "Microphone permission is required for voice input. You can enable it in Android Settings."
        }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* A reminder can still be scheduled; notifications may be hidden until allowed. */ }

        DisposableEffect(Unit) { onDispose { recognizer?.destroy(); tts?.shutdown() } }

        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Jiva", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    Text("AI Assistance · speak in your language", style = MaterialTheme.typography.titleMedium)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (listening) "Listening… / Sun raha hoon…" else "Your assistant", style = MaterialTheme.typography.titleMedium)
                            Text("You: ${transcript.ifBlank { "Tap the mic and say a command" }}")
                            Text(response)
                            Button(onClick = {
                                listening = true
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    listen({ phrase ->
                                        listening = false; transcript = phrase
                                        if (phrase.isBlank()) response = "I didn't catch that. Please try again."
                                        else runCommand(phrase, { response = it }, { showReminder = true }, { showApps = true })
                                    }, { listening = false; response = "Speech recognition failed. Check your device speech service and try again." })
                                } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }, modifier = Modifier.fillMaxWidth()) { Text(if (listening) "Listening…" else "🎙  Speak to Jiva") }
                        }
                    }
                    Text("Quick actions", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showApps = true }) { Text("📱 Apps") }
                        OutlinedButton(onClick = { showReminder = true }) { Text("⏰ Reminder") }
                        OutlinedButton(onClick = { openIntent(Intent(Settings.ACTION_SETTINGS)) }) { Text("⚙ Settings") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { openIntent(Intent("android.media.action.IMAGE_CAPTURE")) }) { Text("📷 Camera") }
                        OutlinedButton(onClick = { openIntent(Intent(Intent.ACTION_DIAL)) }) { Text("📞 Dialer") }
                        OutlinedButton(onClick = { openIntent(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))) }) { Text("✉ Messages") }
                    }
                    Text("Upcoming reminders", style = MaterialTheme.typography.titleLarge)
                    val upcoming = scheduled.mapNotNull { it.split('|', limit = 3).takeIf { bits -> bits.size == 3 } }
                        .sortedBy { it[1].toLongOrNull() ?: Long.MAX_VALUE }
                    if (upcoming.isEmpty()) Text("No reminders scheduled yet.")
                    upcoming.forEach { bits ->
                        val whenText = bits[1].toLongOrNull()?.let { DateFormat.getDateTimeInstance().format(it) } ?: "Scheduled"
                        OutlinedButton(onClick = {}) { Text("${bits[2]} · $whenText") }
                    }
                    Text("Language detection depends on the speech service installed on your device. Jiva asks compatible services to detect language; spoken replies use the detected script when supported by your text-to-speech engine. Some devices may use their default language or require an internet connection.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (showApps) AlertDialog(
            onDismissRequest = { showApps = false },
            title = { Text("Open an installed app") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(appSearch, { appSearch = it }, label = { Text("Search apps") }, modifier = Modifier.fillMaxWidth())
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(apps.filter { it.label.contains(appSearch, ignoreCase = true) }) { app ->
                            TextButton(onClick = { showApps = false; openIntent(app.launchIntent); response = "Opening ${app.label}." }) {
                                Text(app.label, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showApps = false }) { Text("Close") } }
        )

        if (showReminder) ReminderDialog(
            initialText = reminderText,
            onTextChange = { reminderText = it },
            onDismiss = { showReminder = false },
            onSave = { message, time ->
                if (scheduleReminder(message, time)) {
                    scheduled.clear()
                    scheduled.addAll(getSharedPreferences(REMINDER_PREFS, MODE_PRIVATE).getStringSet("items", emptySet()) ?: emptySet())
                    response = "Reminder scheduled: $message"
                    speak(response, Locale.getDefault())
                    showReminder = false
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else response = "Choose a time in the future."
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ReminderDialog(initialText: String, onTextChange: (String) -> Unit, onDismiss: () -> Unit, onSave: (String, Long) -> Unit) {
        var message by remember { mutableStateOf(initialText) }
        var showDatePicker by remember { mutableStateOf(false) }
        var showTimePicker by remember { mutableStateOf(false) }
        val now = Calendar.getInstance()
        var year by remember { mutableStateOf(now.get(Calendar.YEAR)) }
        var month by remember { mutableStateOf(now.get(Calendar.MONTH)) }
        var day by remember { mutableStateOf(now.get(Calendar.DAY_OF_MONTH)) }
        var hour by remember { mutableStateOf((now.get(Calendar.HOUR_OF_DAY) + 1) % 24) }
        var minute by remember { mutableStateOf(0) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("New reminder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(message, { message = it; onTextChange(it) }, label = { Text("What should Jiva remind you?") }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { showDatePicker = true }) { Text("Date: %04d-%02d-%02d".format(year, month + 1, day)) }
                    OutlinedButton(onClick = { showTimePicker = true }) { Text("Time: %02d:%02d".format(hour, minute)) }
                }
            },
            confirmButton = { Button(onClick = {
                val cal = Calendar.getInstance().apply { set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }
                if (message.isNotBlank()) onSave(message.trim(), cal.timeInMillis)
            }) { Text("Schedule") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
        if (showDatePicker) {
            val state = rememberDatePickerState(initialSelectedDateMillis = Calendar.getInstance().apply { set(year, month, day) }.timeInMillis)
            DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { selected -> Calendar.getInstance().apply { timeInMillis = selected }.also { year = it.get(Calendar.YEAR); month = it.get(Calendar.MONTH); day = it.get(Calendar.DAY_OF_MONTH) } }
                showDatePicker = false
            }) { Text("OK") } }, dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }) { DatePicker(state) }
        }
        if (showTimePicker) {
            val state = rememberTimePickerState(initialHour = hour, initialMinute = minute)
            AlertDialog(onDismissRequest = { showTimePicker = false }, title = { Text("Choose time") }, text = { TimePicker(state) }, confirmButton = { TextButton(onClick = { hour = state.hour; minute = state.minute; showTimePicker = false }) { Text("OK") } }, dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } })
        }
    }
}

data class AppEntry(val label: String, val packageName: String, val launchIntent: Intent)

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra("message") ?: "Jiva reminder"
        val id = intent.getIntExtra("id", message.hashCode())
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL, "Jiva reminders", NotificationManager.IMPORTANCE_HIGH))
        }
        val openApp = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Jiva reminder")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        manager.notify(id, notification)
        val prefs = context.getSharedPreferences(REMINDER_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("items", (prefs.getStringSet("items", emptySet()) ?: emptySet()).filterNot { it.startsWith("$id|") }.toSet()).apply()
    }
}
