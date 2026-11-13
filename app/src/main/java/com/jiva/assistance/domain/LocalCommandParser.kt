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
                IntentPlan(
                    AssistantAction.WHATSAPP_SHARE,
                    "Choose the contact and review the message in WhatsApp before sending.",
                    recipientQuery = extractRecipient(raw),
                    message = extractMessage(raw),
                )

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

            else -> extractAppName(raw)?.let { app ->
                IntentPlan(AssistantAction.OPEN_APP, "I'll look for that installed app.", appName = canonicalAppName(app))
            }
        }
    }

    /** Recognize explicit app-launch phrasing; the selected installed app still goes through confirmation. */
    private fun extractAppName(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val prefix = Regex(
            "^(?i)(?:please\\s+|जरा\\s+|कृपया\\s+)?(?:open|launch|start|run|खोलो|खोलिए|खोलें|खोल|चलाओ|चला\\s*दो|चालू\\s*करो|शुरू\\s*करो|उघडा|उघड|चालवा|ખોલો|ચાલુ\\s*કરો|ચાલાવો|খোলো|খুলুন|চালাও|চালু\\s*করো|খোলক|চলাও|திற|திறக்கவும்|இயக்கு|తెరువు|తెరవండి|నడుపు|ತೆರೆ|ತೆರೆಯಿರಿ|ಚಲಾಯಿಸು|തുറക്കൂ|തുറക്കുക|പ്രവർത്തിപ്പിക്കുക|ਖੋਲ੍ਹੋ|ਚਲਾਓ|کھولو|چلاؤ|کھولیں|چلائیں|ଖୋଲ|ଖୋଲନ୍ତୁ|ଚଲାଅ|खोलो|चलाओ|चलाइए|चलाइये|चलवा|చలావో|چلاؤ)\\s+(?:the\\s+|app\\s+)?",
        )
        val suffix = Regex(
            "(?i)\\s+(?:app\\s+)?(?:please\\s+)?(?:open|launch|start|run|खोलो|खोलिए|खोलें|खोल|चलाओ|चला\\s*दो|चालू\\s*करो|शुरू\\s*करो|उघडा|उघड|चालवा|ખોલો|ચાલુ\\s*કરો|ચાલાવો|খোলো|খুলুন|চালাও|চালু\\s*করো|খোলক|চলাও|திற|திறக்கவும்|இயக்கு|తెరువు|తెరవండి|నడుపు|ತೆರೆ|ತೆರೆಯಿರಿ|ಚಲಾಯಿಸು|തുറക്കൂ|തുറക്കുക|പ്രവർത്തിപ്പിക്കുക|ਖੋਲ੍ਹੋ|ਚਲਾਓ|کھولو|چلاؤ|کھولیں|چلائیں|ଖୋଲ|ଖୋଲନ୍ତୁ|ଚଲାଅ|खोलो|चलाओ|चलाइए|चलाइये|चलवा|చలావో|چلاؤ)(?:\\s+(?:please|कृपया))?$",
        )

        val app = when {
            prefix.containsMatchIn(trimmed) -> trimmed.replaceFirst(prefix, "").trim()
            suffix.containsMatchIn(trimmed) -> trimmed.replace(suffix, "").trim()
            else -> return null
        }.replace(Regex("(?i)\\s+(?:app|application)$"), "").trim().trim('"', '\'')

        return app.takeIf { it.isNotBlank() && it.length <= 100 }
    }

    /** Convert common Indian-script names to the English launcher labels used by many installed apps. */
    private fun canonicalAppName(name: String): String {
        val key = name.trim().lowercase(Locale.ROOT).replace(Regex("[\\s._-]+"), "")
        return APP_ALIASES[key] ?: name.trim()
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

    private companion object {
        val APP_ALIASES = mapOf(
            "whatsapp" to "WhatsApp", "व्हाट्सएप" to "WhatsApp", "व्हाट्सऐप" to "WhatsApp", "واتساب" to "WhatsApp",
            "વોટ્સએપ" to "WhatsApp", "વોટ્સેપ" to "WhatsApp", "வாட்ஸ்அப்" to "WhatsApp", "వాట్సాప్" to "WhatsApp",
            "হোয়াটসঅ্যাপ" to "WhatsApp", "হোয়াটসঅ্যাপ" to "WhatsApp", "ਵਟਸਐਪ" to "WhatsApp", "واٹسایپ" to "WhatsApp", "واٹس ایپ" to "WhatsApp",
            "youtube" to "YouTube", "यूट्यूब" to "YouTube", "یوٹیوب" to "YouTube", "યુટ્યુબ" to "YouTube", "யூடியூப்" to "YouTube",
            "యూట్యూబ్" to "YouTube", "ইউটিউব" to "YouTube", "ਯੂਟਿਊਬ" to "YouTube", "ୟୁଟ୍ୟୁବ" to "YouTube",
            "instagram" to "Instagram", "इंस्टाग्राम" to "Instagram", "ઇન્સ્ટાગ્રામ" to "Instagram", "இன்ஸ்டாகிராம்" to "Instagram",
            "ఇన్‌స్టాగ్రామ్" to "Instagram", "ইনস্টাগ্রাম" to "Instagram", "ਇੰਸਟਾਗ੍ਰਾਮ" to "Instagram", "انسٹاگرام" to "Instagram",
            "facebook" to "Facebook", "फेसबुक" to "Facebook", "ફેસબુક" to "Facebook", "ஃபேஸ்புக்" to "Facebook",
            "ఫేస్బుక్" to "Facebook", "ফেসবুক" to "Facebook", "ਫੇਸਬੁੱਕ" to "Facebook", "فیس بک" to "Facebook",
            "telegram" to "Telegram", "टेलीग्राम" to "Telegram", "ટેલિગ્રામ" to "Telegram", "டெலிகிராம்" to "Telegram",
            "టెలిగ్రామ్" to "Telegram", "টেলিগ্রাম" to "Telegram", "ਟੈਲੀਗ੍ਰਾਮ" to "Telegram", "ٹیلیگرام" to "Telegram",
            "chrome" to "Chrome", "क्रोम" to "Chrome", "ક્રોમ" to "Chrome", "குரோம்" to "Chrome", "క్రోమ్" to "Chrome",
            "ক্রোম" to "Chrome", "ਕ੍ਰੋਮ" to "Chrome", "کروم" to "Chrome",
            "gmail" to "Gmail", "जीमेल" to "Gmail", "જીમેલ" to "Gmail", "ஜிமெயில்" to "Gmail", "జీమెయిల్" to "Gmail",
            "জিমেইল" to "Gmail", "ਜੀਮੇਲ" to "Gmail", "جی میل" to "Gmail",
            "google maps" to "Maps", "maps" to "Maps", "गूगल मैप्स" to "Maps", "मैप्स" to "Maps", "મેપ્સ" to "Maps",
            "மேப்ஸ்" to "Maps", "మ్యాప్స్" to "Maps", "ম্যাপস" to "Maps", "ਮੈਪਸ" to "Maps", "گوگل میپس" to "Maps",
        )
    }
}
