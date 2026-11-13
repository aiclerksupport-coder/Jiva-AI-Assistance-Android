package com.jiva.assistance.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Calls Gemini generateContent for language understanding. Model output is data, never executable instructions. */
class GeminiRepository(private val model: String = "gemini-2.5-flash") {
    suspend fun interpret(apiKey: String, userText: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "Add your Gemini API key in Settings." }
            val system = """You are Jiva, an Android assistant. Understand the user's language and respond in that language. Return only JSON: {\"reply\":string,\"action\": one of CHAT,OPEN_APP,CALL,SMS,WHATSAPP_SHARE,REMINDER,OPEN_SETTINGS,CAMERA,\"appName\":string,\"recipientQuery\":string,\"message\":string,\"settingsTarget\": one of general,wifi,bluetooth,display,sound}. Never claim any action was completed. If unclear use CHAT and ask a question. Request only actions; the app will validate and ask before acting."""
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText)))))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${Uri.encode(apiKey)}"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 25_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                check(code in 200..299) { "Gemini request failed (HTTP $code). Check key, model, quota, and network." }
                val text = JSONObject(response).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                JSONObject(text)
            } finally {
                connection.disconnect()
            }
        }
    }
}
