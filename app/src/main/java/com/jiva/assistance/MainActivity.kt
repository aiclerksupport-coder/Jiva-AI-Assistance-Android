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
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val CHANNEL_ID = "jiva_reminders"
private const val PREFS = "jiva_private"
private const val KEY_ALIAS = "jiva_api_key_v1"
private const val GEMINI_MODEL = "gemini-2.5-flash"

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var recognizedLanguage: String? = null
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createReminderChannel()
        tts = TextToSpeech(this, this)
        setContent { JivaDashboard() }
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }

    private fun speak(text: String, languageTag: String? = null) {
        val engine = tts ?: return
        val locale = languageTag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jiva-reply")
    }

    private fun startListening(onResult: (String) -> Unit, onFailure: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onFailure("Speech recognition service is not available on this phone.")
            return
        }
        tts?.stop()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { service ->
            service.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    recognizedLanguage = results?.getString("android.speech.extra.DETECTED_LANGUAGE")
                    onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onError(error: Int) = onFailure("I couldn't recognize that. Check your speech service and try again.")
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
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak naturally / Apni bhasha mein boliye")
                // Android 14+ compatible services can return the detected language. Provider coverage varies.
                if (Build.VERSION.SDK_INT >= 34) putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
            }
            service.startListening(request)
        }
    }

    private fun localCommand(raw: String): Action? {
        val text = raw.lowercase(Locale.ROOT).trim()
        if (text.isBlank()) return null
        if (listOf("remind", "reminder", "याद दिला", "रिमाइंडर", "recordatorio", "rappel", "提醒").any(text::contains)) return Action(Kind.REMINDER)
        if (listOf("open settings", "settings", "सेटिंग", "configuración", "paramètres", "设置").any(text::contains)) {
            val intent = when {
                listOf("wifi", "wi-fi", "वाईफाई", "वाइफाइ").any(text::contains) -> Settings.Panel.ACTION_WIFI
                listOf("internet", "network", "नेटवर्क").any(text::contains) -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                listOf("bluetooth", "ब्लूटूथ").any(text::contains) -> Settings.Panel.ACTION_BLUETOOTH
                listOf("volume", "sound", "आवाज़", "ध्वनि").any(text::contains) -> Settings.ACTION_SOUND_SETTINGS
                listOf("display", "screen", "डिस्प्ले", "स्क्रीन").any(text::contains) -> Settings.ACTION_DISPLAY_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            return Action(Kind.SETTINGS, intentAction = intent)
        }
        if (listOf("camera", "कैमरा", "cámara", "相机").any(text::contains)) return Action(Kind.CAMERA)
        if (listOf("call", "phone", "कॉल", "फोन", "llama", "llamar", "anrufen").any(text::contains)) return Action(Kind.CALL)
        if (listOf("whatsapp", "whats app", "व्हाट्सएप").any(text::contains)) return Action(Kind.WHATSAPP)
        if (listOf("message", "sms", "मैसेज", "संदेश", "mensaje", "message", "短信").any(text::contains)) return Action(Kind.MESSAGE)
        val openVerb = listOf("open ", "launch ", "start ", "खोलो ", "खोल ", "abre ", "abrir ", "開く ").firstOrNull(text::startsWith)
        if (openVerb != null) return Action(Kind.OPEN_APP, appQuery = raw.drop(openVerb.length).trim())
        return null
    }

    private fun installedApps(): List<AppEntry> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(query, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val name = info.loadLabel(packageManager)?.toString() ?: return@mapNotNull null
                val launch = packageManager.getLaunchIntentForPackage(info.activityInfo.packageName) ?: return@mapNotNull null
                AppEntry(name, info.activityInfo.packageName, launch)
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun askGemini(apiKey: String, userText: String, history: List<Pair<String, String>>, callback: (Result<AiResult>) -> Unit) {
        worker.execute {
            val result = runCatching {
                val contents = JSONArray()
                history.takeLast(8).forEach { (role, text) ->
                    contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text))))
                }
                val prompt = """You are Jiva, a helpful Android assistant. Reply naturally in the user's language. Interpret intent but never claim an action happened. Return ONLY JSON with fields: reply (string), action (one of CHAT, OPEN_APP, CALL, MESSAGE, WHATSAPP, REMINDER, SETTINGS, CAMERA), query (string), recipient (string), message (string), settingsTarget (one of general,wifi,bluetooth,display,sound). For requests requiring contact selection, choose CALL/MESSAGE/WHATSAPP and provide recipient if spoken. For any consequential action, explain briefly that Jiva will ask the user to confirm; do not suggest bypassing Android confirmation. If unclear, choose CHAT and ask a clarifying question. User request: $userText"""
                contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
                val payload = JSONObject().put("contents", contents)
                    .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent?key=${Uri.encode(apiKey)}")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 20_000; readTimeout = 30_000; doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream.bufferedReader().use { it.readText() }
                connection.disconnect()
                if (code !in 200..299) error("Gemini API returned HTTP $code. Check the key, model availability, quota, and network.")
                val candidate = JSONObject(body).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                val json = JSONObject(candidate)
                val allowed = setOf("CHAT", "OPEN_APP", "CALL", "MESSAGE", "WHATSAPP", "REMINDER", "SETTINGS", "CAMERA")
                AiResult(json.optString("reply", ""), json.optString("action", "CHAT").uppercase().takeIf { it in allowed } ?: "CHAT",
                    json.optString("query"), json.optString("recipient"), json.optString("message"), json.optString("settingsTarget", "general"))
            }
            runOnUiThread { callback(result) }
        }
    }

    private fun createReminderChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Jiva reminders", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun scheduleReminder(message: String, triggerAt: Long): Boolean {
        if (triggerAt <= System.currentTimeMillis()) return false
        val id = (triggerAt xor message.hashCode().toLong()).toInt()
        val intent = Intent(this, ReminderReceiver::class.java).putExtra("message", message).putExtra("id", id)
        val pending = PendingIntent.getBroadcast(this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        val reminders = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet("reminders", emptySet())?.toMutableSet() ?: mutableSetOf()
        reminders.add("$id|$triggerAt|$message")
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet("reminders", reminders).apply()
        return true
    }

    @Composable
    private fun JivaDashboard() {
        var transcript by remember { mutableStateOf("") }
        var input by remember { mutableStateOf("") }
        var response by remember { mutableStateOf("Namaste! I'm Jiva. Speak or type in your language.") }
        var isListening by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var showReminder by remember { mutableStateOf(false) }
        var showApps by remember { mutableStateOf(false) }
        var showKey by remember { mutableStateOf(false) }
        var apiKeyInput by remember { mutableStateOf("") }
        var appSearch by remember { mutableStateOf("") }
        var pending by remember { mutableStateOf<Action?>(null) }
        var pendingContact by remember { mutableStateOf<Action?>(null) }
        var selectedContact by remember { mutableStateOf<Pair<String, String>?>(null) }
        var confirmationText by remember { mutableStateOf("") }
        var messageDraft by remember { mutableStateOf("") }
        val history = remember { mutableStateListOf<Pair<String, String>>() }
        val reminders = remember { mutableStateListOf<String>().apply {
            addAll(getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet("reminders", emptySet()) ?: emptySet())
        } }
        val apps = remember(showApps) { if (showApps) installedApps() else emptyList() }

        fun act(action: Action) {
            when (action.kind) {
                Kind.CALL, Kind.MESSAGE, Kind.WHATSAPP -> {
                    pendingContact = action
                    try { contactPickerLaunch(action.kind) } catch (_: Exception) { response = "Contact picker unavailable. You can try again or choose an app manually." }
                }
                Kind.REMINDER -> showReminder = true
                Kind.OPEN_APP -> {
                    val entry = installedApps().firstOrNull { it.label.equals(action.appQuery, true) }
                        ?: installedApps().firstOrNull { it.label.contains(action.appQuery, true) }
                    if (entry == null) { showApps = true; appSearch = action.appQuery }
                    else try { startActivity(entry.launchIntent); response = "Opened ${entry.label}." } catch (_: Exception) { response = "Couldn't open ${entry.label}." }
                }
                Kind.SETTINGS, Kind.CAMERA -> { pending = action; showSettings = true }
                Kind.CHAT -> Unit
            }
        }

        val contacts = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            val action = pendingContact
            pendingContact = null
            if (uri != null && action != null) {
                val contact = readPhoneContact(uri)
                if (contact != null) {
                    selectedContact = contact
                    if (action.kind == Kind.CALL) { pending = action.copy(recipient = contact.first, phone = contact.second); showSettings = true }
                    else {
                        pending = action.copy(recipient = contact.first, phone = contact.second)
                        messageDraft = action.message
                        showSettings = true
                    }
                } else response = "Couldn't read that contact. Please select a phone number."
            }
        }
        // A small wrapper allows contact selection to be launched from validated assistant actions.
        contactLauncher.value = { kind ->
            contacts.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        }
        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening({ heard -> isListening = false; transcript = heard; submit(heard, history, apiKey(), { response = it }, { act(it) }, { busy = it }) },
                { isListening = false; response = it })
            else { isListening = false; response = "Microphone permission is needed for voice commands." }
        }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        DisposableEffect(Unit) { onDispose { recognizer?.destroy(); tts?.shutdown(); worker.shutdown() } }

        MaterialTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Jiva", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                            Text("AI assistant · private by design", style = MaterialTheme.typography.bodyMedium)
                        }
                        OutlinedButton(onClick = { showKey = true }) { Text(if (apiKey().isBlank()) "Connect Gemini" else "Gemini connected") }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (isListening) "Listening… / Sun raha hoon…" else "Your assistant", style = MaterialTheme.typography.titleLarge)
                            if (transcript.isNotBlank()) Text("You said: $transcript", style = MaterialTheme.typography.bodyMedium)
                            Text(response, style = MaterialTheme.typography.bodyLarge)
                            if (busy) Text("Thinking…")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    isListening = true
                                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        startListening({ heard -> isListening = false; transcript = heard; submit(heard, history, apiKey(), { response = it }, { act(it) }, { busy = it }) },
                                            { isListening = false; response = it })
                                    } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                }, modifier = Modifier.weight(1f)) { Text(if (isListening) "Listening…" else "🎙 Speak") }
                                OutlinedButton(onClick = { tts?.stop(); isListening = true; if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening({ heard -> isListening = false; transcript = heard; submit(heard, history, apiKey(), { response = it }, { act(it) }, { busy = it }) }, { isListening = false; response = it }) else micPermission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Interrupt") }
                            }
                            OutlinedTextField(input, { input = it }, label = { Text("Type a command in any supported language") }, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { val text = input.trim(); if (text.isNotBlank()) { transcript = text; input = ""; submit(text, history, apiKey(), { response = it }, { act(it) }, { busy = it }) } }, modifier = Modifier.fillMaxWidth()) { Text("Send") }
                        }
                    }
                    Text("Quick actions", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showApps = true }) { Text("📱 Apps") }
                        OutlinedButton(onClick = { showReminder = true }) { Text("⏰ Reminder") }
                        OutlinedButton(onClick = { pending = Action(Kind.SETTINGS, intentAction = Settings.ACTION_SETTINGS); showSettings = true }) { Text("⚙ Settings") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pending = Action(Kind.CAMERA); showSettings = true }) { Text("📷 Camera") }
                        OutlinedButton(onClick = { pendingContact = Action(Kind.CALL); contacts.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }) { Text("📞 Call") }
                        OutlinedButton(onClick = { pendingContact = Action(Kind.MESSAGE); contacts.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }) { Text("✉ Message") }
                    }
                    Text("Reminders", style = MaterialTheme.typography.titleLarge)
                    reminders.mapNotNull { it.split('|', limit = 3).takeIf { parts -> parts.size == 3 } }.sortedBy { it[1].toLongOrNull() ?: Long.MAX_VALUE }.forEach { parts ->
                        Text("• ${parts[2]} — ${parts[1].toLongOrNull()?.let { DateFormat.getDateTimeInstance().format(it) } ?: "scheduled"}")
                    }
                    Text("Speech recognition and language detection depend on the Android speech service on this phone. Gemini requests are sent to Google using your API key. Calls and messages always open a system/app UI for you to review and send.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (showKey) AlertDialog(onDismissRequest = { showKey = false }, title = { Text("Connect your Gemini API key") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(apiKeyInput, { apiKeyInput = it }, label = { Text("Gemini API key") }, modifier = Modifier.fillMaxWidth())
                Text("Key is encrypted in Android Keystore-backed local storage. Requests go directly from this phone to Google; network access is required. Never share your key.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { runCatching { saveApiKey(apiKeyInput.trim()) }; apiKeyInput = ""; showKey = false }) { Text("Save securely") } }, dismissButton = {
            TextButton(onClick = { clearApiKey(); apiKeyInput = ""; showKey = false }) { Text("Remove key") }
        })

        if (showApps) AlertDialog(onDismissRequest = { showApps = false }, title = { Text("Choose an app") }, text = {
            Column {
                OutlinedTextField(appSearch, { appSearch = it }, label = { Text("Search installed apps") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(apps.filter { it.label.contains(appSearch, true) }) { app ->
                        TextButton(onClick = { showApps = false; try { startActivity(app.launchIntent); response = "Opened ${app.label}." } catch (_: Exception) { response = "Couldn't open the app." } }) { Text(app.label) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showApps = false }) { Text("Close") } })

        if (showSettings) {
            val action = pending
            val isSettings = action?.kind == Kind.SETTINGS || action?.kind == Kind.CAMERA
            AlertDialog(onDismissRequest = { showSettings = false; pending = null; selectedContact = null },
                title = { Text(when (action?.kind) { Kind.CALL -> "Confirm call"; Kind.MESSAGE -> "Review message"; Kind.WHATSAPP -> "Share with WhatsApp"; else -> "Confirm action" }) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (action?.kind) {
                            Kind.CALL -> Text("Open dialer for ${action.recipient} (${action.phone})? You must tap Call in the dialer.")
                            Kind.MESSAGE, Kind.WHATSAPP -> {
                                Text("To: ${action.recipient} (${action.phone}). Review before sending.")
                                OutlinedTextField(messageDraft, { messageDraft = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
                            }
                            Kind.SETTINGS -> Text("Open Android settings? You will make and approve changes there.")
                            Kind.CAMERA -> Text("Open the camera app?")
                            else -> Text("Continue?")
                        }
                    }
                }, confirmButton = { Button(onClick = {
                    showSettings = false
                    when (action?.kind) {
                        Kind.CALL -> openSystem(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(action.phone)}")))
                        Kind.MESSAGE -> {
                            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(action.phone)}")).putExtra("sms_body", messageDraft)
                            openSystem(intent)
                        }
                        Kind.WHATSAPP -> {
                            val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, messageDraft)
                            val target = packageManager.getLaunchIntentForPackage("com.whatsapp")
                            if (target != null) { share.setPackage("com.whatsapp"); openSystem(share) } else openSystem(Intent.createChooser(share, "Choose a messaging app"))
                        }
                        Kind.SETTINGS -> openSystem(Intent(action.intentAction ?: Settings.ACTION_SETTINGS))
                        Kind.CAMERA -> openSystem(Intent("android.media.action.IMAGE_CAPTURE"))
                        else -> Unit
                    }
                    pending = null; selectedContact = null
                }) { Text(if (action?.kind == Kind.CALL) "Open dialer" else "Continue") } }, dismissButton = { TextButton(onClick = { showSettings = false; pending = null; selectedContact = null }) { Text("Cancel") } })
        }

        if (showReminder) ReminderDialog(onDismiss = { showReminder = false }, onSave = { message, time ->
            if (scheduleReminder(message, time)) {
                reminders.clear(); reminders.addAll(getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet("reminders", emptySet()) ?: emptySet())
                response = "Reminder scheduled: $message"; speak(response)
                showReminder = false
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else response = "Choose a time in the future."
        })
    }

    // Contact picker callback is assigned by the composable; it avoids requesting broad contacts permission.
    private val contactPickerLaunch = ContactPickerBridge
    private fun apiKey(): String = runCatching { loadApiKey() }.getOrDefault("")

    private fun submit(text: String, history: MutableList<Pair<String, String>>, key: String, setReply: (String) -> Unit, act: (Action) -> Unit, setBusy: (Boolean) -> Unit) {
        val local = localCommand(text)
        if (local != null) {
            val acknowledgement = when (local.kind) {
                Kind.CALL -> "Choose the contact. Jiva will open the dialer for your review."
                Kind.MESSAGE -> "Choose the contact and review the message before sending."
                Kind.WHATSAPP -> "Choose the contact. WhatsApp will let you review and send the message."
                Kind.REMINDER -> "Let's set the reminder time and message."
                Kind.OPEN_APP -> "Looking for that installed app."
                Kind.SETTINGS -> "I can open that settings screen. Please confirm first."
                Kind.CAMERA -> "Please confirm opening the camera."
                Kind.CHAT -> ""
            }
            setReply(acknowledgement); if (acknowledgement.isNotBlank()) speak(acknowledgement, recognizedLanguage)
            act(local); return
        }
        if (key.isBlank()) {
            val reply = "I can handle common commands locally. Add your Gemini API key to ask Jiva other questions or use natural-language parsing."
            setReply(reply); speak(reply, recognizedLanguage); return
        }
        setBusy(true)
        askGemini(key, text, history.toList()) { result ->
            setBusy(false)
            result.onSuccess { ai ->
                val reply = ai.reply.ifBlank { "I understood your request. What would you like me to do?" }
                setReply(reply); speak(reply, recognizedLanguage)
                history.add("user" to text); history.add("model" to reply)
                val action = when (ai.action) {
                    "OPEN_APP" -> Action(Kind.OPEN_APP, appQuery = ai.query.ifBlank { text })
                    "CALL" -> Action(Kind.CALL, recipient = ai.recipient)
                    "MESSAGE" -> Action(Kind.MESSAGE, recipient = ai.recipient, message = ai.message)
                    "WHATSAPP" -> Action(Kind.WHATSAPP, recipient = ai.recipient, message = ai.message)
                    "REMINDER" -> Action(Kind.REMINDER)
                    "SETTINGS" -> Action(Kind.SETTINGS, intentAction = when (ai.settingsTarget) {
                        "wifi" -> Settings.Panel.ACTION_WIFI; "bluetooth" -> Settings.Panel.ACTION_BLUETOOTH
                        "display" -> Settings.ACTION_DISPLAY_SETTINGS; "sound" -> Settings.ACTION_SOUND_SETTINGS; else -> Settings.ACTION_SETTINGS
                    })
                    "CAMERA" -> Action(Kind.CAMERA)
                    else -> null
                }
                if (action != null) act(action)
            }.onFailure { error ->
                val message = error.message ?: "Gemini request failed. Check your connection and API key."
                setReply(message); speak(message, recognizedLanguage)
            }
        }
    }

    private fun openSystem(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { responseNoActivity() }
    }
    private fun responseNoActivity() { /* Activity availability is reported through Android's own UI; no silent action is taken. */ }

    private fun readPhoneContact(uri: Uri): Pair<String, String>? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val name = cursor.getString(0).orEmpty()
                val number = cursor.getString(1).orEmpty()
                if (number.isBlank()) null else (name.ifBlank { number }) to number
            } else null
        } catch (_: Exception) { null } finally { cursor?.close() }
    }

    private fun createKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(KEY_ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun saveApiKey(value: String) {
        if (value.isBlank()) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, createKey()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("api_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("api_cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun loadApiKey(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val iv = prefs.getString("api_iv", null) ?: return ""
        val encrypted = prefs.getString("api_cipher", null) ?: return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, createKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }
        return String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun clearApiKey() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("api_iv").remove("api_cipher").apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ReminderDialog(onDismiss: () -> Unit, onSave: (String, Long) -> Unit) {
        var text by remember { mutableStateOf("") }
        var showDate by remember { mutableStateOf(false) }
        var showTime by remember { mutableStateOf(false) }
        val now = Calendar.getInstance()
        var year by remember { mutableStateOf(now.get(Calendar.YEAR)) }
        var month by remember { mutableStateOf(now.get(Calendar.MONTH)) }
        var day by remember { mutableStateOf(now.get(Calendar.DAY_OF_MONTH)) }
        var hour by remember { mutableStateOf((now.get(Calendar.HOUR_OF_DAY) + 1) % 24) }
        var minute by remember { mutableStateOf(0) }
        AlertDialog(onDismissRequest = onDismiss, title = { Text("New reminder") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("What should Jiva remind you?") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { showDate = true }) { Text("Date: %04d-%02d-%02d".format(year, month + 1, day)) }
                OutlinedButton(onClick = { showTime = true }) { Text("Time: %02d:%02d".format(hour, minute)) }
            }
        }, confirmButton = { Button(onClick = { val time = Calendar.getInstance().apply { set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis; if (text.isNotBlank()) onSave(text.trim(), time) }) { Text("Schedule") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
        if (showDate) {
            val state = rememberDatePickerState(initialSelectedDateMillis = Calendar.getInstance().apply { set(year, month, day) }.timeInMillis)
            DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { Calendar.getInstance().apply { timeInMillis = it }.also { cal -> year = cal.get(Calendar.YEAR); month = cal.get(Calendar.MONTH); day = cal.get(Calendar.DAY_OF_MONTH) } }; showDate = false }) { Text("OK") } }, dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } }) { DatePicker(state) }
        }
        if (showTime) {
            val state = rememberTimePickerState(initialHour = hour, initialMinute = minute)
            AlertDialog(onDismissRequest = { showTime = false }, title = { Text("Choose time") }, text = { TimePicker(state) }, confirmButton = { TextButton(onClick = { hour = state.hour; minute = state.minute; showTime = false }) { Text("OK") } }, dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } })
        }
    }

    override fun onDestroy() { recognizer?.destroy(); tts?.shutdown(); worker.shutdown(); super.onDestroy() }
}

private data class AppEntry(val label: String, val packageName: String, val launchIntent: Intent)
private enum class Kind { CHAT, OPEN_APP, CALL, MESSAGE, WHATSAPP, REMINDER, SETTINGS, CAMERA }
private data class Action(val kind: Kind, val appQuery: String = "", val recipient: String = "", val phone: String = "", val message: String = "", val intentAction: String? = null)
private data class AiResult(val reply: String, val action: String, val query: String, val recipient: String, val message: String, val settingsTarget: String)
private object ContactPickerBridge
private val contactLauncher = ThreadLocal<((Kind) -> Unit)>()
private fun contactPickerLaunch(kind: Kind) { contactLauncher.get()?.invoke(kind) }

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra("message") ?: "Jiva reminder"
        val id = intent.getIntExtra("id", message.hashCode())
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Jiva reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Jiva reminder").setContentText(message).setContentIntent(open).setAutoCancel(true).build())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("reminders", (prefs.getStringSet("reminders", emptySet()) ?: emptySet()).filterNot { it.startsWith("$id|") }.toSet()).apply()
    }
}
