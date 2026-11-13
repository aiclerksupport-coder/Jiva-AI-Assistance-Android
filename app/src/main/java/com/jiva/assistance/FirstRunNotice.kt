package com.jiva.assistance

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
            onDismissRequest = { /* The user must choose Continue or Exit. */ },
            title = { Text("Welcome to Jiva · कृपया पहले पढ़ें") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Jiva is experimental. AI, translations, speech recognition, reminders and phone actions may be wrong or fail. Verify every response, contact, message and action. Do not rely on Jiva for emergencies or safety-critical tasks.\n\n" +
                            "Calls/messages open Android or another app for your review; Jiva cannot silently control the whole phone. Android and device providers may restrict features.\n\n" +
                            "When you connect Gemini, your prompt is sent to Google using your API key and may use your quota or incur charges. Speech recognition may use the provider installed on your phone. Do not submit sensitive information unless you accept those providers' data handling.\n\n" +
                            "You choose whether to install and use this open-source project and must follow applicable laws and third-party terms. This notice is not a legal waiver; rights and liability that cannot be waived remain in force. Read the README, DISCLAIMER, TERMS_OF_USE and LICENSE in the repository."
                    )
                    androidx.compose.foundation.layout.Row {
                        Checkbox(checked = checked, onCheckedChange = { checked = it })
                        Text("I have read this warning / मैंने चेतावनी पढ़ ली है")
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
                TextButton(onClick = { (context as? Activity)?.finish() }) { Text("Exit / बाहर निकलें") }
            },
        )
    }
}
