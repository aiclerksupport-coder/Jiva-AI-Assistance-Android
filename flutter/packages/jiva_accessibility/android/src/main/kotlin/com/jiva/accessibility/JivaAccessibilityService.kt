package com.jiva.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import com.jiva.accessibility.WindowPackagePolicy
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JivaAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRequest: PendingRequest? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val request = pendingRequest ?: return
        if (isPhoneLocked()) {
            pendingRequest = null
            handler.removeCallbacksAndMessages(null)
            report(request.type, emptyList(), "Phone locked; screen action cancelled.")
            return
        }
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage.isBlank() || eventPackage == packageName ||
            eventPackage == "com.android.settings" ||
            eventPackage == "com.android.systemui" ||
            eventPackage == "android"
        ) return
        pendingRequest = null
        handler.removeCallbacksAndMessages(null)
        val root = rootInActiveWindow
        if (root == null) {
            report(request.type, emptyList(), "No active screen was available.")
            return
        }
        try {
            ensureJivaEnabled()
            check(!isPhoneLocked()) { "Unlock the phone before screen assistance." }
            check(WindowPackagePolicy.isTrustedTarget(
                eventPackage, root.packageName?.toString(), event.windowId, root.windowId,
            )) {
                "Active screen changed before Jiva could act. Please review and retry."
            }
            if (containsPassword(root, 0) || looksPrivate(root, 0)) {
                throw IllegalStateException(
                    "Screen assistance is blocked on a password, login, banking, or payment screen.",
                )
            }
            when (request.type) {
                REQUEST_READ -> report(request.type, readVisibleScreen(root), "Screen read locally.")
                REQUEST_CLICK -> {
                    val clicked = clickVisibleText(request.value.orEmpty(), root)
                    report(
                        request.type,
                        emptyList(),
                        if (clicked) "Visible control clicked."
                        else "The label was not unique or clickable.",
                    )
                }
                else -> {
                    val completed = performScreenAction(request.type, root)
                    report(
                        request.type,
                        emptyList(),
                        if (completed) "Screen action completed."
                        else "No matching screen action was available.",
                    )
                }
            }
        } catch (error: Exception) {
            report(request.type, emptyList(), error.message ?: "Screen assistance failed.")
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        pendingRequest?.let {
            report(it.type, emptyList(), "Accessibility was disabled; request cancelled.")
        }
        pendingRequest = null
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        pendingRequest = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    fun requestVisibleScreenRead() {
        queueRequest(PendingRequest(REQUEST_READ))
    }

    fun queueVisibleTextClick(label: String) {
        val normalized = normalize(label)
        require(normalized.isNotEmpty()) { "Enter the visible button text to click." }
        queueRequest(PendingRequest(REQUEST_CLICK, normalized))
    }

    fun queueScreenAction(action: String) {
        require(action in setOf(ACTION_BACK, ACTION_SCROLL_FORWARD, ACTION_SCROLL_BACKWARD)) {
            "Unsupported screen action."
        }
        queueRequest(PendingRequest(action))
    }

    private fun queueRequest(request: PendingRequest) {
        ensureJivaEnabled()
        check(!isPhoneLocked()) { "Unlock the phone before requesting screen assistance." }
        // A second tap cannot silently replace an earlier confirmed action.
        // One user-reviewed request owns the queue until result or timeout.
        check(pendingRequest == null) {
            "Finish the previous screen action before starting another."
        }
        pendingRequest = request
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (pendingRequest === request) {
                pendingRequest = null
                report(request.type, emptyList(), "No target app was opened within 30 seconds.")
            }
        }, REQUEST_TIMEOUT_MILLIS)
    }

    private fun readVisibleScreen(root: AccessibilityNodeInfo): List<String> {
        val lines = linkedSetOf<String>()
        walk(root, lines, 0)
        return lines.take(MAX_LINES)
    }

    fun clickVisibleText(label: String): Boolean {
        ensureJivaEnabled()
        if (isPhoneLocked()) return false
        val root = rootInActiveWindow ?: return false
        return clickVisibleText(label, root)
    }

    private fun clickVisibleText(label: String, root: AccessibilityNodeInfo): Boolean {
        ensureJivaEnabled()
        val normalized = normalize(label)
        require(normalized.isNotEmpty()) { "Enter the visible button text to click." }
        if (containsPassword(root, 0) || looksPrivate(root, 0)) {
            throw IllegalStateException("Screen clicks are blocked on private or credential screens.")
        }
        val matches = mutableListOf<AccessibilityNodeInfo>()
        findMatches(root, normalized, matches, 0)
        if (matches.size != 1) return false
        var target = matches.single()
        while (!target.isClickable) {
            target = target.parent ?: return false
        }
        if (!target.isEnabled || !target.isVisibleToUser || target.isPassword) return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun performScreenAction(action: String): Boolean {
        ensureJivaEnabled()
        if (isPhoneLocked()) return false
        val root = rootInActiveWindow ?: return false
        return performScreenAction(action, root)
    }

    private fun performScreenAction(action: String, root: AccessibilityNodeInfo): Boolean {
        ensureJivaEnabled()
        if (containsPassword(root, 0) || looksPrivate(root, 0)) {
            throw IllegalStateException("Screen actions are blocked on private or credential screens.")
        }
        return when (action) {
            ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ACTION_SCROLL_FORWARD -> findScrollable(root, 0)?.performAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            ) ?: false
            ACTION_SCROLL_BACKWARD -> findScrollable(root, 0)?.performAction(
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            ) ?: false
            else -> false
        }
    }

    fun performPhoneNavigation(action: String): Boolean {
        ensureJivaEnabled()
        if (isPhoneLocked()) return false
        return when (action) {
            PHONE_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            PHONE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            PHONE_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            PHONE_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            PHONE_QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            else -> false
        }
    }

    private fun containsPassword(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return true
        if (!node.isVisibleToUser) return false
        if (node.isPassword) return true
        for (index in 0 until node.childCount) {
            if (node.getChild(index)?.let { containsPassword(it, depth + 1) } == true) {
                return true
            }
        }
        return false
    }

    /** Defense in depth: text heuristics cannot certify every Android window as safe. */
    private fun looksPrivate(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return true
        if (!node.isVisibleToUser) return false
        val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
        if (labels.any(::containsPrivateTerm)) return true
        for (index in 0 until node.childCount) {
            if (node.getChild(index)?.let { looksPrivate(it, depth + 1) } == true) return true
        }
        return false
    }

    private fun containsPrivateTerm(value: String): Boolean {
        val text = normalize(value)
        return PRIVATE_SCREEN_TERMS.any { term ->
            if (term.any { it.code > 127 }) {
                text.contains(term)
            } else {
                Regex("(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])")
                    .containsMatchIn(text)
            }
        }
    }

    private fun findScrollable(
        node: AccessibilityNodeInfo,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH || !node.isVisibleToUser || node.isPassword) return null
        if (node.isScrollable) return node
        for (index in 0 until node.childCount) {
            val result = node.getChild(index)?.let { findScrollable(it, depth + 1) }
            if (result != null) return result
        }
        return null
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        lines: MutableSet<String>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH || !node.isVisibleToUser || node.isPassword) return
        node.text?.toString()?.let { addVisibleText(it, lines) }
        node.contentDescription?.toString()?.let { addVisibleText(it, lines) }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { walk(it, lines, depth + 1) }
            if (lines.size >= MAX_LINES) return
        }
    }

    private fun addVisibleText(value: String, lines: MutableSet<String>) {
        val safe = value.trim().take(MAX_TEXT_LENGTH)
        if (safe.isNotEmpty() && lines.size < MAX_LINES) lines += safe
    }

    private fun findMatches(
        node: AccessibilityNodeInfo,
        target: String,
        matches: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH || !node.isVisibleToUser || node.isPassword) return
        val labels = listOf(
            normalize(node.text?.toString().orEmpty()),
            normalize(node.contentDescription?.toString().orEmpty()),
        )
        if (target in labels && hasClickableAncestor(node)) matches += node
        if (matches.size > 1) return
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { findMatches(it, target, matches, depth + 1) }
            if (matches.size > 1) return
        }
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_DEPTH) {
            if (current?.isClickable == true) return true
            current = current?.parent
        }
        return false
    }

    private fun isPhoneLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    private fun ensureJivaEnabled() {
        check(
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(ENABLED_KEY, true),
        ) { "Turn Jiva on before using screen assistance." }
    }

    private fun normalize(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun report(type: String, text: List<String>, message: String) {
        JivaAccessibilityPlugin.reportScreenAssistResult(type, text, message)
    }

    private data class PendingRequest(val type: String, val value: String? = null)

    companion object {
        private const val REQUEST_READ = "read"
        private const val REQUEST_CLICK = "click"
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L
        const val ACTION_BACK = "back"
        const val ACTION_SCROLL_FORWARD = "scroll_forward"
        const val ACTION_SCROLL_BACKWARD = "scroll_backward"
        const val PHONE_HOME = "home"
        const val PHONE_BACK = "back"
        const val PHONE_RECENTS = "recents"
        const val PHONE_NOTIFICATIONS = "notifications"
        const val PHONE_QUICK_SETTINGS = "quick_settings"
        const val PREFERENCES = "jiva_screen_assist"
        const val ENABLED_KEY = "enabled"
        // Curated denylist; intentionally conservative for private/account screens.
        private val PRIVATE_SCREEN_TERMS = listOf(
            "password", "passcode", "otp", "verification code", "pin code", "enter pin",
            "login", "log in", "sign in", "payment", "pay now", "checkout",
            "bank account", "banking", "net banking", "card number", "cvv", "cvc",
            "upi pin", "payment pin", "account number", "account recovery",
            "पासवर्ड", "ओटीपी", "लॉगिन", "भुगतान", "यूपीआई पिन", "बैंक खाता", "खाता नंबर",
            "પાસવર્ડ", "ઓટીપી", "બેંક ખાતું", "યુપીઆઈ પિન",
            "கடவுச்சொல்", "வங்கி கணக்கு", "ஒரு முறை கடவுச்சொல்",
            "పాస్‌వర్డ్", "బ్యాంక్ ఖాతా", "ఓటిపి",
            "পাসওয়ার্ড", "ওটিপি", "ব্যাংক অ্যাকাউন্ট",
            "ਪਾਸਵਰਡ", "ਓਟੀਪੀ", "ਬੈਂਕ ਖਾਤਾ",
            "پاس ورڈ", "او ٹی پی", "بینک اکاؤنٹ",
        ).map { it.lowercase(java.util.Locale.ROOT) }

        private const val MAX_DEPTH = 20
        private const val MAX_LINES = 150
        private const val MAX_TEXT_LENGTH = 160

        @Volatile
        var instance: JivaAccessibilityService? = null
            private set
    }
}
