package com.jiva.assistance

import android.Manifest
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiva.assistance.domain.AssistantAction
import com.jiva.assistance.domain.IntentPlan
import com.jiva.assistance.domain.VoicePresets
import com.jiva.assistance.data.WakeWordModelImporter
import com.jiva.assistance.data.ProcessedAudioInput
import com.jiva.assistance.platform.contacts.ContactPicker
import com.jiva.assistance.accessibility.JivaAccessibilityService
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var speech: SpeechRecognizer? = null
    private var processedAudioInput: ProcessedAudioInput? = null
    private var cancellingSpeech = false
    private var tts: TextToSpeech? = null
    private var detectedLanguage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent { JivaApp() }
    }

    private suspend fun speakFallbackGreeting() {
        val engine = tts ?: throw IllegalStateException("Text-to-speech is unavailable.")
        val completed = CompletableDeferred<Unit>()
        val utteranceId = "jiva_wake_greeting"
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) {
                if (id == utteranceId) completed.complete(Unit)
            }
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    completed.completeExceptionally(IllegalStateException("Text-to-speech could not speak the greeting."))
                }
            }
        })
        check(engine.speak("Ji, boliye", TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS) {
            "Text-to-speech could not start the greeting."
        }
        withTimeout(10_000) { completed.await() }
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }

    private fun listen(
        onText: (String) -> Unit,
        onError: (String) -> Unit,
        patientListening: Boolean,
        noiseProcessing: Boolean,
        onAudioStatus: (String) -> Unit,
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { onError("Speech recognition is not available on this phone."); return }
        cancellingSpeech = true
        speech?.destroy()
        processedAudioInput?.close()
        processedAudioInput = null
        var captureUnavailableReason: String? = null
        val input = if (noiseProcessing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                ProcessedAudioInput.open()
            } catch (error: Exception) {
                captureUnavailableReason = error.message ?: "device limitation"
                null
            }
        } else {
            null
        }
        processedAudioInput = input
        if (input == null && noiseProcessing) {
            val reason = captureUnavailableReason?.let { " ($it)" }.orEmpty()
            onAudioStatus("Using the phone's speech recognizer. Enhanced microphone processing requires Android 13+ and device support$reason.")
        } else if (input != null) {
            onAudioStatus(input.status)
        } else {
            onAudioStatus("Phone speech recognition microphone processing is in use.")
        }
        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (error: Exception) {
            processedAudioInput?.close()
            processedAudioInput = null
            onError("Could not create speech recognition: ${error.message ?: "service unavailable"}")
            return
        }
        speech = recognizer
        cancellingSpeech = false
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (speech !== recognizer) return
                processedAudioInput?.close()
                processedAudioInput = null
                if (cancellingSpeech) {
                    cancellingSpeech = false
                    return
                }
                detectedLanguage = results?.getString("android.speech.extra.DETECTED_LANGUAGE")
                onText(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onError(error: Int) {
                if (speech !== recognizer) return
                processedAudioInput?.close()
                processedAudioInput = null
                if (cancellingSpeech) {
                    cancellingSpeech = false
                    return
                }
                if (input != null) {
                    onAudioStatus("The speech service rejected enhanced microphone audio; retrying with standard phone speech recognition.")
                    recognizer.destroy()
                    speech = null
                    listen(onText, onError, patientListening, false, onAudioStatus)
                } else {
                    onError("Speech recognition failed. Check the phone's speech service and try again.")
                }
            }
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
            if (patientListening) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4_500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_500L)
            }
            if (input != null) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, input.descriptor)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, ProcessedAudioInput.SAMPLE_RATE)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, ProcessedAudioInput.CHANNEL_COUNT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, ProcessedAudioInput.ENCODING)
            }
            if (android.os.Build.VERSION.SDK_INT >= 34) putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
        }
        if (input != null) {
            try {
                input.start()
            } catch (error: Exception) {
                processedAudioInput?.close()
                processedAudioInput = null
                request.removeExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE)
                request.removeExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE)
                request.removeExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT)
                request.removeExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING)
                onAudioStatus("Enhanced microphone unavailable (${error.message ?: "device limitation"}); using standard speech recognition.")
            }
        }
        try {
            recognizer.startListening(request)
        } catch (error: Exception) {
            processedAudioInput?.close()
            processedAudioInput = null
            recognizer.destroy()
            speech = null
            onError("Could not start speech recognition: ${error.message ?: "microphone unavailable"}")
        }
    }

    @androidx.compose.runtime.Composable
    private fun JivaApp(vm: AssistantViewModel = viewModel()) {
        val state by vm.state.collectAsState()
        var showKey by remember { mutableStateOf(false) }
        var showLegal by remember { mutableStateOf(false) }
        var showWakeSettings by remember { mutableStateOf(false) }
        var showScreenReadWarning by remember { mutableStateOf(false) }
        var screenText by remember { mutableStateOf("") }
        var screenTarget by remember { mutableStateOf("") }
        var screenAssistError by remember { mutableStateOf("") }
        var pendingScreenClick by remember { mutableStateOf("") }
        var sensitiveClickReviewed by remember { mutableStateOf(false) }
        var keyText by remember { mutableStateOf("") }
        var picovoiceKeyText by remember { mutableStateOf("") }
        var keyError by remember { mutableStateOf("") }
        var wakeSetupError by remember { mutableStateOf("") }
        var testingKey by remember { mutableStateOf(false) }
        var keyVerified by remember { mutableStateOf(false) }
        var pendingWakeModelName by remember { mutableStateOf("jiva") }
        var wakeModels by remember { mutableStateOf(vm.wakeWordModels()) }
        var wakeServiceActive by remember { mutableStateOf(vm.wakeWordServiceEnabled()) }
        var wakeStartPending by remember { mutableStateOf(false) }
        var wakeImportPending by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()
        var appQuery by remember { mutableStateOf("") }
        var showApps by remember { mutableStateOf(false) }
        var showVoiceMenu by remember { mutableStateOf(false) }
        var appItems by remember { mutableStateOf(emptyList<Pair<String, Intent>>()) }
        val reminderScheduler = remember { ReminderScheduler(this) }
        var reminderSavePending by remember { mutableStateOf(false) }
        var greetAfterPermission by remember { mutableStateOf(false) }
        var waitingForWakePause by remember { mutableStateOf(false) }
        var greetAfterWakePause by remember { mutableStateOf(false) }

        suspend fun playWakeGreeting() {
            vm.wakeFromKeyword()
            if (!vm.hasApiKey()) {
                try {
                    speakFallbackGreeting()
                } catch (error: Exception) {
                    vm.showReply("Voice greeting failed: ${error.message ?: "text-to-speech is unavailable."}")
                    vm.setListening(true)
                }
            }
        }

        fun startWakeService() {
            try {
                check(vm.wakeWordConfigured()) {
                    "Save a Picovoice AccessKey and import both Jiva and Giva .ppn models first."
                }
                vm.setWakeWordServiceEnabled(true)
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, WakeWordService::class.java),
                )
                wakeServiceActive = true
                wakeSetupError = ""
            } catch (error: Exception) {
                vm.setWakeWordServiceEnabled(false)
                wakeSetupError = error.message ?: "Could not start wake-word listening."
            }
        }

        fun pauseWakeService() {
            if (vm.wakeWordServiceEnabled()) {
                startService(
                    Intent(this@MainActivity, WakeWordService::class.java)
                        .setAction(WakeWordService.ACTION_PAUSE),
                )
            }
        }

        fun runScreenAction(action: String) {
            try {
                pauseWakeService()
                val service = com.jiva.assistance.accessibility.JivaAccessibilityService.instance
                if (service == null) {
                    screenAssistError = "Enable Jiva screen assistance in Android Accessibility Settings, then return here."
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return
                }
                service.queueScreenAction(action)
                vm.setScreenAssistPending(true)
                screenText = ""
                screenAssistError = "Switch to the target app within 30 seconds."
            } catch (error: Exception) {
                screenAssistError = error.message ?: "Screen action failed."
            }
        }

        fun clickScreenTarget() {
            val target = pendingScreenClick
            try {
                val service = com.jiva.assistance.accessibility.JivaAccessibilityService.instance
                if (service == null) {
                    screenAssistError = "Enable Jiva screen assistance in Android Accessibility Settings, then return here."
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    service.queueVisibleTextClick(target)
                    vm.setScreenAssistPending(true)
                    screenText = ""
                    screenAssistError = "Switch to the target app within 30 seconds."
                }
            } catch (error: Exception) {
                screenAssistError = error.message ?: "The visible control could not be clicked."
            } finally {
                pendingScreenClick = ""
                sensitiveClickReviewed = false
            }
        }

        fun beginVoiceCapture(greet: Boolean, pauseWakeWord: Boolean = true) {
            if (!state.enabled) return
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                vm.setListening(false)
                vm.showReply("Microphone permission is required for voice commands.")
                return
            }
            vm.setListening(true)
            if (pauseWakeWord && vm.wakeWordServiceEnabled()) {
                waitingForWakePause = true
                greetAfterWakePause = greet
                try {
                    startService(
                        Intent(this@MainActivity, WakeWordService::class.java)
                            .setAction(WakeWordService.ACTION_PAUSE),
                    )
                } catch (error: Exception) {
                    waitingForWakePause = false
                    vm.setListening(false)
                    vm.showReply("Could not pause wake-word listening: ${error.message}")
                }
                return
            }
            coroutineScope.launch {
                if (greet) playWakeGreeting()
                listen(
                    { vm.setListening(false); vm.submit(it) },
                    { vm.setListening(false); vm.showReply(it) },
                    state.patientListeningEnabled,
                    state.noiseProcessingEnabled,
                    vm::setAudioProcessingStatus,
                )
            }
        }

        fun setJivaEnabled(enabled: Boolean) {
            if (!enabled) {
                processedAudioInput?.close()
                processedAudioInput = null
                cancellingSpeech = true
                speech?.cancel()
                speech?.destroy()
                speech = null
                tts?.stop()
                vm.stopSpeech()
                if (vm.wakeWordServiceEnabled()) {
                    startService(
                        Intent(this@MainActivity, WakeWordService::class.java)
                            .setAction(WakeWordService.ACTION_STOP),
                    )
                }
                wakeServiceActive = false
                wakeStartPending = false
                waitingForWakePause = false
            }
            vm.setAssistantEnabled(enabled)
        }

        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (allowed) {
                if (greetAfterPermission) {
                    greetAfterPermission = false
                    beginVoiceCapture(greet = true)
                } else {
                    beginVoiceCapture(greet = false)
                }
            } else {
                greetAfterPermission = false
                vm.showReply("Microphone permission is needed for voice commands.")
            }
        }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) vm.showReply("Reminder scheduled, but notifications are disabled. Enable them in Android Settings to see alerts.")
        }
        val wakeNotificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && wakeStartPending) {
                wakeStartPending = false
                startWakeService()
            } else if (!granted) {
                wakeStartPending = false
                wakeSetupError = "Notification permission is required for the visible microphone service."
            }
        }
        val wakeMicPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (!allowed) {
                wakeSetupError = "Microphone permission is required for wake-word listening."
            } else if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                wakeStartPending = true
                wakeNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startWakeService()
            }
        }
        val wakeModelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                wakeImportPending = false
            } else {
                val selectedModel = pendingWakeModelName
                coroutineScope.launch {
                    try {
                        val path = withContext(Dispatchers.IO) {
                            WakeWordModelImporter.import(this@MainActivity, uri, selectedModel)
                        }
                        vm.saveWakeWordModel(selectedModel, path)
                        wakeModels = vm.wakeWordModels()
                        wakeSetupError = ""
                    } catch (error: Exception) {
                        wakeSetupError = error.message ?: "Could not import the wake-word model."
                    } finally {
                        wakeImportPending = false
                    }
                }
            }
        }
        val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@rememberLauncherForActivityResult
            val plan = vm.state.value.pendingPlan ?: return@rememberLauncherForActivityResult
            val contact = ContactPicker.readSelectedPhone(contentResolver, uri)
            if (contact != null) vm.updatePlan(plan.copy(contactName = contact.displayName, phoneNumber = contact.phoneNumber))
            else vm.showReply("Couldn't read a phone number from that contact. Choose another contact.")
        }

        DisposableEffect(vm) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: Intent?) {
                    when (intent?.action) {
                        WakeWordService.ACTION_WAKE_WORD -> {
                            beginVoiceCapture(greet = true, pauseWakeWord = false)
                        }
                        WakeWordService.ACTION_PAUSED -> {
                            if (waitingForWakePause) {
                                val greet = greetAfterWakePause
                                waitingForWakePause = false
                                beginVoiceCapture(greet, pauseWakeWord = false)
                            }
                        }
                        WakeWordService.ACTION_ERROR -> {
                            waitingForWakePause = false
                            vm.showReply(
                                intent.getStringExtra(WakeWordService.EXTRA_ERROR) ?: "Wake-word listening failed.",
                            )
                        }
                        WakeWordService.ACTION_STOPPED -> {
                            wakeServiceActive = false
                            if (waitingForWakePause) {
                                val greet = greetAfterWakePause
                                waitingForWakePause = false
                                beginVoiceCapture(greet, pauseWakeWord = false)
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(WakeWordService.ACTION_WAKE_WORD)
                addAction(WakeWordService.ACTION_PAUSED)
                addAction(WakeWordService.ACTION_ERROR)
                addAction(WakeWordService.ACTION_STOPPED)
            }
            ContextCompat.registerReceiver(
                this@MainActivity,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            onDispose { unregisterReceiver(receiver) }
        }

        fun requestWakeServiceStart() {
            if (!vm.wakeWordConfigured()) {
                wakeSetupError = "Save a Picovoice AccessKey and import both Jiva and Giva .ppn models first."
                return
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                wakeMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                wakeStartPending = true
                wakeNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startWakeService()
            }
        }

        MaterialTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Jiva", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                            Text("Your assistant · user-controlled", style = MaterialTheme.typography.bodyMedium)
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (state.enabled) "On" else "Off")
                                Switch(
                                    checked = state.enabled,
                                    onCheckedChange = ::setJivaEnabled,
                                )
                            }
                            OutlinedButton(
                                enabled = state.enabled,
                                onClick = { pauseWakeService(); showKey = true },
                            ) {
                                Text(
                                    when {
                                        keyVerified -> "Gemini verified"
                                        vm.hasApiKey() -> "Gemini key saved"
                                        else -> "Gemini key"
                                    },
                                )
                            }
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                when {
                                    state.busy -> "Jiva is working or speaking…"
                                    state.listening -> "Jiva is listening…"
                                    !state.enabled -> "Jiva is off · turn it on to use the assistant"
                                    state.screenAssistPending -> "Jiva is waiting for the target app…"
                                    state.isSleeping -> "Jiva is sleeping · tap Speak to wake"
                                    else -> "Talk or type to Jiva"
                                },
                                style = MaterialTheme.typography.titleLarge,
                            )
                            if (state.transcript.isNotBlank()) Text("You: ${state.transcript}")
                            Text(state.reply, style = MaterialTheme.typography.bodyLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = state.enabled && !state.busy && !state.listening, onClick = {
                                    if (state.isSleeping) {
                                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                            beginVoiceCapture(greet = true)
                                        } else {
                                            greetAfterPermission = true
                                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    } else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        beginVoiceCapture(greet = false)
                                    }
                                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                }, modifier = Modifier.weight(1f)) { Text("🎙 Speak") }
                                OutlinedButton(
                                    enabled = state.enabled,
                                    onClick = {
                                        processedAudioInput?.close()
                                        processedAudioInput = null
                                        cancellingSpeech = true
                                        tts?.stop()
                                        speech?.cancel()
                                        vm.stopSpeech()
                                        vm.setListening(false)
                                    },
                                ) { Text("Stop") }
                            }
                            Box {
                                OutlinedButton(enabled = state.enabled, onClick = { showVoiceMenu = true }) {
                                    Text("Voice: ${VoicePresets.find(state.selectedVoice).label}")
                                }
                                DropdownMenu(
                                    expanded = showVoiceMenu,
                                    onDismissRequest = { showVoiceMenu = false },
                                ) {
                                    VoicePresets.all.forEach { voice ->
                                        DropdownMenuItem(
                                            text = { Text("${voice.perceivedVoice}: ${voice.label}") },
                                            onClick = {
                                                vm.selectVoice(voice.voiceName)
                                                showVoiceMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(value = state.input, onValueChange = vm::setInput, enabled = state.enabled, label = { Text("Type in your language") }, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { pauseWakeService(); vm.submit() }, enabled = state.enabled && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Send") }
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Voice input", style = MaterialTheme.typography.titleMedium)
                            Text(state.audioProcessingStatus, style = MaterialTheme.typography.bodySmall)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Clear voice processing")
                                    Text(
                                        "Use Android noise reduction and automatic gain when supported.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(
                                    checked = state.noiseProcessingEnabled,
                                    onCheckedChange = vm::setNoiseProcessingEnabled,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Patient listening")
                                    Text(
                                        "Wait longer through pauses and slow speech.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(
                                    checked = state.patientListeningEnabled,
                                    onCheckedChange = vm::setPatientListeningEnabled,
                                )
                            }
                            Text(
                                "Noise processing is best-effort: Android 13+ and the speech service/device must support supplied microphone audio. Otherwise Jiva uses standard phone speech recognition. The speech service may process audio remotely; only recognized text is sent to Gemini. Very loud noise can still affect accuracy.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Text("Quick actions", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = state.enabled, onClick = { pauseWakeService(); appItems = queryApps(); appQuery = ""; showApps = true }) { Text("Apps") }
                        OutlinedButton(enabled = state.enabled, onClick = { pauseWakeService(); vm.showReminderEditor() }) { Text("Reminders") }
                        OutlinedButton(enabled = state.enabled, onClick = { pauseWakeService(); vm.updatePlan(IntentPlan(AssistantAction.OPEN_SETTINGS, "Open settings?", settingsTarget = "general")) }) { Text("Settings") }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Visible-screen assistance", style = MaterialTheme.typography.titleMedium)
                            Text("Optional Android Accessibility access. After confirming, switch to the target app within 30 seconds; Jiva reads or acts only once on that next app screen. Text stays local and is not sent to Gemini. Android cannot reliably identify every secure window; do not use this on private, banking, login, or payment screens. Password fields block all screen actions.")
                            OutlinedButton(
                                enabled = state.enabled,
                                onClick = {
                                    pauseWakeService()
                                    showScreenReadWarning = true
                                    screenAssistError = ""
                                },
                            ) { Text("Read visible screen") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled = state.enabled, onClick = { runScreenAction("back") }) { Text("Back") }
                                OutlinedButton(enabled = state.enabled, onClick = { runScreenAction("scroll_backward") }) { Text("Scroll up") }
                                OutlinedButton(enabled = state.enabled, onClick = { runScreenAction("scroll_forward") }) { Text("Scroll down") }
                            }
                            OutlinedTextField(
                                value = screenTarget,
                                onValueChange = { screenTarget = it },
                                enabled = state.enabled,
                                label = { Text("Exact visible button label") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(
                                enabled = state.enabled && screenTarget.isNotBlank(),
                                onClick = {
                                    pauseWakeService()
                                    pendingScreenClick = screenTarget.trim()
                                    sensitiveClickReviewed = false
                                },
                            ) { Text("Review and click") }
                            if (screenAssistError.isNotBlank()) {
                                Text(screenAssistError, color = MaterialTheme.colorScheme.error)
                            }
                            if (screenText.isNotBlank()) {
                                Text("Visible text (not sent to Gemini):", style = MaterialTheme.typography.labelLarge)
                                Text(screenText, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(
                                enabled = state.enabled,
                                onClick = {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                            ) { Text("Open Android Accessibility Settings") }
                        }
                    }
                    OutlinedButton(enabled = state.enabled, onClick = {
                        pauseWakeService()
                        wakeSetupError = ""
                        wakeModels = vm.wakeWordModels()
                        showWakeSettings = true
                    }) { Text("Wake-word settings") }
                    if (wakeServiceActive) {
                        OutlinedButton(enabled = state.enabled, onClick = {
                            stopService(Intent(this@MainActivity, WakeWordService::class.java))
                            vm.setWakeWordServiceEnabled(false)
                            wakeServiceActive = false
                        }) { Text("Stop background wake listening") }
                    } else {
                        OutlinedButton(enabled = state.enabled, onClick = { requestWakeServiceStart() }) { Text("Start background wake listening") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = state.enabled, onClick = { pauseWakeService(); vm.updatePlan(IntentPlan(AssistantAction.CALL, "Choose a contact to call.")) }) { Text("Call") }
                        OutlinedButton(enabled = state.enabled, onClick = { pauseWakeService(); vm.updatePlan(IntentPlan(AssistantAction.SMS, "Choose a contact and write a message.")) }) { Text("Message") }
                        OutlinedButton(enabled = state.enabled, onClick = { pauseWakeService(); vm.updatePlan(IntentPlan(AssistantAction.CAMERA, "Open camera?")) }) { Text("Camera") }
                    }
                    Text("Upcoming reminders", style = MaterialTheme.typography.titleLarge)
                    if (state.reminders.isEmpty()) Text("No reminders scheduled.")
                    state.reminders.forEach { Text("• ${it.text} — ${java.text.DateFormat.getDateTimeInstance().format(it.triggerAtMillis)}") }
                    Text("Speech languages depend on your phone's speech service. Gemini requests send text to Google using your key. Sensitive actions require your review.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showLegal = true }) { Text("Usage, privacy & legal notice") }
                }
            }
        }

        FirstRunNotice()
        if (showLegal) LegalNoticeDialog(onDismiss = { showLegal = false })

        state.pendingPlan?.let { plan ->
            ActionConfirmationDialog(plan = plan, onDismiss = vm::dismissPlan, onPickContact = {
                contactPicker.launch(ContactPicker.pickPhoneIntent())
            }, onConfirm = confirm@{ confirmed ->
                // Stale dialog callbacks and double taps must not repeat a handoff.
                if (!vm.consumePendingPlan(plan)) return@confirm
                when (confirmed.action) {
                    AssistantAction.CALL -> launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(confirmed.phoneNumber.orEmpty())}")))
                    AssistantAction.SMS -> launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(confirmed.phoneNumber.orEmpty())}")).putExtra("sms_body", confirmed.message.orEmpty()))
                    AssistantAction.WHATSAPP_SHARE -> launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, confirmed.message.orEmpty()), "Choose a messaging app"))
                    AssistantAction.OPEN_SETTINGS -> launch(settingsIntent(confirmed.settingsTarget))
                    AssistantAction.CAMERA -> launch(Intent("android.media.action.IMAGE_CAPTURE"))
                    AssistantAction.OPEN_APP -> {
                        val match = queryApps().firstOrNull { it.first.equals(confirmed.appName, true) }
                            ?: queryApps().firstOrNull { it.first.contains(confirmed.appName.orEmpty(), true) }
                        if (match != null) launch(match.second) else { appQuery = confirmed.appName.orEmpty(); appItems = queryApps(); showApps = true }
                    }
                    AssistantAction.REMINDER -> vm.showReminderEditor()
                    AssistantAction.PHONE_NAVIGATION -> {
                        val service = JivaAccessibilityService.instance
                        if (service == null) {
                            vm.showReply("Enable Jiva screen assistance in Android Accessibility Settings to use phone navigation.")
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } else {
                            try {
                                val completed = service.performPhoneNavigation(confirmed.phoneAction.orEmpty())
                                vm.showReply(
                                    if (completed) "Phone navigation action completed."
                                    else "Android could not perform that navigation action.",
                                )
                            } catch (error: Exception) {
                                vm.showReply(error.message ?: "Phone navigation failed.")
                            }
                        }
                    }
                    AssistantAction.CHAT -> Unit
                }
            })
        }

        if (showKey) AlertDialog(onDismissRequest = { if (!testingKey) showKey = false }, title = { Text("Connect Gemini to Jiva") }, text = {
            Column {
                OutlinedTextField(
                    keyText,
                    {
                        keyText = it
                        keyError = ""
                    },
                    label = { Text("Your Gemini API key") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !testingKey,
                )
                Text("Encrypted on this device with Android Keystore. Gemini receives your text; Live voice uses a WebSocket URL containing the key. Use short-lived tokens from a backend for production.")
                if (keyError.isNotBlank()) Text(keyError, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            Button(
                enabled = !testingKey && keyText.isNotBlank(),
                onClick = {
                    testingKey = true
                    keyError = ""
                    coroutineScope.launch {
                        vm.verifyAndSaveApiKey(keyText.trim()) { attempt, _ ->
                            vm.showGeminiRetryStatus("Gemini key verification", attempt)
                        }
                            .onSuccess {
                                keyVerified = true
                                keyText = ""
                                showKey = false
                                vm.showReply("Gemini key and model access verified.")
                            }
                            .onFailure { keyError = it.message ?: "Gemini API verification failed." }
                        testingKey = false
                    }
                },
            ) { Text(if (testingKey) "Testing…" else "Test & save") }
        }, dismissButton = {
            TextButton(
                enabled = !testingKey,
                onClick = { vm.clearApiKey(); keyText = ""; keyVerified = false; showKey = false },
            ) { Text("Remove key") }
        })

        if (showWakeSettings) AlertDialog(
            onDismissRequest = { showWakeSettings = false },
            title = { Text("Background wake words") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = picovoiceKeyText,
                        onValueChange = { picovoiceKeyText = it; wakeSetupError = "" },
                        label = { Text("Picovoice AccessKey") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text("Saved encrypted on this device. Create Android .ppn models for both “Jiva” and “Giva” in Picovoice Console. Stop background listening before changing the key or models, then restart it.")
                    TextButton(onClick = {
                        try {
                            vm.savePicovoiceAccessKey(picovoiceKeyText.trim())
                            picovoiceKeyText = ""
                            wakeSetupError = ""
                        } catch (error: Exception) {
                            wakeSetupError = error.message ?: "Could not save the AccessKey."
                        }
                    }) { Text("Save AccessKey") }
                    TextButton(enabled = !wakeImportPending, onClick = {
                        wakeImportPending = true
                        pendingWakeModelName = "jiva"
                        wakeModelPicker.launch(arrayOf("*/*"))
                    }) { Text("Import Jiva .ppn · ${if (wakeModels.any { java.io.File(it).name == "jiva.ppn" }) "ready" else "missing"}") }
                    TextButton(enabled = !wakeImportPending, onClick = {
                        wakeImportPending = true
                        pendingWakeModelName = "giva"
                        wakeModelPicker.launch(arrayOf("*/*"))
                    }) { Text("Import Giva .ppn · ${if (wakeModels.any { java.io.File(it).name == "giva.ppn" }) "ready" else "missing"}") }
                    if (wakeSetupError.isNotBlank()) Text(wakeSetupError, color = MaterialTheme.colorScheme.error)
                    Text("Android keeps a visible microphone notification while listening. Force-stop, restricted battery settings, or vendor process controls can stop the service.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showWakeSettings = false }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = {
                    stopService(Intent(this@MainActivity, WakeWordService::class.java))
                    vm.setWakeWordServiceEnabled(false)
                    vm.clearWakeWordConfig()
                    wakeServiceActive = false
                    wakeModels = emptyList()
                }) { Text("Remove wake-word setup") }
            },
        )

        if (showScreenReadWarning) AlertDialog(
            onDismissRequest = { showScreenReadWarning = false },
            title = { Text("Read the current screen?") },
            text = {
                Text("Only the visible accessibility text will be read locally. Do not continue on a private, login, banking, payment, or otherwise secure screen. Android does not expose a reliable indicator for every secure screen.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showScreenReadWarning = false
                    try {
                        val service = JivaAccessibilityService.instance
                        if (service == null) {
                            screenAssistError = "Enable Jiva screen assistance in Android Accessibility Settings, then return here."
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } else {
                            service.requestVisibleScreenRead()
                            vm.setScreenAssistPending(true)
                            screenText = ""
                            screenAssistError = "Switch to the target app within 30 seconds. Jiva will capture that screen once."
                        }
                    } catch (error: Exception) {
                        screenAssistError = error.message ?: "The visible screen could not be read."
                    }
                }) { Text("Read this screen") }
            },
            dismissButton = {
                TextButton(onClick = { showScreenReadWarning = false }) { Text("Cancel") }
            },
        )

        if (pendingScreenClick.isNotBlank()) {
            val sensitive = Regex(
                "(send|delete|pay|purchase|transfer|confirm|submit|post|buy|remove|share|भेज|हट|मिटा|खरीद|भुगतान|पैसा|स्थानांतरण|पुष्टि|साझा)",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(pendingScreenClick)
            AlertDialog(
                onDismissRequest = {
                    pendingScreenClick = ""
                    sensitiveClickReviewed = false
                },
                title = {
                    Text(
                        if (sensitive && sensitiveClickReviewed) {
                            "Confirm sensitive click"
                        } else {
                            "Click visible control?"
                        },
                    )
                },
                text = {
                    Text(
                        if (sensitive && sensitiveClickReviewed) {
                            "Final confirmation: click “$pendingScreenClick” now? This could send, delete, purchase, or share information. Verify the app and screen before proceeding."
                        } else {
                            "Jiva will click the unique visible control labeled “$pendingScreenClick”. Review the current app and screen. Sensitive labels require a second confirmation."
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (sensitive && !sensitiveClickReviewed) {
                            sensitiveClickReviewed = true
                        } else {
                            clickScreenTarget()
                        }
                    }) {
                        Text(
                            if (sensitive && !sensitiveClickReviewed) {
                                "Review again"
                            } else {
                                "Confirm click"
                            },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pendingScreenClick = ""
                        sensitiveClickReviewed = false
                    }) { Text("Cancel") }
                },
            )
        }

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

        if (state.showReminderEditor) ReminderEditorDialog(context = this, onDismiss = vm::closeReminderEditor, onSave = { text, time ->
            if (!reminderSavePending) {
                reminderSavePending = true
                try {
                    reminderScheduler.schedule(text, time).onSuccess {
                        vm.setReminders(reminderScheduler.list()); vm.closeReminderEditor(); vm.showReply("Reminder scheduled: $text")
                        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }.onFailure { vm.showReply(it.message ?: "Couldn't schedule reminder.") }
                } finally {
                    reminderSavePending = false
                }
            }
        })

        LaunchedEffect(Unit) {
            vm.setReminders(reminderScheduler.list())
            JivaAccessibilityService.results.collect { result ->
                vm.setScreenAssistPending(false)
                if (result.type == "read") screenText = result.text.joinToString("\n")
                screenAssistError = result.message
            }
        }
        LaunchedEffect(
            state.busy,
            state.listening,
            state.screenAssistPending,
            state.pendingPlan,
            state.showReminderEditor,
            wakeServiceActive,
            showKey,
            showWakeSettings,
            showApps,
        ) {
            if (wakeServiceActive &&
                !state.busy &&
                !state.listening &&
                !state.screenAssistPending &&
                state.pendingPlan == null &&
                !state.showReminderEditor &&
                !showKey &&
                !showWakeSettings &&
                !showApps
            ) {
                startService(
                    Intent(this@MainActivity, WakeWordService::class.java)
                        .setAction(WakeWordService.ACTION_RESUME),
                )
            }
        }
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
        "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
        else -> Intent(Settings.ACTION_SETTINGS)
    }

    private fun launch(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { /* TODO: surface unavailable-action state in UI. */ }
    }

    override fun onDestroy() {
        processedAudioInput?.close()
        processedAudioInput = null
        cancellingSpeech = true
        speech?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
