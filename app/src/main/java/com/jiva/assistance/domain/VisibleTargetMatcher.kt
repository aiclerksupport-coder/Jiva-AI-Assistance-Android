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

    /** Exact normalized equality only; multiple nodes within one clickable control count as one target. */
    fun resolve(label: String, candidates: List<Candidate>): Resolution {
        val target = normalize(label)
        if (target.isEmpty()) return Resolution.NotFound
        val identities = candidates.asSequence()
            .filter { it.visible && it.clickable && it.enabled && it.targetIdentity.isNotBlank() }
            .filter { candidate -> candidate.labels.any { normalize(it) == target } }
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

    /** Best-effort blocklist for credential, account, and payment screens; never treated as secure-window detection. */
    fun looksLikePrivateOrCredentialScreen(visibleText: String): Boolean {
        val text = normalize(visibleText)
        return PRIVATE_SCREEN_TERMS.any { term -> text.contains(normalize(term)) }
    }

    /** High-impact visible labels require an additional review step in the UI. */
    fun isHighImpactLabel(label: String): Boolean {
        val text = normalize(label)
        return HIGH_IMPACT_TERMS.any { term -> text.contains(normalize(term)) }
    }

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)

    private val PRIVATE_SCREEN_TERMS = listOf(
        "password", "passcode", "pin code", "one-time password", "one time password", "otp", "verification code",
        "sign in", "log in", "login", "bank account", "banking", "credit card", "debit card", "card number",
        "upi pin", "payment pin", "wallet balance", "transaction password", "account number",
        "पासवर्ड", "पिन कोड", "ओटीपी", "एक बार का पासवर्ड", "लॉगिन", "साइन इन", "बैंक खाता", "बैंकिंग",
        "कार्ड नंबर", "यूपीआई पिन", "भुगतान पिन", "खाता नंबर", "लेनदेन पासवर्ड",
        "கடவுச்சொல்", "ஒரு முறை கடவுச்சொல்", "உள்நுழை", "வங்கி கணக்கு", "அட்டை எண்", "பரிவர்த்தனை",
        "પાસવર્ડ", "ઓટીપી", "લૉગિન", "બેંક ખાતું", "કાર્ડ નંબર", "યુપીઆઈ પિન",
        "పాస్‌వర్డ్", "ఓటిపి", "లాగిన్", "బ్యాంక్ ఖాతా", "కార్డ్ నంబర్", "యూపీఐ పిన్",
        "পাসওয়ার্ড", "ওটিপি", "লগইন", "ব্যাংক অ্যাকাউন্ট", "কার্ড নম্বর", "ইউপিআই পিন",
        "پاس ورڈ", "او ٹی پی", "لاگ ان", "بینک اکاؤنٹ", "کارڈ نمبر", "یو پی آئی پن",
    )

    private val HIGH_IMPACT_TERMS = listOf(
        "send", "post", "publish", "delete", "remove", "pay", "purchase", "buy", "transfer", "share", "submit",
        "भेज", "पोस्ट", "हटाएं", "मिटाएं", "भुगतान", "खरीद", "स्थानांतरण", "साझा", "सबमिट",
        "அனுப்பு", "வெளியிடு", "நீக்கு", "பணம் செலுத்து", "வாங்கு", "பகிர்",
        "મોકલો", "પોસ્ટ", "કાઢી નાખો", "ચૂકવણી", "ખરીદો", "શેર",
        "పంపు", "పోస్ట్", "తొలగించు", "చెల్లించు", "కొను", "షేర్",
        "পাঠান", "পোস্ট", "মুছুন", "পেমেন্ট", "কিনুন", "শেয়ার",
        "بھیج", "پوسٹ", "حذف", "ادائیگی", "خرید", "شیئر",
    )
}
