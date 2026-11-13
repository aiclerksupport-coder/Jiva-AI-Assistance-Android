package com.jiva.assistance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LegalNoticeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Usage, privacy & legal notice") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("EXPERIMENTAL · JIVA IS NOT A SAFETY-CRITICAL SERVICE", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                Text("AI, speech, translation, app handoffs, and reminders may be inaccurate or fail. Review all results and actions. Do not use Jiva for emergencies or safety-critical tasks.\n\n" +
                    "ANDROID ACTIONS\nJiva cannot silently control the whole phone. Calls, SMS, and WhatsApp are handed off for your review; you complete sending. Protected settings are changed by you in Android. Delivery and compatibility are not guaranteed.\n\n" +
                    "GEMINI AND DATA\nIf you configure your own Gemini API key, prompts are sent to Google and may use your quota or incur charges. Speech provider processing depends on your phone/provider. Do not send sensitive information unless you accept the providers' terms and data practices. Keep your key private.\n\n" +
                    "YOUR RESPONSIBILITY\nReview source, permissions, and third-party terms; use Jiva lawfully; verify outputs before relying on them. This project is provided under the MIT License and without a promise of fitness or accuracy. This notice is not legal advice or a blanket waiver of rights/liability. Mandatory legal rights remain unaffected.\n\n" +
                    "Read the repository's README, LICENSE, DISCLAIMER.md, TERMS_OF_USE.md, SECURITY.md, and LEGAL_REVIEW_REQUIRED.md for details.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
