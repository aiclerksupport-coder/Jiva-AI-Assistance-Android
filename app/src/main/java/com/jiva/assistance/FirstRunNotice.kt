package com.jiva.assistance

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** First-run safety/privacy acknowledgement. It informs users; it is not a legal waiver. */
@Composable
fun FirstRunNotice() {
    val context = LocalContext.current
    var acknowledged by remember { mutableStateOf(context.getSharedPreferences("jiva_notice", 0).getBoolean("acknowledged", false)) }
    var checked by remember { mutableStateOf(false) }
    if (!acknowledged) {
        AlertDialog(
            onDismissRequest = { /* User must choose Continue or Exit. */ },
            title = { Text("Before you use Jiva", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    Text("EXPERIMENTAL SOFTWARE", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Jiva is an experimental Android assistant. AI answers, translations, speech recognition, reminders, and phone actions can be wrong or fail. Check every answer, recipient, message, reminder, and action. Do not rely on Jiva for emergencies or safety-critical tasks.\n\n" +
                            "USER REVIEW REQUIRED\nCalls and messages open Android or another app for your review. Jiva cannot silently control the whole phone. Android, manufacturers, and third-party apps may restrict features.\n\n" +
                            "PRIVACY AND GEMINI\nWhen you connect Gemini, your prompt is sent to Google using your API key and may use your quota or incur charges. Speech recognition may use the provider installed on your phone. Do not send sensitive information unless you accept the providers' data handling.\n\n" +
                            "YOUR CHOICE\nReview the source, permissions, README, LICENSE, DISCLAIMER, and draft TERMS_OF_USE before use. Follow applicable laws and third-party terms. This acknowledgement is informational; it does not waive rights or liabilities that cannot legally be waived."
                    )
                    Row {
                        Checkbox(checked = checked, onCheckedChange = { checked = it })
                        Text("I have read and understand this notice.")
                    }
                }
            },
            confirmButton = {
                Button(enabled = checked, onClick = {
                    context.getSharedPreferences("jiva_notice", 0).edit().putBoolean("acknowledged", true).apply()
                    acknowledged = true
                }) { Text("Continue to Jiva") }
            },
            dismissButton = {
                TextButton(onClick = { (context as? Activity)?.finish() }) { Text("Exit") }
            },
        )
    }
}
