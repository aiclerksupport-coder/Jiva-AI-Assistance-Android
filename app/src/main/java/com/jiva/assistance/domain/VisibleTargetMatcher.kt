package com.jiva.assistance.domain

import java.text.Normalizer
import java.util.Locale

/** Pure, fail-closed matching for labels observed on the current visible accessibility tree. */
object VisibleTargetMatcher {
    data class Candidate(
        val labels: Set<String>,
        val targetIdentity: String,
        val visible: Boolean = true,
        val clickable: Boolean = true,
        val enabled: Boolean = true,
    )

    sealed interface Resolution {
        data class Unique(val targetIdentity: String) : Resolution
        data object NotFound : Resolution
        data object Ambiguous : Resolution
    }

    enum class ReviewRisk { NONE, SOCIAL_POST, COMMUNICATION, FINANCIAL, DESTRUCTIVE }

    /** Exact normalized equality or exact equivalent in a curated UI-label translation group. */
    fun resolve(label: String, candidates: List<Candidate>): Resolution {
        val target = normalize(label)
        if (target.isEmpty()) return Resolution.NotFound
        val equivalents = aliasesFor(target)
        val identities = candidates.asSequence()
            .filter { it.visible && it.clickable && it.enabled && it.targetIdentity.isNotBlank() }
            .filter { candidate -> candidate.labels.any { normalize(it) in equivalents } }
            .map { it.targetIdentity }
            .distinct()
            .take(2)
            .toList()
        return when (identities.size) {
            0 -> Resolution.NotFound
            1 -> Resolution.Unique(identities.single())
            else -> Resolution.Ambiguous
        }
    }

    /** Best-effort blocklist; this cannot reliably determine whether an Android window is secure. */
    fun looksLikePrivateOrCredentialScreen(visibleText: String): Boolean {
        val text = normalize(visibleText)
        return PRIVATE_SCREEN_TERMS.any { term -> containsTerm(text, term) }
    }

    /** Classifies action labels so the review UI can require an extra confirmation for consequential actions. */
    fun reviewRisk(label: String): ReviewRisk {
        val normalized = normalize(label)
        return when {
            matchesGroup(normalized, FINANCIAL_LABELS) -> ReviewRisk.FINANCIAL
            matchesGroup(normalized, DESTRUCTIVE_LABELS) -> ReviewRisk.DESTRUCTIVE
            matchesGroup(normalized, COMMUNICATION_LABELS) -> ReviewRisk.COMMUNICATION
            matchesGroup(normalized, SOCIAL_POST_LABELS) -> ReviewRisk.SOCIAL_POST
            else -> ReviewRisk.NONE
        }
    }

