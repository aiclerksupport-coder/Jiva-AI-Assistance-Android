package com.jiva.assistance.domain

import java.util.Locale

/** Small deterministic parser for explicitly supported command phrases. Unknown requests fall through to Gemini. */
class LocalCommandParser {
    fun parse(raw: String): IntentPlan? {
        val text = raw.trim().lowercase(Locale.ROOT)
        if (text.isBlank()) return null

        phoneAction(text)?.let { action ->
            return IntentPlan(
                AssistantAction.PHONE_NAVIGATION,
                "Open the ${action.replace('_', ' ')} panel or screen?",
                phoneAction = action,
            )
        }

        return when {
            containsAny(text, "remind", "reminder", "याद दिला", "याद दिलाना", "रिमाइंडर", "recordatorio", "rappel", "提醒", "リマインダー") ->
                IntentPlan(AssistantAction.REMINDER, "Let's set a reminder. / Reminder ka time set karein.")

            containsAny(text, "whatsapp", "व्हाट्सएप", "واتساب") && containsAny(text, "message", "send", "मैसेज", "भेज", "mensaje", "enviar", "发", "送") ->
                IntentPlan(AssistantAction.WHATSAPP_SHARE, "Choose the contact and review the message in WhatsApp before sending.", recipientQuery = extractRecipient(raw), message = extractMessage(raw))

            containsAny(text, "call", "phone", "कॉल", "फोन", "कॉल करो", "llama", "llamar", "anrufen", "打电话") ->
                IntentPlan(AssistantAction.CALL, "Choose a contact. Jiva will open the dialer for your review.", recipientQuery = extractRecipient(raw))

            containsAny(text, "message", "sms", "मैसेज", "संदेश", "भेजो", "mensaje", "短信", "send text") ->
                IntentPlan(AssistantAction.SMS, "Choose a contact and review the message before sending.", recipientQuery = extractRecipient(raw), message = extractMessage(raw))

            containsAny(text, "settings", "setting", "सेटिंग", "configuración", "paramètres", "设置", "設定") -> {
                val target = when {
                    containsAny(text, "wifi", "wi-fi", "वाईफाई", "वाइफाइ") -> "wifi"
                    containsAny(text, "bluetooth", "ब्लूटूथ") -> "bluetooth"
                    containsAny(text, "display", "screen", "डिस्प्ले", "स्क्रीन") -> "display"
                    containsAny(text, "sound", "volume", "आवाज़", "ध्वनि") -> "sound"
                    else -> "general"
                }
                IntentPlan(AssistantAction.OPEN_SETTINGS, "I can open Android settings. You approve any changes there.", settingsTarget = target)
            }

            containsAny(text, "camera", "कैमरा", "cámara", "相机") ->
                IntentPlan(AssistantAction.CAMERA, "Open the camera app?")

            startsWithAny(text, "open ", "launch ", "start ", "खोलो ", "खोल ", "abre ", "abrir ", "開く ", "打开 ") -> {
                val app = raw.trim().replace(Regex("(?i)^(open|launch|start|खोलो|खोल|abre|abrir|開く|打开)\\s+"), "").trim()
                if (app.isBlank()) null else IntentPlan(AssistantAction.OPEN_APP, "I'll look for that installed app.", appName = app)
            }
            else -> null
        }
    }

    private fun extractRecipient(raw: String): String? {
        val match = Regex("(?i)\\b(?:to|call|message|text|send|फोन|कॉल|मैसेज|संदेश|भेजो|भेज)\\s+([\\p{L}][\\p{L} .'-]{0,40})").find(raw)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun extractMessage(raw: String): String? {
        val match = Regex("(?i)(?:saying|message|text|that|लिखो|लिखना|मैसेज)\\s*[:：]?\\s*[\"'](.+?)[\"']").find(raw)
        return match?.groupValues?.getOrNull(1)?.trim()
    }

    private fun phoneAction(text: String): String? = when {
        text in setOf("home", "go home", "home screen") ||
            containsAny(text, "go to home screen", "होम स्क्रीन", "होम पर जाओ") -> "home"
        text in setOf("back", "go back") ||
            containsAny(text, "navigate back", "वापस जाओ", "पीछे जाओ") -> "back"
        text in setOf("recents", "recent apps", "show recent apps") ||
            containsAny(text, "हाल के ऐप", "हालिया ऐप") -> "recents"
        text in setOf("notifications", "show notifications", "open notifications", "notification panel") ||
            containsAny(text, "notification shade", "notification panel", "सूचना पैनल", "नोटिफिकेशन") -> "notifications"
        text in setOf("quick settings", "open quick settings", "control center") ||
            containsAny(text, "क्विक सेटिंग", "कंट्रोल सेंटर") -> "quick_settings"
        else -> null
    }

    private fun containsAny(text: String, vararg phrases: String) = phrases.any { text.contains(it) }
    private fun startsWithAny(text: String, vararg phrases: String) = phrases.any { text.startsWith(it) }
}
