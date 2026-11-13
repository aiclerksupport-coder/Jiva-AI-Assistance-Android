package com.jiva.assistance

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jiva.assistance.data.ApiKeyStore
import com.jiva.assistance.data.GeminiRepository
import com.jiva.assistance.domain.AssistantAction
import com.jiva.assistance.domain.AssistantUiState
import com.jiva.assistance.domain.IntentPlan
import com.jiva.assistance.domain.LocalCommandParser
import com.jiva.assistance.domain.ReminderItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val parser = LocalCommandParser()
    private val gemini = GeminiRepository()
    private val keyStore = ApiKeyStore(application)
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    fun setInput(value: String) { _state.value = _state.value.copy(input = value) }
    fun updatePlan(plan: IntentPlan?) { _state.value = _state.value.copy(pendingPlan = plan) }
    fun dismissPlan() { _state.value = _state.value.copy(pendingPlan = null) }
    fun showReminderEditor() { _state.value = _state.value.copy(showReminderEditor = true) }
    fun closeReminderEditor() { _state.value = _state.value.copy(showReminderEditor = false) }
    fun setReminders(items: List<ReminderItem>) { _state.value = _state.value.copy(reminders = items) }
    fun saveApiKey(value: String) { keyStore.save(value) }
    fun clearApiKey() { keyStore.clear() }
    fun hasApiKey(): Boolean = runCatching { keyStore.read().isNotBlank() }.getOrDefault(false)

    fun submit(value: String = _state.value.input) {
        val text = value.trim()
        if (text.isBlank() || _state.value.busy) return
        _state.value = _state.value.copy(input = "", transcript = text, busy = true, pendingPlan = null)
        val local = parser.parse(text)
        if (local != null) {
            _state.value = _state.value.copy(busy = false, reply = local.reply, pendingPlan = local)
            return
        }
        val key = runCatching { keyStore.read() }.getOrDefault("")
        if (key.isBlank()) {
            _state.value = _state.value.copy(busy = false, reply = "I didn't recognize that command. Connect your own Gemini API key for multilingual natural-language understanding.")
            return
        }
        viewModelScope.launch {
            gemini.interpret(key, text).onSuccess { json ->
                val plan = parseAndValidate(json)
                _state.value = _state.value.copy(busy = false, reply = plan.reply, pendingPlan = if (plan.action == AssistantAction.CHAT) null else plan)
            }.onFailure { error ->
                _state.value = _state.value.copy(busy = false, reply = error.message ?: "Gemini request failed. Check your connection and API key.")
            }
        }
    }

    private fun parseAndValidate(json: JSONObject): IntentPlan {
        val action = runCatching { AssistantAction.valueOf(json.optString("action", "CHAT").uppercase()) }
            .getOrDefault(AssistantAction.CHAT)
        val target = json.optString("settingsTarget", "general").lowercase().takeIf { it in setOf("general", "wifi", "bluetooth", "display", "sound") } ?: "general"
        val safeMessage = json.optString("message").take(2_000).ifBlank { null }
        return IntentPlan(
            action = action,
            reply = json.optString("reply", "What would you like Jiva to do?").take(2_000),
            appName = json.optString("appName").take(100).ifBlank { null },
            recipientQuery = json.optString("recipientQuery").take(100).ifBlank { null },
            message = safeMessage,
            settingsTarget = target,
        )
    }
}
