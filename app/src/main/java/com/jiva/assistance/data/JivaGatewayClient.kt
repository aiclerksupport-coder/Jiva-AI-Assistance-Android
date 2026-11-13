package com.jiva.assistance.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Tokens must be supplied by Firebase Auth + App Check at request time. */
data class JivaGatewayTokens(val firebaseIdToken: String, val appCheckToken: String)

class JivaGatewayClient(
    baseUrl: String,
    private val tokenProvider: suspend () -> JivaGatewayTokens,
) {
    private val base = baseUrl.trimEnd('/').also {
        require(it.startsWith("https://") && !it.contains("@") && !it.contains("?") && !it.contains("#")) {
            "Jiva gateway must be HTTPS without credentials or query parameters."
        }
    }

    suspend fun interpret(text: String): JSONObject {
        require(text.isNotBlank() && text.length <= 2000) { "Text must be 1–2000 characters." }
        return post("v1/interpret", JSONObject().put("text", text))
    }

    suspend fun liveToken(): String = post("v1/live-token", JSONObject())
        .getString("token").also { check(it.isNotBlank()) { "No ephemeral Live token returned." } }

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val creds = tokenProvider()
        check(creds.firebaseIdToken.isNotBlank() && creds.appCheckToken.isNotBlank()) {
            "Firebase sign-in and App Check are required."
        }
        val connection = (URL("$base/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${creds.firebaseIdToken}")
            setRequestProperty("X-Firebase-AppCheck", creds.appCheckToken)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Cache-Control", "no-store")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            check(status in 200..299) { "Jiva gateway failed (HTTP $status)." }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
