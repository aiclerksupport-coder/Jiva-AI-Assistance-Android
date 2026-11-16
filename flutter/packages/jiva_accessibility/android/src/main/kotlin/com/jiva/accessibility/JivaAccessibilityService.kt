package com.jiva.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import com.jiva.accessibility.WindowPackagePolicy
import android.os.Handler
import android.os.Build
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JivaAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRequest: PendingRequest? = null
    private var lastWorkflowPackage: String? = null
    private var verifyingAction = false
    private var verificationGeneration = 0
    private var screenOffRegistered = false
    // TYPE_ACCESSIBILITY_OVERLAY is owned by the explicitly enabled accessibility
    // service. Never requests SYSTEM_ALERT_WINDOW or launches hidden actions.
    private var floatingControls: View? = null
    private var floatingWindowManager: WindowManager? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            val request = pendingRequest ?: return
            pendingRequest = null
            lastWorkflowPackage = null
            handler.removeCallbacksAndMessages(null)
            report(request, emptyList(), "Screen turned off; pending action cancelled.")
        }
    }

    private fun unregisterScreenOffReceiver() {
        if (screenOffRegistered) {
            unregisterReceiver(screenOffReceiver)
            screenOffRegistered = false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (!screenOffRegistered) {
            // ACTION_SCREEN_OFF is an Android system protected broadcast;
            // a dynamic receiver cancels queued actions before next UI event.
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            screenOffRegistered = true
        }
        if (getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getBoolean(FLOATING_CONTROLS_KEY, false)) {
            if (getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .getBoolean(ENABLED_KEY, true)) {
                runCatching { setFloatingControls(true) }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val request = pendingRequest ?: return
        if (isPhoneLocked()) {
            pendingRequest = null
            handler.removeCallbacksAndMessages(null)
            report(request, emptyList(), "Phone locked; screen action cancelled.")
            return
        }
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage.isBlank() || eventPackage == packageName ||
            eventPackage == "com.android.settings" ||
            eventPackage == "com.android.systemui" ||
            eventPackage == "android"
        ) return
        if (request.type == REQUEST_OPEN_APP && eventPackage != request.value) return
        pendingRequest = null
        handler.removeCallbacksAndMessages(null)
        val root = rootInActiveWindow
        if (root == null) {
            report(request, emptyList(), "No active screen was available.")
            return
        }
        executeReviewedRequest(request, eventPackage, root, event.windowId)
    }

    private fun executeReviewedRequest(
        request: PendingRequest, eventPackage: String,
        root: AccessibilityNodeInfo, eventWindowId: Int,
    ) {
        try {
            ensureJivaEnabled()
            check(!isPhoneLocked()) { "Unlock the phone before screen assistance." }
            check(WindowPackagePolicy.isTrustedTarget(
                eventPackage, root.packageName?.toString(), eventWindowId, root.windowId,
            )) { "Active screen changed before Jiva could act. Please review and retry." }
            check(request.type == REQUEST_OPEN_APP || lastWorkflowPackage == null || request.operationId.isEmpty() ||
                lastWorkflowPackage == eventPackage) {
                "Target app changed. Stop and review before continuing."
            }
            if (containsPassword(root, 0) || looksPrivate(root, 0)) {
                throw IllegalStateException("Sensitive screen blocked; task paused.")
            }
            if (request.operationId.isNotEmpty()) lastWorkflowPackage = eventPackage
            when (request.type) {
                REQUEST_OPEN_APP -> report(request, emptyList(), "Verified reviewed app opened.")
                REQUEST_VERIFY_GOAL -> {
                    val expected = request.value.orEmpty().trim()
                    val visible = readVisibleScreen(root)
                    val exact = visible.any { it.trim().equals(expected, ignoreCase = true) }
                    report(request, emptyList(), if (exact) "Verified final goal on target screen."
                        else "Final goal text not found on target screen; task paused.")
                }
                REQUEST_READ -> report(request, readVisibleScreen(root), "Screen read locally.")
                REQUEST_SCREENSHOT -> captureReviewedScreen(root, eventPackage)
                else -> {
                    val before = readVisibleScreen(root).joinToString("\u241f")
                    val previousWindowId = root.windowId
                    val accepted = when (request.type) {
                        REQUEST_CLICK -> clickVisibleText(request.value.orEmpty(), root)
                        REQUEST_TYPE -> setFocusedText(request.value.orEmpty(), root)
                        else -> performScreenAction(request.type, root)
                    }
                    if (!accepted) {
                        report(request, emptyList(), "Action was rejected or target was ambiguous.")
                    } else {
                        // Android's performAction(true) is NOT task completion evidence.
                        // Give the UI time to settle; then collect an independent snapshot.
                        verifyingAction = true
                        val generation = ++verificationGeneration
                        handler.postDelayed({
                            if (generation == verificationGeneration) verifyActionOutcome(
                                request, eventPackage, previousWindowId, before,
                            )
                        }, 450L)
                    }
                }
            }
        } catch (error: Exception) {
            report(request, emptyList(), error.message ?: "Screen assistance failed.")
        }
    }

    private fun verifyActionOutcome(
        request: PendingRequest, expectedPackage: String,
        beforeWindowId: Int, beforeSnapshot: String,
    ) {
        verifyingAction = false
        try {
            check(!isPhoneLocked()) { "Phone locked; outcome unverified." }
            val root = rootInActiveWindow ?: error("Target screen missing; outcome unverified.")
            check(root.packageName?.toString() == expectedPackage) {
                "Another app appeared; outcome unverified."
            }
            check(!containsPassword(root, 0) && !looksPrivate(root, 0)) {
                "Sensitive screen appeared; outcome unverified."
            }
            val after = readVisibleScreen(root).joinToString("\u241f")
            val changed = root.windowId != beforeWindowId || after != beforeSnapshot
            val verified = when (request.type) {
                REQUEST_TYPE -> {
                    // Confirm the exact requested value appears on the local, non-private screen.
                    val expected = request.value.orEmpty()
                    expected.isNotEmpty() && after.contains(expected)
                }
                REQUEST_CLICK, ACTION_BACK, ACTION_SCROLL_FORWARD, ACTION_SCROLL_BACKWARD -> changed
                else -> false
            }
            report(request, emptyList(), if (verified)
                "Verified target screen changed." else
                "Action sent but no verifiable result. Inspect screen and re-plan.")
        } catch (error: Exception) {
            report(request, emptyList(), error.message ?: "Action outcome unverified.")
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        unregisterScreenOffReceiver()
        removeFloatingControls()
        if (instance === this) instance = null
        pendingRequest?.let {
            report(it, emptyList(), "Accessibility was disabled; request cancelled.")
        }
        pendingRequest = null
        lastWorkflowPackage = null
        verifyingAction = false
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        unregisterScreenOffReceiver()
        removeFloatingControls()
        if (instance === this) instance = null
        pendingRequest = null
        lastWorkflowPackage = null
        verifyingAction = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Explicit opt-in: controls remain visible over normal apps while this
     * accessibility service is connected. OS/service termination removes them.
     * No automated tap, screen read, screenshot or recording is triggered here.
     */
    fun setFloatingControls(enabled: Boolean) {
        if (!enabled) {
            removeFloatingControls()
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean(FLOATING_CONTROLS_KEY, false).apply()
            return
        }
        ensureJivaEnabled()
        if (floatingControls != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.rgb(24, 42, 48))
            contentDescription = "Jiva assistant floating controls"
        }
        fun control(label: String, action: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(16, 12, 16, 12)
            setOnClickListener { action() }
        }
        row.addView(control("Jiva") {
            // User-triggered navigation only. No background Activity auto-launch.
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (launch != null) runCatching { startActivity(launch) }
        })
        row.addView(control("Stop") {
            cancelPendingScreenAction("Pending action cancelled from floating control.")
        })
        row.addView(control("×") { setFloatingControls(false) })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 12; y = 160 }
        try {
            wm.addView(row, params)
            floatingWindowManager = wm
            floatingControls = row
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean(FLOATING_CONTROLS_KEY, true).apply()
        } catch (error: Exception) {
            removeFloatingControls()
            throw IllegalStateException("Could not show accessibility overlay: ${error.message}", error)
        }
    }

    private fun removeFloatingControls() {
        val view = floatingControls
        floatingControls = null
        if (view != null) runCatching { floatingWindowManager?.removeViewImmediate(view) }
        floatingWindowManager = null
    }

    fun cancelPendingScreenAction(reason: String = "Pending action cancelled by user.") {
        val request = pendingRequest
        pendingRequest = null
        lastWorkflowPackage = null
        verifyingAction = false
        verificationGeneration++
        handler.removeCallbacksAndMessages(null)
        if (request != null) report(request, emptyList(), reason)
    }

    fun requestReviewedScreenshot() {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Screenshot capture requires Android 11 or newer."
        }
        queueRequest(PendingRequest(REQUEST_SCREENSHOT))
    }

    /** Single screenshot from a target app, only after the user's explicit per-shot approval.
     * Never persisted or sent to a remote API. Secure windows are rejected by Android.
     */
    private fun captureReviewedScreen(root: AccessibilityNodeInfo, targetPackage: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            report(REQUEST_SCREENSHOT, emptyList(), "Android 11+ required for screen capture.")
            return
        }
        if (containsPassword(root, 0) || looksPrivate(root, 0) || isPhoneLocked()) {
            report(REQUEST_SCREENSHOT, emptyList(), "Private/locked screen capture blocked.")
            return
        }
        val capturedWindowId = root.windowId
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    try {
                        if (isPhoneLocked() || pendingRequest != null) {
                            report(REQUEST_SCREENSHOT, emptyList(), "Screen changed; capture discarded.")
                            return
                        }
                        val currentRoot = rootInActiveWindow
                        if (currentRoot == null || currentRoot.windowId != capturedWindowId ||
                            currentRoot.packageName?.toString() != targetPackage ||
                            containsPassword(currentRoot, 0) || looksPrivate(currentRoot, 0)) {
                            report(REQUEST_SCREENSHOT, emptyList(), "Screen changed or is private; capture discarded.")
                            return
                        }
                        val original = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?: throw IllegalStateException("Screenshot buffer was unavailable.")
                        // Copy off the hardware buffer so it can be closed safely.
                        val width = minOf(960, original.width)
                        val height = (original.height.toDouble() * width / original.width).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createScaledBitmap(original, width, height, true)
                        val jpeg = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 65, jpeg)
                        if (bitmap !== original) bitmap.recycle()
                        val bytes = jpeg.toByteArray()
                        check(bytes.size <= 600_000) { "Screenshot exceeds local preview size limit." }
                        JivaAccessibilityPlugin.reportReviewedScreenshot(
                            Base64.encodeToString(bytes, Base64.NO_WRAP), targetPackage)
                    } catch (error: Exception) {
                        report(REQUEST_SCREENSHOT, emptyList(), error.message ?: "Capture failed.")
                    } finally {
                        buffer.close()
                    }
                }
                override fun onFailure(errorCode: Int) {
                    report(REQUEST_SCREENSHOT, emptyList(),
                        "Android blocked the screenshot (code $errorCode). Secure windows are not captured.")
                }
            })
    }

    fun requestVisibleScreenRead(operationId: String = "") {
        queueRequest(PendingRequest(REQUEST_READ, operationId = operationId))
    }

    fun queueVisibleTextClick(label: String, operationId: String = "") {
        val normalized = normalize(label)
        require(normalized.isNotEmpty()) { "Enter the visible button text to click." }
        queueRequest(PendingRequest(REQUEST_CLICK, normalized, operationId))
    }

    fun queueFocusedTextInput(value: String, operationId: String = "") {
        require(value.isNotBlank() && value.length <= 100 &&
            value.none { java.lang.Character.isISOControl(it) }) { "Text must be 1-100 safe characters." }
        queueRequest(PendingRequest(REQUEST_TYPE, value, operationId))
    }

    /** Never type into arbitrary fields: exactly one focused, visible editable node. */
    private fun setFocusedText(text: String, root: AccessibilityNodeInfo): Boolean {
        if (containsPassword(root, 0) || looksPrivate(root, 0)) return false
        val focused = mutableListOf<AccessibilityNodeInfo>()
        collectFocusedEditors(root, focused, 0)
        if (focused.size != 1) return false
        val target = focused.single()
        if (!target.isEnabled || !target.isVisibleToUser || target.isPassword) return false
        if (!target.isEditable || !target.isFocused) return false
        val arguments = android.os.Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun collectFocusedEditors(
        node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>, depth: Int,
    ) {
        if (depth > MAX_DEPTH || !node.isVisibleToUser || node.isPassword || result.size > 1) return
        if (node.isEditable && node.isFocused) result.add(node)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectFocusedEditors(it, result, depth + 1) }
            if (result.size > 1) return
        }
    }

    /** Only launches a reviewed installed launcher; Android confirms package via
     * TYPE_WINDOW_STATE_CHANGED before reporting success. Never crosses into
     * private, system or Jiva screens. No blind retry on timeout. */
    fun launchReviewedApp(targetPackage: String, operationId: String) {
        require(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(targetPackage)) {
            "Invalid installed app package."
        }
        require(targetPackage != packageName && targetPackage !in setOf(
            "com.android.settings", "com.android.systemui", "android")) {
            "Protected app cannot be a workflow target."
        }
        require(Regex("^[0-9a-f]{32}$").matches(operationId)) { "Invalid operation ID." }
        ensureJivaEnabled()
        check(!isPhoneLocked() && pendingRequest == null && !verifyingAction) {
            "Unlock device or finish previous action first."
        }
        val intent = packageManager.getLaunchIntentForPackage(targetPackage)
            ?: throw IllegalStateException("Reviewed installed app is not launchable.")
        lastWorkflowPackage = null // Only explicit, approved transition resets binding.
        val request = PendingRequest(REQUEST_OPEN_APP, targetPackage, operationId)
        pendingRequest = request
        handler.postDelayed({
            if (pendingRequest === request) {
                pendingRequest = null
                report(request, emptyList(), "App launch not verified within timeout.")
            }
        }, REQUEST_TIMEOUT_MILLIS)
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Some launchers resume an already-open Activity without emitting a
            // WINDOW_STATE_CHANGED event. Check the actual foreground package
            // independently rather than assuming startActivity means success.
            handler.postDelayed({
                if (pendingRequest === request) {
                    val root = rootInActiveWindow
                    if (root?.packageName?.toString() == targetPackage &&
                        !isPhoneLocked()) {
                        pendingRequest = null
                        executeReviewedRequest(request, targetPackage, root, root.windowId)
                    }
                }
            }, 850L)
        } catch (error: Exception) {
            if (pendingRequest === request) pendingRequest = null
            report(request, emptyList(), "Reviewed app launch failed.")
        }
    }

    fun verifyVisibleGoal(text: String, operationId: String) {
        val expected = text.trim()
        require(expected.isNotEmpty() && expected.length <= 100 &&
            expected.none { it.code < 32 }) { "Invalid expected result text." }
        queueRequest(PendingRequest(REQUEST_VERIFY_GOAL, expected, operationId))
    }

    fun queueScreenAction(action: String, operationId: String = "") {
        require(action in setOf(ACTION_BACK, ACTION_SCROLL_FORWARD, ACTION_SCROLL_BACKWARD)) {
            "Unsupported screen action."
        }
        queueRequest(PendingRequest(action, operationId = operationId))
    }

    private fun queueRequest(request: PendingRequest) {
        require(request.operationId.isEmpty() || Regex("^[0-9a-f]{32}$").matches(request.operationId)) {
            "Invalid workflow operation ID."
        }
        ensureJivaEnabled()
        check(!isPhoneLocked()) { "Unlock the phone before requesting screen assistance." }
        // A second tap cannot silently replace an earlier confirmed action.
        // One user-reviewed request owns the queue until result or timeout.
        check(pendingRequest == null) {
            "Finish the previous screen action before starting another."
        }
        check(!verifyingAction) { "Previous action is still being verified." }
        pendingRequest = request
        handler.removeCallbacksAndMessages(null)
        // Subsequent approved workflow actions can run against the *same* visible
        // target app. The initial action still requires switching from Jiva.
        val current = rootInActiveWindow
        val boundPackage = lastWorkflowPackage
        if (request.operationId.isNotEmpty() && boundPackage != null && current != null &&
            current.packageName?.toString() == boundPackage &&
            !isPhoneLocked() && !containsPassword(current, 0) && !looksPrivate(current, 0)) {
            pendingRequest = null
            handler.postDelayed({
                val fresh = rootInActiveWindow
                if (fresh == null || fresh.packageName?.toString() != boundPackage) {
                    report(request, emptyList(), "Target app changed before action; paused.")
                } else {
                    executeReviewedRequest(request, boundPackage, fresh, fresh.windowId)
                }
            }, 120L)
            return
        }
        handler.postDelayed({
            if (pendingRequest === request) {
                pendingRequest = null
                report(request, emptyList(), "No target app was opened within 30 seconds.")
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

    private fun report(request: PendingRequest, text: List<String>, message: String) {
        JivaAccessibilityPlugin.reportScreenAssistResult(
            request.type, text, message, request.operationId,
        )
    }

    private data class PendingRequest(
        val type: String,
        val value: String? = null,
        val operationId: String = "",
    )

    companion object {
        private const val REQUEST_OPEN_APP = "open_app"
        private const val REQUEST_VERIFY_GOAL = "verify_text"
        private const val REQUEST_READ = "read"
        private const val REQUEST_SCREENSHOT = "screenshot"
        private const val REQUEST_CLICK = "click"
        private const val REQUEST_TYPE = "type"
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
        const val FLOATING_CONTROLS_KEY = "floating_controls_enabled"
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
