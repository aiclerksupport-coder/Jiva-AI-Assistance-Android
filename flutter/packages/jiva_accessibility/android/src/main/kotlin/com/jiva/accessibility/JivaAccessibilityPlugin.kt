package com.jiva.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class JivaAccessibilityPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    private lateinit var context: Context
    private lateinit var channel: MethodChannel
    private lateinit var speechChannel: MethodChannel
    private var speechEngine: JivaSpeechEngine? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "com.jiva/accessibility")
        channel.setMethodCallHandler(this)
        speechChannel = MethodChannel(binding.binaryMessenger, "com.jiva/speech")
        speechEngine = JivaSpeechEngine(context, speechChannel)
        speechChannel.setMethodCallHandler(speechEngine)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        speechChannel.setMethodCallHandler(null)
        speechEngine?.release()
        speechEngine = null
        if (resultChannel === channel) resultChannel = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        resultChannel = channel
    }

    override fun onDetachedFromActivityForConfigChanges() {
        if (resultChannel === channel) resultChannel = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        resultChannel = channel
    }

    override fun onDetachedFromActivity() {
        if (resultChannel === channel) resultChannel = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "isEnabled" -> result.success(isServiceEnabled())
                "openSettings" -> {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    result.success(null)
                }
                "setJivaEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled")
                        ?: throw IllegalArgumentException("Enabled state is required.")
                    context.getSharedPreferences(
                        JivaAccessibilityService.PREFERENCES,
                        Context.MODE_PRIVATE,
                    ).edit().putBoolean(JivaAccessibilityService.ENABLED_KEY, enabled).apply()
                    result.success(null)
                }
                "requestVisibleScreenRead" -> {
                    service().requestVisibleScreenRead()
                    result.success(null)
                }
                "queueVisibleTextClick" -> {
                    val text = call.argument<String>("text")
                        ?: throw IllegalArgumentException("Visible label is required.")
                    service().queueVisibleTextClick(text)
                    result.success(null)
                }
                "queueScreenAction" -> {
                    val action = call.argument<String>("action")
                        ?: throw IllegalArgumentException("Screen action is required.")
                    service().queueScreenAction(action)
                    result.success(null)
                }
                "performPhoneNavigation" -> {
                    val action = call.argument<String>("action")
                        ?: throw IllegalArgumentException("Phone navigation action is required.")
                    result.success(service().performPhoneNavigation(action))
                }
                else -> result.notImplemented()
            }
        } catch (error: Exception) {
            result.error("JIVA_SCREEN_ASSIST", error.message, null)
        }
    }

    private fun service(): JivaAccessibilityService =
        JivaAccessibilityService.instance
            ?: throw IllegalStateException("Enable Jiva screen assistance in Android Accessibility Settings.")

    private fun isServiceEnabled(): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        ).any { it.resolveInfo.serviceInfo.packageName == context.packageName &&
            it.resolveInfo.serviceInfo.name == JivaAccessibilityService::class.java.name }
    }

    companion object {
        @Volatile
        private var resultChannel: MethodChannel? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        fun reportScreenAssistResult(
            type: String,
            text: List<String>,
            message: String,
        ) {
            mainHandler.post {
                resultChannel?.invokeMethod(
                    "screenAssistResult",
                    mapOf("type" to type, "text" to text, "message" to message),
                )
            }
        }
    }
}