    fun isHighImpactLabel(label: String): Boolean = reviewRisk(label) != ReviewRisk.NONE

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)

    private fun aliasesFor(label: String): Set<String> = LABEL_GROUPS.firstOrNull { label in it } ?: setOf(label)
    private fun matchesGroup(label: String, groups: List<Set<String>>): Boolean = groups.any { label in it }

    private fun containsTerm(text: String, rawTerm: String): Boolean {
        val term = normalize(rawTerm)
        if (term.any { it.code > 127 }) return text.contains(term)
        return Regex("(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])").containsMatchIn(text)
    }

    private val LIKE_LABELS = setOf(
        "like", "liked", "लाइक", "पसंद", "पसन्द", "आवडलं", "आवडले", "પસંદ", "લાઈક", "விருப்பம்", "லைக்",
        "ఇష్టం", "లైక్", "পছন্দ", "লাইক", "ਪਸੰਦ", "ਲਾਈਕ", "ಪছಂದ", "ಲೈಕ್", "ഇഷ്ടം", "ലൈക്ക്", "پسند", "لائک",
    ).map(::normalize).toSet()
    private val SEND_LABELS = setOf(
        "send", "send now", "भेजें", "भेजो", "भेजिए", "పంపు", "పంపండి", "పంపించు", "அனுப்பு", "அனுப்பவும்",
        "পাঠান", "পাঠাও", "મોકલો", "મોકલો", "ಕಳುಹಿಸಿ", "അയയ്ക്കുക", "അയക്കുക", "ਭੇਜੋ", "ਭੇਜੋ", "بھیجیں", "بھیجیں",
    ).map(::normalize).toSet()
    private val PAY_LABELS = setOf(
        "pay", "pay now", "payment", "भुगतान", "भुगतान करें", "पैसे दें", "चुकाएं", "ચૂકવો", "ચુકવણી કરો",
        "செலுத்து", "பணம் செலுத்து", "చెల్లించు", "చెల్లించండి", "পরিশোধ করুন", "পেমেন্ট করুন", "ಪಾವತಿಸಿ",
        "പണം നൽകുക", "പണമടയ്ക്കുക", "ਭੁਗਤਾਨ ਕਰੋ", "ادائیگی", "ادائیگی کریں", "پیسے دیں",
    ).map(::normalize).toSet()
    private val DELETE_LABELS = setOf(
        "delete", "remove", "हटाएं", "हटाओ", "मिटाएं", "मिटाओ", "કાઢી નાખો", "કાઢી નાખો", "நீக்கு", "அழி",
        "తొలగించు", "తొలగించండి", "মুছুন", "মুছে ফেলুন", "ਮਿਟਾਓ", "ਹਟਾਓ", "ಅಳಿಸಿ", "നീക്കം ചെയ്യുക", "حذف", "مٹائیں",
    ).map(::normalize).toSet()
    private val COMMENT_LABELS = setOf(
        "comment", "post comment", "submit comment", "comment now", "टिप्पणी", "कमेंट", "टिप्पणी पोस्ट करें",
        "ટિપ્પણી", "કોમેન્ટ", "கருத்து", "கருத்தை இடுக", "వ్యాఖ్య", "కామెంట్", "মন্তব্য", "কমেন্ট",
        "ਟਿੱਪਣੀ", "ਕਮੈਂਟ", "ಕಾಮೆಂಟ್", "അഭിപ്രായം", "تبصرہ", "کمنٹ",
    ).map(::normalize).toSet()
    private val SHARE_LABELS = setOf(
        "share", "शेयर", "साझा करें", "साझा", "பகிர்", "பகிரவும்", "షేర్", "పంచుకోండి", "শেয়ার", "ভাগ করুন",
        "શેર", "શેર કરો", "ਸ਼ੇਅਰ", "ਸਾਂਝਾ ਕਰੋ", "ಹಂಚಿಕೊಳ್ಳಿ", "പങ്കിടുക", "شیئر", "شیئر کریں",
    ).map(::normalize).toSet()
    private val SOCIAL_POST_LABELS = listOf(LIKE_LABELS, COMMENT_LABELS, SHARE_LABELS)
    private val COMMUNICATION_LABELS = listOf(SEND_LABELS)
    private val FINANCIAL_LABELS = listOf(PAY_LABELS)
    private val DESTRUCTIVE_LABELS = listOf(DELETE_LABELS)
    private val LABEL_GROUPS = SOCIAL_POST_LABELS + COMMUNICATION_LABELS + FINANCIAL_LABELS + DESTRUCTIVE_LABELS

    private val PRIVATE_SCREEN_TERMS = listOf(
        "password", "passcode", "pin code", "one-time password", "one time password", "otp", "verification code",
        "verification pin", "security code", "enter pin", "enter your pin", "sign in", "log in", "login", "log on",
        "bank account", "banking", "net banking", "credit card", "debit card", "card number", "cvv", "cvc",
        "upi pin", "payment pin", "wallet balance", "transaction password", "account number", "account recovery",
        "पासवर्ड", "पिन कोड", "ओटीपी", "एक बार का पासवर्ड", "सत्यापन कोड", "लॉगिन", "साइन इन", "बैंक खाता", "बैंकिंग",
        "नेट बैंकिंग", "कार्ड नंबर", "सीवीवी", "यूपीआई पिन", "भुगतान पिन", "खाता नंबर", "खाता पुनर्प्राप्ति",
        "கடவுச்சொல்", "ஒரு முறை கடவுச்சொல்", "சரிபார்ப்பு குறியீடு", "உள்நுழை", "வங்கி கணக்கு", "அட்டை எண்", "பரிவர்த்தனை",
        "પાસવર્ડ", "ઓટીપી", "લૉગિન", "બેંક ખાતું", "નેટ બેંકિંગ", "કાર્ડ નંબર", "યુપીઆઈ પિન", "ખાતા નંબર",
        "పాస్‌వర్డ్", "ఓటిపి", "ధృవీకరణ కోడ్", "లాగిన్", "బ్యాంక్ ఖాతా", "కార్డ్ నంబర్", "యూపీఐ పిన్", "ఖాతా నంబర్",
        "পাসওয়ার্ড", "ওটিপি", "যাচাইকরণ কোড", "লগইন", "ব্যাংক অ্যাকাউন্ট", "কার্ড নম্বর", "ইউপিআই পিন", "অ্যাকাউন্ট নম্বর",
        "ਪਾਸਵਰਡ", "ਓਟੀਪੀ", "ਲਾਗਇਨ", "ਬੈਂਕ ਖਾਤਾ", "ਕਾਰਡ ਨੰਬਰ", "ਯੂਪੀਆਈ ਪਿੰਨ",
        "ಪಾಸ್‌ವರ್ಡ್", "ಒಟಿಪಿ", "ಲಾಗಿನ್", "ಬ್ಯಾಂಕ್ ಖಾತೆ", "ಕಾರ್ಡ್ ಸಂಖ್ಯೆ", "ಯುಪಿಐ ಪಿನ್",
        "പാസ്‌വേഡ്", "ഒടിപി", "ലോഗിൻ", "ബാങ്ക് അക്കൗണ്ട്", "കാർഡ് നമ്പർ", "യുപിഐ പിൻ",
        "پاس ورڈ", "او ٹی پی", "تصدیقی کوڈ", "لاگ ان", "بینک اکاؤنٹ", "کارڈ نمبر", "یو پی آئی پن",
    ).map(::normalize)
}
