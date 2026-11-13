package com.jiva.assistance.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Calls Gemini generateContent for language understanding. Model output is data, never executable instructions. */
class GeminiRepository(private val model: String = "gemini-3.8-flash") {
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    private val modelsToVerify = listOf(
        model to "generateContent",
        "gemini-3.8-live" to "bidiGenerateContent",
    )

    fun cancelCurrentRequest() {
        activeConnection?.disconnect()
    }

    suspend fun interpret(
        apiKey: String,
        userText: String,
        onRetry: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            GeminiRetryPolicy.execute(onRetry) {
                require(apiKey.isNotBlank()) { "Add your Gemini API key in Settings." }
                val system = """You are Jiva, an Android assistant. Understand the user's language and respond in that language. Return only JSON: {\"reply\":string,\"action\": one of CHAT,OPEN_APP,CALL,SMS,WHATSAPP_SHARE,REMINDER,OPEN_SETTINGS,CAMERA,PHONE_NAVIGATION,\"appName\":string,\"recipientQuery\":string,\"message\":string,\"settingsTarget\": one of general,wifi,bluetooth,display,sound,\"phoneAction\": one of home,back,recents,notifications,quick_settings}. Use PHONE_NAVIGATION only when the user explicitly asks for one of those actions. Never claim any action was completed. If unclear use CHAT and ask a question. Request only actions; the app will validate and ask before acting."""
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText)))))
                    .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 25_000
                    doOutput = true
                    setRequestProperty("x-goog-api-key", apiKey)
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                activeConnection = connection
                try {
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) {
                        throw GeminiApiException(
                            code,
                            "Gemini request failed (HTTP $code). Check key, model, quota, and network.",
                        )
                    }
                    val text = JSONObject(response).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    JSONObject(text)
                } finally {
                    connection.disconnect()
                    if (activeConnection === connection) activeConnection = null
                }
            }
        }
    }

    suspend fun verifyApiKey(
        apiKey: String,
        onRetry: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            GeminiRetryPolicy.execute(onRetry) {
                require(apiKey.isNotBlank()) { "Enter your Gemini API key." }
                modelsToVerify.forEach { (modelName, requiredMethod) ->
                    val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName")
                        .openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15_000
                        readTimeout = 15_000
                        setRequestProperty("x-goog-api-key", apiKey)
                    }
                    activeConnection = connection
                    try {
                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        if (code !in 200..299) {
                            throw GeminiApiException(
                                code,
                                "Gemini model check failed for $modelName (HTTP $code). Verify the key, API access, and network.",
                            )
                        }
                        val methods = JSONObject(response).optJSONArray("supportedGenerationMethods")
                        check((0 until (methods?.length() ?: 0)).any { methods?.optString(it) == requiredMethod }) {
                            "$modelName does not support $requiredMethod for Jiva."
                        }
                    } finally {
                        connection.disconnect()
                        if (activeConnection === connection) activeConnection = null
                    }
                }
            }

        }
    }
}
