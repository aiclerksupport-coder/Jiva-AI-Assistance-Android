package com.jiva.assistance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jiva.assistance.data.ApiKeyStore
import com.jiva.assistance.data.GeminiLiveClient
import com.jiva.assistance.data.GeminiRepository
import com.jiva.assistance.data.GeminiRetryPolicy
import com.jiva.assistance.domain.AssistantAction
import com.jiva.assistance.domain.AssistantUiState
import com.jiva.assistance.domain.IntentPlan
import com.jiva.assistance.domain.LocalCommandParser
import com.jiva.assistance.domain.PhoneNavigationActions
import com.jiva.assistance.domain.ReminderItem
import com.jiva.assistance.domain.VoicePresets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val parser = LocalCommandParser()
    private val gemini = GeminiRepository()
    private val geminiLive = GeminiLiveClient()
    private val keyStore = ApiKeyStore(application)
    private val preferences = application.getSharedPreferences("jiva_settings", Application.MODE_PRIVATE)
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()
    private var idleSleepJob: Job? = null
    private var commandJob: Job? = null

    init {
        _state.value = _state.value.copy(
            selectedVoice = VoicePresets.find(preferences.getString("voice", VoicePresets.default.voiceName).orEmpty()).voiceName,
            enabled = preferences.getBoolean("assistant_enabled", true),
            noiseProcessingEnabled = preferences.getBoolean("noise_processing_enabled", true),
            patientListeningEnabled = preferences.getBoolean("patient_listening_enabled", true),
        )
        scheduleSleepTimer()
    }

    fun setInput(value: String) {
        _state.value = _state.value.copy(input = value, isSleeping = false)
        scheduleSleepTimer()
    }
    fun showReply(value: String) {
        _state.value = _state.value.copy(
            reply = value,
            busy = false,
            listening = false,
            isSleeping = false,
        )
        scheduleSleepTimer()
    }
    fun showGeminiRetryStatus(operation: String, attempt: Int) {
        if (_state.value.enabled) {
            _state.value = _state.value.copy(
                reply = "$operation interrupted. Retrying (${attempt + 1}/${GeminiRetryPolicy.MAX_ATTEMPTS})…",
            )
        }
    }
    fun updatePlan(plan: IntentPlan?) {
        _state.value = _state.value.copy(pendingPlan = plan, isSleeping = false)
        scheduleSleepTimer()
    }
    fun dismissPlan() {
        _state.value = _state.value.copy(pendingPlan = null, isSleeping = false)
        scheduleSleepTimer()
    }
    /** Claim exactly the displayed pending plan before a user-visible handoff. */
    fun consumePendingPlan(expected: IntentPlan): Boolean {
        if (_state.value.pendingPlan != expected) return false
        dismissPlan()
        return true
    }
    fun showReminderEditor() {
        idleSleepJob?.cancel()
        _state.value = _state.value.copy(showReminderEditor = true, isSleeping = false)
    }
    fun closeReminderEditor() {
        _state.value = _state.value.copy(showReminderEditor = false, isSleeping = false)
        scheduleSleepTimer()
    }
    fun setReminders(items: List<ReminderItem>) { _state.value = _state.value.copy(reminders = items) }
    fun setListening(value: Boolean) {
        if (value && !_state.value.enabled) return
        _state.value = _state.value.copy(listening = value, isSleeping = false)
        if (value) idleSleepJob?.cancel() else scheduleSleepTimer()
    }
    fun setNoiseProcessingEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("noise_processing_enabled", enabled).apply()
        _state.value = _state.value.copy(noiseProcessingEnabled = enabled)
    }
    fun setPatientListeningEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("patient_listening_enabled", enabled).apply()
        _state.value = _state.value.copy(patientListeningEnabled = enabled)
    }
    fun setAudioProcessingStatus(status: String) {
        _state.value = _state.value.copy(audioProcessingStatus = status)
    }
    fun setScreenAssistPending(value: Boolean) {
        if (value && !_state.value.enabled) return
        _state.value = _state.value.copy(
            screenAssistPending = value,
            isSleeping = false,
        )
        if (value) idleSleepJob?.cancel() else scheduleSleepTimer()
    }

    fun setAssistantEnabled(enabled: Boolean) {
        if (_state.value.enabled == enabled) return
        preferences.edit().putBoolean("assistant_enabled", enabled).apply()
        if (!enabled) {
            idleSleepJob?.cancel()
            commandJob?.cancel()
            gemini.cancelCurrentRequest()
            geminiLive.cancel()
            keyStore.setWakeServiceEnabled(false)
            _state.value = _state.value.copy(
                enabled = false,
                busy = false,
                listening = false,
                screenAssistPending = false,
                isSleeping = false,
                pendingPlan = null,
                showReminderEditor = false,
                reply = "Jiva is off.",
            )
        } else {
            _state.value = _state.value.copy(
                enabled = true,
                isSleeping = false,
                reply = "Jiva is on. How can I help?",
            )
            scheduleSleepTimer()
        }
    }

    suspend fun wakeFromKeyword() {
        if (!_state.value.enabled) return
        idleSleepJob?.cancel()
        _state.value = _state.value.copy(
            isSleeping = false,
            reply = "Ji, boliye",
        )
        val key = try {
            keyStore.read()
        } catch (error: Exception) {
            _state.value = _state.value.copy(reply = "Could not read the saved Gemini key: ${error.message ?: "storage error"}")
            return
        }
        if (key.isNotBlank()) {
            _state.value = _state.value.copy(busy = true)
            speakReply(key, "Ji, boliye")
        } else {
            scheduleSleepTimer()
        }
    }
    fun selectVoice(voiceName: String) {
        val selected = VoicePresets.find(voiceName).voiceName
        preferences.edit().putString("voice", selected).apply()
        _state.value = _state.value.copy(selectedVoice = selected)
    }
    fun saveApiKey(value: String) { keyStore.save(value) }
    fun clearApiKey() { keyStore.clear() }
    fun savePicovoiceAccessKey(value: String) { keyStore.savePicovoiceAccessKey(value) }
    fun clearWakeWordConfig() {
        keyStore.clearPicovoiceAccessKey()
        keyStore.clearWakeWordModels()
    }
    fun saveWakeWordModel(name: String, path: String) { keyStore.saveWakeWordModel(name, path) }
    fun wakeWordModels(): List<String> = keyStore.readWakeWordModels()
    fun wakeWordConfigured(): Boolean =
        keyStore.readPicovoiceAccessKey().isNotBlank() && keyStore.readWakeWordModels().size == 2
    fun wakeWordServiceEnabled(): Boolean = keyStore.isWakeServiceEnabled()
    fun setWakeWordServiceEnabled(enabled: Boolean) { keyStore.setWakeServiceEnabled(enabled) }
    fun stopSpeech() { geminiLive.stopPlayback() }
    fun hasApiKey(): Boolean = runCatching { keyStore.read().isNotBlank() }.getOrDefault(false)
    suspend fun verifyAndSaveApiKey(
        value: String,
        onRetry: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    ): Result<Unit> = gemini.verifyApiKey(value, onRetry).mapCatching { keyStore.save(value) }

    fun submit(value: String = _state.value.input) {
        val text = value.trim()
        if (text.isBlank() || _state.value.busy || !_state.value.enabled) return
        idleSleepJob?.cancel()
        _state.value = _state.value.copy(input = "", transcript = text, busy = true, pendingPlan = null)
        val local = parser.parse(text)
        if (local != null) {
            _state.value = _state.value.copy(busy = false, reply = local.reply, pendingPlan = local)
            speakReplyIfConfigured(local.reply)
            return
        }
        val key = runCatching { keyStore.read() }.getOrDefault("")
        if (key.isBlank()) {
            _state.value = _state.value.copy(busy = false, reply = "I didn't recognize that command. Connect your own Gemini API key for multilingual natural-language understanding.")
            scheduleSleepTimer()
            return
        }
        commandJob = viewModelScope.launch {
            gemini.interpret(key, text) { attempt, _ ->
                _state.value = _state.value.copy(
                    reply = "Gemini connection interrupted. Retrying (${attempt + 1}/${GeminiRetryPolicy.MAX_ATTEMPTS})…",
                )
            }.onSuccess { json ->
                if (!_state.value.enabled) return@launch
                val plan = parseAndValidate(json)
                _state.value = _state.value.copy(
                    busy = true,
                    reply = plan.reply,
                    pendingPlan = if (plan.action == AssistantAction.CHAT) null else plan,
                )
                speakReply(key, plan.reply)
            }.onFailure { error ->
                if (_state.value.enabled) {
                    _state.value = _state.value.copy(busy = false, reply = error.message ?: "Gemini request failed. Check your connection and API key.")
                    scheduleSleepTimer()
                }
            }
        }
    }

    private fun speakReplyIfConfigured(reply: String) {
        val key = try {
            keyStore.read()
        } catch (error: Exception) {
            _state.value = _state.value.copy(reply = "${_state.value.reply}\nVoice unavailable: ${error.message ?: "Could not read the saved API key."}")
            return
        }
        if (key.isNotBlank()) {
            _state.value = _state.value.copy(busy = true)
            commandJob = viewModelScope.launch { speakReply(key, reply) }
        } else scheduleSleepTimer()
    }

    private suspend fun speakReply(apiKey: String, reply: String) {
        try {
            geminiLive.respondAndSpeak(apiKey, reply, _state.value.selectedVoice) { attempt, _ ->
                if (_state.value.enabled) {
                    _state.value = _state.value.copy(
                        reply = "Gemini voice connection interrupted. Retrying (${attempt + 1}/${GeminiRetryPolicy.MAX_ATTEMPTS})…",
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (_state.value.enabled) {
                _state.value = _state.value.copy(
                    reply = "${_state.value.reply}\nVoice response failed: ${error.message ?: "Gemini Live is unavailable."}",
                )
            }
        } finally {
            if (_state.value.enabled) {
                _state.value = _state.value.copy(busy = false)
                scheduleSleepTimer()
            }
        }
    }

    private fun scheduleSleepTimer() {
        idleSleepJob?.cancel()
        val current = _state.value
        if (!current.enabled || current.busy || current.listening || current.screenAssistPending || current.pendingPlan != null || current.showReminderEditor) return
        idleSleepJob = viewModelScope.launch {
            delay(IDLE_SLEEP_MILLIS)
            val latest = _state.value
            if (!latest.busy && !latest.listening && !latest.screenAssistPending && latest.pendingPlan == null && !latest.showReminderEditor) {
                _state.value = latest.copy(isSleeping = true)
            }
        }
    }

    private fun parseAndValidate(json: JSONObject): IntentPlan {
        val requestedAction = runCatching {
            AssistantAction.valueOf(json.optString("action", "CHAT").uppercase())
        }.getOrDefault(AssistantAction.CHAT)
        val target = json.optString("settingsTarget", "general").lowercase().takeIf { it in setOf("general", "wifi", "bluetooth", "display", "sound") } ?: "general"
        val safeMessage = json.optString("message").take(2_000).takeIf { it.isNotBlank() }
        val phoneAction = json.optString("phoneAction").lowercase().takeIf {
            it in PhoneNavigationActions.supported
        }
        return IntentPlan(
            action = if (requestedAction == AssistantAction.PHONE_NAVIGATION && phoneAction == null) {
                AssistantAction.CHAT
            } else {
                requestedAction
            },
            reply = json.optString("reply", "What would you like Jiva to do?").take(2_000),
            appName = json.optString("appName").take(100).takeIf { it.isNotBlank() },
            recipientQuery = json.optString("recipientQuery").take(100).takeIf { it.isNotBlank() },
            message = safeMessage,
            settingsTarget = target,
            phoneAction = phoneAction,
        )
    }

    private companion object {
        const val IDLE_SLEEP_MILLIS = 30_000L
    }
}
