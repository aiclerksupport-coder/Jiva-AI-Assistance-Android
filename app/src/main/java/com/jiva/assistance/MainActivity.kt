package com.jiva.assistance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiva.assistance.domain.AssistantAction
import com.jiva.assistance.domain.IntentPlan
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var speech: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var detectedLanguage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent { JivaApp() }
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }

    private fun listen(onText: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { onError("Speech recognition is not available on this phone."); return }
        speech?.destroy()
        speech = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    detectedLanguage = results?.getString("android.speech.extra.DETECTED_LANGUAGE")
                    onText(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onError(error: Int) = onError("Speech recognition failed. Check the phone's speech service and try again.")
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
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak naturally to Jiva")
                if (android.os.Build.VERSION.SDK_INT >= 34) putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
            }
            recognizer.startListening(request)
        }
    }

    @androidx.compose.runtime.Composable
    private fun JivaApp(vm: AssistantViewModel = viewModel()) {
        val state by vm.state.collectAsState()
        var showKey by remember { mutableStateOf(false) }
        var keyText by remember { mutableStateOf("") }
        var appQuery by remember { mutableStateOf("") }
        var showApps by remember { mutableStateOf(false) }
        var appItems by remember { mutableStateOf(emptyList<Pair<String, Intent>>()) }
        val reminderScheduler = remember { ReminderScheduler(this) }

        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (allowed) listen({ vm.submit(it) }, { vm.setInput(it) })
        }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@rememberLauncherForActivityResult
            val plan = vm.state.value.pendingPlan ?: return@rememberLauncherForActivityResult
            val contact = contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() to cursor.getString(1).orEmpty() else null
            }
            if (contact != null && contact.second.isNotBlank()) vm.updatePlan(plan.copy(contactName = contact.first, phoneNumber = contact.second))
        }

        MaterialTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Jiva", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                            Text("Your assistant · user-controlled", style = MaterialTheme.typography.bodyMedium)
                        }
                        OutlinedButton(onClick = { showKey = true }) { Text("Gemini key") }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (state.busy) "Jiva is thinking…" else "Talk or type to Jiva", style = MaterialTheme.typography.titleLarge)
                            if (state.transcript.isNotBlank()) Text("You: ${state.transcript}")
                            Text(state.reply, style = MaterialTheme.typography.bodyLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen({ vm.submit(it) }, { vm.setInput(it) })
                                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                }, modifier = Modifier.weight(1f)) { Text("🎙 Speak") }
                                OutlinedButton(onClick = { tts?.stop(); speech?.cancel() }) { Text("Stop") }
                            }
                            OutlinedTextField(value = state.input, onValueChange = vm::setInput, label = { Text("Type in your language") }, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { vm.submit() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Send") }
                        }
                    }
                    Text("Quick actions", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { appItems = queryApps(); showApps = true }) { Text("Apps") }
                        OutlinedButton(onClick = vm::showReminderEditor) { Text("Reminders") }
                        OutlinedButton(onClick = { vm.updatePlan(IntentPlan(AssistantAction.OPEN_SETTINGS, "Open settings?", settingsTarget = "general")) }) { Text("Settings") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.updatePlan(IntentPlan(AssistantAction.CALL, "Choose a contact to call.")) }) { Text("Call") }
                        OutlinedButton(onClick = { vm.updatePlan(IntentPlan(AssistantAction.SMS, "Choose a contact and write a message.")) }) { Text("Message") }
                        OutlinedButton(onClick = { vm.updatePlan(IntentPlan(AssistantAction.CAMERA, "Open camera?")) }) { Text("Camera") }
                    }
                    Text("Upcoming reminders", style = MaterialTheme.typography.titleLarge)
                    state.reminders.forEach { Text("• ${it.text} — ${java.text.DateFormat.getDateTimeInstance().format(it.triggerAtMillis)}") }
                    Text("Speech languages depend on your phone's speech service. Gemini requests send text to Google using your key. Sensitive actions require your review.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        state.pendingPlan?.let { plan ->
            ActionConfirmationDialog(plan = plan, onDismiss = vm::dismissPlan, onPickContact = {
                contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            }, onConfirm = { confirmed ->
                vm.dismissPlan()
                when (confirmed.action) {
                    AssistantAction.CALL -> launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(confirmed.phoneNumber.orEmpty())}")))
                    AssistantAction.SMS -> launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(confirmed.phoneNumber.orEmpty())}")).putExtra("sms_body", confirmed.message.orEmpty()))
                    AssistantAction.WHATSAPP_SHARE -> launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, confirmed.message.orEmpty()), "Choose WhatsApp or another app"))
                    AssistantAction.OPEN_SETTINGS -> launch(settingsIntent(confirmed.settingsTarget))
                    AssistantAction.CAMERA -> launch(Intent("android.media.action.IMAGE_CAPTURE"))
                    AssistantAction.OPEN_APP -> {
                        val match = queryApps().firstOrNull { it.first.equals(confirmed.appName, true) }
                            ?: queryApps().firstOrNull { it.first.contains(confirmed.appName.orEmpty(), true) }
                        if (match != null) launch(match.second) else { appQuery = confirmed.appName.orEmpty(); appItems = queryApps(); showApps = true }
                    }
                    AssistantAction.REMINDER -> vm.showReminderEditor()
                    AssistantAction.CHAT -> Unit
                }
            })
        }

        if (showKey) AlertDialog(onDismissRequest = { showKey = false }, title = { Text("Connect Gemini to Jiva") }, text = {
            Column {
                OutlinedTextField(keyText, { keyText = it }, label = { Text("Your Gemini API key") }, modifier = Modifier.fillMaxWidth())
                Text("Encrypted on this device with Android Keystore. Requests go to Google. A compromised device can expose client-side keys.")
            }
        }, confirmButton = { TextButton(onClick = { runCatching { com.jiva.assistance.data.ApiKeyStore(this).save(keyText.trim()) }; keyText = ""; showKey = false }) { Text("Save") } }, dismissButton = {
            TextButton(onClick = { com.jiva.assistance.data.ApiKeyStore(this).clear(); keyText = ""; showKey = false }) { Text("Remove key") }
        })

        if (showApps) AlertDialog(onDismissRequest = { showApps = false }, title = { Text("Choose an installed app") }, text = {
            Column {
                OutlinedTextField(appQuery, { appQuery = it }, label = { Text("Search apps") }, modifier = Modifier.fillMaxWidth())
                LazyColumn {
                    items(appItems.filter { it.first.contains(appQuery, ignoreCase = true) }) { item ->
                        TextButton(onClick = { showApps = false; launch(item.second) }) { Text(item.first) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showApps = false }) { Text("Close") } })

        if (state.showReminderEditor) ReminderEditorDialog(onDismiss = vm::closeReminderEditor, onSave = { text, time ->
            reminderScheduler.schedule(text, time).onSuccess { reminder ->
                vm.setReminders(reminderScheduler.list()); vm.closeReminderEditor()
                if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }.onFailure { vm.updatePlan(null) }
        })

        LaunchedEffect(Unit) { vm.setReminders(reminderScheduler.list()) }
    }

    private fun queryApps(): List<Pair<String, Intent>> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(query, PackageManager.MATCH_ALL).mapNotNull { info ->
            val launch = packageManager.getLaunchIntentForPackage(info.activityInfo.packageName) ?: return@mapNotNull null
            info.loadLabel(packageManager).toString() to launch
        }.distinctBy { it.first }.sortedBy { it.first.lowercase(Locale.getDefault()) }
    }

    private fun settingsIntent(target: String?): Intent = when (target) {
        "wifi" -> Intent(Settings.Panel.ACTION_WIFI)
        "bluetooth" -> Intent(Settings.Panel.ACTION_BLUETOOTH)
        "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
        else -> Intent(Settings.ACTION_SETTINGS)
    }

    private fun launch(intent: Intent) { runCatching { startActivity(intent) } }

    override fun onDestroy() { speech?.destroy(); tts?.shutdown(); super.onDestroy() }
}
