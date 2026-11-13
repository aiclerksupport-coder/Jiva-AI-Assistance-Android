package com.jiva.assistance

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.jiva.assistance.domain.AssistantAction
import com.jiva.assistance.domain.IntentPlan
import java.util.Calendar

@Composable
fun ActionConfirmationDialog(
    plan: IntentPlan,
    onDismiss: () -> Unit,
    onPickContact: () -> Unit,
    onConfirm: (IntentPlan) -> Unit,
) {
    var message by remember(plan) { mutableStateOf(plan.message.orEmpty()) }
    val needsContact = plan.action in setOf(AssistantAction.CALL, AssistantAction.SMS, AssistantAction.WHATSAPP_SHARE)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when (plan.action) {
            AssistantAction.CALL -> "Confirm call"
            AssistantAction.SMS -> "Review message"
            AssistantAction.WHATSAPP_SHARE -> "Review WhatsApp handoff"
            AssistantAction.OPEN_SETTINGS -> "Open Android settings?"
            AssistantAction.CAMERA -> "Open camera?"
            AssistantAction.OPEN_APP -> "Open app?"
            AssistantAction.REMINDER -> "Create reminder?"
            AssistantAction.CHAT -> "Jiva"
        }) },
        text = {
            Column {
                Text(when (plan.action) {
                    AssistantAction.CALL -> "Jiva will open the system dialer. You must tap Call there."
                    AssistantAction.SMS -> "Review the SMS in your messaging app and tap Send there."
                    AssistantAction.WHATSAPP_SHARE -> "Jiva cannot choose a private WhatsApp chat or send automatically. Choose the chat and tap Send in WhatsApp."
                    AssistantAction.OPEN_SETTINGS -> "Jiva opens Android settings; you approve and make any protected change there."
                    AssistantAction.CAMERA -> "Open the camera app on this device."
                    AssistantAction.OPEN_APP -> "Open ${plan.appName ?: "the selected app"} if installed."
                    AssistantAction.REMINDER -> "Open the reminder editor to choose text and time."
                    AssistantAction.CHAT -> plan.reply
                })
                if (needsContact) {
                    TextButton(onClick = onPickContact) { Text(plan.contactName?.let { "Selected: $it · choose again" } ?: "Choose contact") }
                    if (plan.contactName != null) Text("${plan.contactName} · ${plan.phoneNumber}")
                }
                if (plan.action == AssistantAction.SMS || plan.action == AssistantAction.WHATSAPP_SHARE) {
                    OutlinedTextField(message, { message = it }, label = { Text("Message draft") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            Button(enabled = !needsContact || plan.phoneNumber != null, onClick = { onConfirm(plan.copy(message = message)) }) {
                Text(if (plan.action == AssistantAction.CALL) "Open dialer" else "Continue")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ReminderEditorDialog(onDismiss: () -> Unit, onSave: (String, Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf(9) }
    var minute by remember { mutableStateOf(0) }
    var date by remember { mutableStateOf(Calendar.getInstance()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Jiva reminder") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, label = { Text("What should Jiva remind you?") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    val current = date
                    DatePickerDialog(current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH)) { _, y, m, d ->
                        date = (current.clone() as Calendar).apply { set(y, m, d) }
                    }.show()
                }) { Text("Choose date") }
                TextButton(onClick = {
                    TimePickerDialog(null, { _, h, m -> hour = h; minute = m }, hour, minute, false).show()
                }) { Text("Time: %02d:%02d".format(hour, minute)) }
            }
        },
        confirmButton = { Button(onClick = {
            val trigger = (date.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            if (text.isNotBlank()) onSave(text.trim(), trigger)
        }) { Text("Schedule") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
