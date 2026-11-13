package com.jiva.assistance.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jiva.assistance.domain.VisibleTargetMatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** User-initiated, one-shot assistance for the currently visible app screen. */
class JivaAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRequest: PendingRequest? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val request = pendingRequest ?: return
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage.isBlank() || eventPackage == packageName ||
            eventPackage == "com.android.settings" || eventPackage == "com.android.systemui" || eventPackage == "android"
        ) return

        pendingRequest = null
        handler.removeCallbacksAndMessages(null)
        val root = rootInActiveWindow
        if (root == null) {
            report(ScreenAssistResult(request.type, emptyList(), "No active screen was available."))
            return
        }
        try {
            ensureJivaEnabled()
            if (containsPassword(root, 0)) {
                throw IllegalStateException("Screen assistance is blocked while a password field is visible.")
            }
            if (looksPrivate(root, 0)) {
                throw IllegalStateException("Screen assistance is blocked on a screen that appears to contain login, account, or payment information.")
            }
            when (request.type) {
                REQUEST_READ -> report(ScreenAssistResult(request.type, walkVisibleText(root), "Screen read locally; text stays on this device."))
                REQUEST_CLICK -> {
                    val clicked = clickVisibleText(request.value.orEmpty())
                    val appLabel = applicationLabel(eventPackage)
                    val result = if (clicked) {
                        val impact = if (VisibleTargetMatcher.isHighImpactLabel(request.value.orEmpty())) {
                            " This was a high-impact control; review the outcome in the app."
                        } else ""
                        "Clicked the exact visible control “${request.value}” in $appLabel after confirmation.$impact"
                    } else {
                        "No action taken: that exact label was absent, disabled, non-clickable, or ambiguous in $appLabel."
                    }
                    report(ScreenAssistResult(request.type, emptyList(), result))
                }
                else -> {
                    val completed = performScreenAction(request.type)
                    report(ScreenAssistResult(request.type, emptyList(), if (completed) "Visible screen action completed." else "No matching screen action was available."))
                }
            }
        } catch (error: Exception) {
            report(ScreenAssistResult(request.type, emptyList(), error.message ?: "Screen assistance failed."))
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        pendingRequest?.let { report(ScreenAssistResult(it.type, emptyList(), "Accessibility was disabled; request cancelled.")) }
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

    fun requestVisibleScreenRead() = queueRequest(PendingRequest(REQUEST_READ))

    fun queueVisibleTextClick(label: String) {
        val normalized = VisibleTargetMatcher.normalize(label)
        require(normalized.isNotEmpty()) { "Enter the exact visible button text to click." }
        require(normalized.length <= MAX_TEXT_LENGTH) { "Target label is too long." }
        queueRequest(PendingRequest(REQUEST_CLICK, normalized))
    }

    fun queueScreenAction(action: String) {
        require(action in setOf(ACTION_BACK, ACTION_SCROLL_FORWARD, ACTION_SCROLL_BACKWARD)) { "Unsupported screen action." }
        queueRequest(PendingRequest(action))
    }

    private fun queueRequest(request: PendingRequest) {
        ensureJivaEnabled()
        pendingRequest = request
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (pendingRequest === request) {
                pendingRequest = null
                report(ScreenAssistResult(request.type, emptyList(), "No target app was opened within 30 seconds; no action was taken."))
            }
        }, REQUEST_TIMEOUT_MILLIS)
    }

    private fun walkVisibleText(root: AccessibilityNodeInfo): List<String> {
        val lines = linkedSetOf<String>()
        walk(root, lines, 0)
        return lines.take(MAX_LINES)
    }

    /** Exact label, unique clickable ancestor, enabled, current screen only. */
    fun clickVisibleText(label: String): Boolean {
        ensureJivaEnabled()
        val normalized = VisibleTargetMatcher.normalize(label)
        require(normalized.isNotEmpty()) { "Enter the exact visible button text to click." }
        val root = rootInActiveWindow ?: return false
        if (containsPassword(root, 0) || looksPrivate(root, 0)) return false

        val targets = linkedMapOf<String, AccessibilityNodeInfo>()
        collectExactTargets(root, normalized, targets, 0)
        val resolution = VisibleTargetMatcher.resolve(
            normalized,
            targets.map { (identity, node) ->
                VisibleTargetMatcher.Candidate(
                    labels = setOf(normalized),
                    targetIdentity = identity,
                    visible = node.isVisibleToUser,
                    clickable = node.isClickable,
                    enabled = node.isEnabled,
                )
            },
        )
        val identity = (resolution as? VisibleTargetMatcher.Resolution.Unique)?.targetIdentity ?: return false
        val target = targets[identity] ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun performScreenAction(action: String): Boolean {
        ensureJivaEnabled()
        val root = rootInActiveWindow ?: return false
        if (containsPassword(root, 0) || looksPrivate(root, 0)) return false
        return when (action) {
            ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ACTION_SCROLL_FORWARD -> findScrollable(root, 0)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
            ACTION_SCROLL_BACKWARD -> findScrollable(root, 0)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false
            else -> false
        }
    }

    fun performPhoneNavigation(action: String): Boolean {
        ensureJivaEnabled()
        return when (action) {
            PHONE_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            PHONE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            PHONE_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            PHONE_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            PHONE_QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            else -> false
        }
    }

    private fun collectExactTargets(
        node: AccessibilityNodeInfo,
        target: String,
        matches: MutableMap<String, AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH || !node.isVisibleToUser || node.isPassword) return
        val labels = listOf(node.text?.toString(), node.contentDescription?.toString())
            .filterNotNull()
            .map(VisibleTargetMatcher::normalize)
        if (target in labels) {
            findClickableAncestor(node)?.let { clickable ->
                if (clickable.isVisibleToUser && clickable.isEnabled) {
                    matches.putIfAbsent(targetIdentity(clickable), clickable)
                }
            }
        }
        if (matches.size > 1) return
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectExactTargets(it, target, matches, depth + 1) }
            if (matches.size > 1) return
        }
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_DEPTH) {
            val candidate = current ?: return null
            if (candidate.isClickable) return candidate.takeIf { it.isEnabled && it.isVisibleToUser }
            current = candidate.parent
        }
        return null
    }

    private fun targetIdentity(node: AccessibilityNodeInfo): String {
        val rect = android.graphics.Rect().also(node::getBoundsInScreen)
        return "${node.windowId}:${node.viewIdResourceName.orEmpty()}:${rect.flattenToString()}"
    }

    private fun findScrollable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH || !node.isVisibleToUser) return null
        if (node.isScrollable && node.isEnabled) return node
        for (index in 0 until node.childCount) {
            val result = node.getChild(index)?.let { findScrollable(it, depth + 1) }
            if (result != null) return result
        }
        return null
    }

    private fun containsPassword(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return true
        if (!node.isVisibleToUser) return false
        if (node.isPassword) return true
        for (index in 0 until node.childCount) {
            if (node.getChild(index)?.let { containsPassword(it, depth + 1) } == true) return true
        }
        return false
    }

    private fun looksPrivate(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_DEPTH || !node.isVisibleToUser) return false
        val visibleText = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ")
        if (VisibleTargetMatcher.looksLikePrivateOrCredentialScreen(visibleText)) return true
        for (index in 0 until node.childCount) {
            if (node.getChild(index)?.let { looksPrivate(it, depth + 1) } == true) return true
        }
        return false
    }

    private fun walk(node: AccessibilityNodeInfo, lines: MutableSet<String>, depth: Int) {
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

    private fun ensureJivaEnabled() {
        check(getSharedPreferences("jiva_settings", MODE_PRIVATE).getBoolean("assistant_enabled", true)) {
            "Turn Jiva on before using screen assistance."
        }
    }

    private fun applicationLabel(packageName: String): String = runCatching {
        val app = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(app).toString()
    }.getOrDefault("the selected app")

    private fun report(result: ScreenAssistResult) { screenAssistResults.tryEmit(result) }

    private data class PendingRequest(val type: String, val value: String? = null)

    companion object {
        private const val REQUEST_READ = "read"
        private const val REQUEST_CLICK = "click"
        const val ACTION_BACK = "back"
        const val ACTION_SCROLL_FORWARD = "scroll_forward"
        const val ACTION_SCROLL_BACKWARD = "scroll_backward"
        const val PHONE_HOME = "home"
        const val PHONE_BACK = "back"
        const val PHONE_RECENTS = "recents"
        const val PHONE_NOTIFICATIONS = "notifications"
        const val PHONE_QUICK_SETTINGS = "quick_settings"
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L
        private const val MAX_DEPTH = 20
        private const val MAX_LINES = 150
        private const val MAX_TEXT_LENGTH = 160

        @Volatile var instance: JivaAccessibilityService? = null
            private set

        private val screenAssistResults = MutableSharedFlow<ScreenAssistResult>(extraBufferCapacity = 1)
        val results = screenAssistResults.asSharedFlow()
    }
}

data class ScreenAssistResult(val type: String, val text: List<String>, val message: String)
