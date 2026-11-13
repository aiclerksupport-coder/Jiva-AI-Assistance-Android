package com.jiva.assistance.domain

enum class AssistantAction { CHAT, OPEN_APP, CALL, SMS, WHATSAPP_SHARE, REMINDER, OPEN_SETTINGS, CAMERA, PHONE_NAVIGATION }

object PhoneNavigationActions {
    val supported = setOf("home", "back", "recents", "notifications", "quick_settings")
}

data class IntentPlan(
    val action: AssistantAction,
    val reply: String,
    val appName: String? = null,
    val recipientQuery: String? = null,
    val contactName: String? = null,
    val phoneNumber: String? = null,
    val message: String? = null,
    val settingsTarget: String? = null,
    val phoneAction: String? = null,
)

data class AssistantUiState(
    val input: String = "",
    val transcript: String = "",
    val reply: String = "Namaste! I'm Jiva. Speak or type in your language.",
    val busy: Boolean = false,
    val listening: Boolean = false,
    val screenAssistPending: Boolean = false,
    val enabled: Boolean = true,
    val isSleeping: Boolean = false,
    val noiseProcessingEnabled: Boolean = true,
    val patientListeningEnabled: Boolean = true,
    val audioProcessingStatus: String = "Microphone support is checked when listening starts.",
    val pendingPlan: IntentPlan? = null,
    val showReminderEditor: Boolean = false,
    val reminders: List<ReminderItem> = emptyList(),
    val selectedVoice: String = VoicePresets.default.voiceName,
)

data class ReminderItem(val id: Int, val text: String, val triggerAtMillis: Long)
