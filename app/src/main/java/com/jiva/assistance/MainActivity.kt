package com.jiva.assistance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var onRecognized: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent { JivaScreen() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jiva-reply")
    }

    private fun listen(onResult: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onResult("Is device par speech recognition available nahi hai.")
            return
        }
        onRecognized = onResult
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    onRecognized?.invoke(phrase ?: "Mujhe awaaz samajh nahi aayi.")
                }
                override fun onError(error: Int) { onRecognized?.invoke("Awaaz samajhne mein dikkat hui. Dobara koshish karein.") }
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Jiva se kahiye")
            }
            speech.startListening(request)
        }
    }

    private fun runCommand(text: String, onReply: (String) -> Unit) {
        val command = text.trim().lowercase(Locale.ROOT)
        val reply = when {
            command.contains("settings") || command.contains("setting") || command.contains("सेटिंग") || command.contains("सेटिंग्स") -> {
                startActivity(Intent(Settings.ACTION_SETTINGS)); "Settings khol raha hoon."
            }
            command.contains("camera") || command.contains("कैमरा") -> {
                val intent = Intent("android.media.action.IMAGE_CAPTURE")
                if (intent.resolveActivity(packageManager) != null) startActivity(intent)
                "Camera kholne ki koshish kar raha hoon."
            }
            command.contains("call") || command.contains("फोन") || command.contains("कॉल") -> {
                val number = Regex("[+]?[- 0-9]{6,}").find(text)?.value?.trim()
                if (number.isNullOrBlank()) "Call ke liye number boliye; abhi Jiva contact naam nahi pehchanta."
                else {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
                    "Dialer khol diya hai. Call karne se pehle number check karke aap khud call karein."
                }
            }
            command.contains("message") || command.contains("sms") || command.contains("मैसेज") || command.contains("संदेश") ->
                "Message bhejne ke liye pehle number aur text wala form agle version mein aayega. Is MVP mein koi message apne aap nahi bheja jata."
            command.contains("remind") || command.contains("याद") || command.contains("रिमाइंड") ->
                "Reminder feature agle version mein add hoga. Abhi koi reminder schedule nahi hua."
            else -> "Main abhi Settings, Camera, aur dialer khol sakta hoon. Aur features dheere-dheere add honge."
        }
        onReply(reply)
        speak(reply)
    }

    @Composable
    private fun JivaScreen() {
        var transcript by remember { mutableStateOf("") }
        var response by remember { mutableStateOf("Namaste! Main Jiva hoon. Mic dabakar boliye.") }
        var showCallConfirmation by remember { mutableStateOf(false) }
        var number by remember { mutableStateOf("") }

        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) listen { phrase ->
                transcript = phrase
                runCommand(phrase) { response = it }
            } else {
                response = "Voice ke liye microphone permission zaroori hai. Aap ise baad mein Settings se de sakte hain."
            }
        }

        DisposableEffect(Unit) {
            onDispose { recognizer?.destroy(); tts?.shutdown() }
        }

        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Jiva AI Assistance", style = MaterialTheme.typography.headlineMedium)
                    Text("Aapki bhasha mein, aapke control mein.")
                    Text("Aapne kaha: ${transcript.ifBlank { "—" }}")
                    Text(response, style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            listen { phrase -> transcript = phrase; runCommand(phrase) { response = it } }
                        } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Text("🎙 Boliye") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_SETTINGS)) }) { Text("Settings") }
                        OutlinedButton(onClick = { runCommand("camera") { response = it } }) { Text("Camera") }
                    }
                }
            }
        }

        if (showCallConfirmation) {
            AlertDialog(
                onDismissRequest = { showCallConfirmation = false },
                title = { Text("Call confirm karein") },
                text = { TextField(value = number, onValueChange = { number = it }, label = { Text("Phone number") }) },
                confirmButton = {
                    Button(onClick = {
                        showCallConfirmation = false
                        if (number.isNotBlank()) startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
                    }) { Text("Dialer kholen") }
                },
                dismissButton = { OutlinedButton(onClick = { showCallConfirmation = false }) { Text("Cancel") } }
            )
        }
    }
}
