package com.jiva.assistance.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.jiva.assistance.domain.VoicePresets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiLiveClient {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var activeSocket: WebSocket? = null
    @Volatile
    private var requestGeneration = 0

    suspend fun respondAndSpeak(
        apiKey: String,
        text: String,
        voiceName: String,
        ephemeralTokenProvider: (suspend () -> String)? = null,
        onRetry: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    ): String {
        require(ephemeralTokenProvider != null || apiKey.isNotBlank()) {
            "A secure Live token or development API key is required."
        }
        require(text.isNotBlank()) { "There is no text to send to Gemini Live." }
        val voice = VoicePresets.find(voiceName).voiceName
        val generation = requestGeneration
        val reply = GeminiRetryPolicy.execute(
            onRetry = { attempt, error ->
                if (requestGeneration != generation) {
                    throw CancellationException("Gemini request cancelled.")
                }
                onRetry(attempt, error)
            },
        ) {
            if (requestGeneration != generation) throw CancellationException("Gemini request cancelled.")
            withContext(Dispatchers.IO) {
                withTimeout(75_000) {
                    requestReply(apiKey, text, voice, ephemeralTokenProvider)
                }
            }
        }
        withContext(Dispatchers.IO) { play(reply.audio) }
        return reply.transcript.ifBlank { "Jiva replied using the selected voice." }
    }

    fun stopPlayback() {
        audioTrack?.let { track ->
            runCatching { track.pause(); track.flush(); track.stop() }
        }
    }

    fun cancel() {
        requestGeneration++
        activeSocket?.cancel()
        stopPlayback()
    }

    private suspend fun requestReply(
        apiKey: String,
        text: String,
        voiceName: String,
        ephemeralTokenProvider: (suspend () -> String)?,
    ): LiveReply {
        val token = ephemeralTokenProvider?.invoke()
        if (ephemeralTokenProvider != null) {
            check(!token.isNullOrBlank()) { "Secure Gemini Live token was not issued." }
        }
        val result = CompletableDeferred<LiveReply>()
        val audio = ByteArrayOutputStream()
        val transcript = StringBuilder()
        var sentText = false
        val builder = HttpUrl.Builder()
            .scheme("wss")
            .host("generativelanguage.googleapis.com")
        if (token != null) {
            builder.addPathSegments(
                "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained",
            ).addQueryParameter("access_token", token)
        } else {
            builder.addPathSegments(
                "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            ).addQueryParameter("key", apiKey)
        }
        val url = builder.build()
        val request = Request.Builder().url(url).build()
        lateinit var socket: WebSocket
        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject()
                    .put(
                        "setup",
                        JSONObject()
                            .put("model", "models/gemini-3.8-live")
                            .put("responseModalities", JSONArray().put("AUDIO"))
                            .put(
                                "systemInstruction",
                                JSONObject().put(
                                    "parts",
                                    JSONArray().put(
                                        JSONObject().put(
                                            "text",
                                            "You are Jiva, a helpful Hindi and multilingual phone assistant. Speak naturally and briefly. Never claim an action was completed unless the user has confirmed it in the app.",
                                        ),
                                    ),
                                ),
                            )
                            .put(
                                "generationConfig",
                                JSONObject().put(
                                    "speechConfig",
                                    JSONObject().put(
                                        "voiceConfig",
                                        JSONObject().put(
                                            "prebuiltVoiceConfig",
                                            JSONObject().put("voiceName", voiceName),
                                        ),
                                    ),
                                ),
                            ),
                    )
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, textMessage: String) {
                try {
                    val message = JSONObject(textMessage)
                    if (message.has("setupComplete") && !sentText) {
                        sentText = true
                        webSocket.send(
                            JSONObject()
                                .put(
                                    "clientContent",
                                    JSONObject()
                                        .put(
                                            "turns",
                                            JSONArray().put(
                                                JSONObject()
                                                    .put("role", "user")
                                                    .put(
                                                        "parts",
                                                        JSONArray().put(JSONObject().put("text", text)),
                                                    ),
                                            ),
                                        )
                                        .put("turnComplete", true),
                                )
                                .toString(),
                        )
                    }
                    message.optJSONObject("error")?.let { error ->
                        val statusCode = error.optInt("code", 0)
                        val status = error.optString("status")
                        val messageText = error.optString("message", "Gemini Live request failed.")
                        val exception = if (statusCode > 0) {
                            GeminiApiException(statusCode, messageText)
                        } else if (status in setOf("UNAVAILABLE", "RESOURCE_EXHAUSTED", "DEADLINE_EXCEEDED")) {
                            IOException(messageText)
                        } else {
                            IllegalStateException(messageText)
                        }
                        result.completeExceptionally(exception)
                        return
                    }
                    val content = message.optJSONObject("serverContent") ?: return
                    content.optJSONObject("outputTranscription")?.optString("text")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(transcript::append)
                    val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts")
                    for (index in 0 until (parts?.length() ?: 0)) {
                        val inlineData = parts?.optJSONObject(index)?.optJSONObject("inlineData") ?: continue
                        val encodedAudio = inlineData.optString("data")
                        if (encodedAudio.isNotBlank()) audio.write(android.util.Base64.decode(encodedAudio, android.util.Base64.DEFAULT))
                    }
                    if (content.optBoolean("interrupted")) audio.reset()
                    if (content.optBoolean("turnComplete")) {
                        val bytes = audio.toByteArray()
                        if (bytes.isEmpty()) {
                            result.completeExceptionally(IOException("Gemini Live returned no speech audio."))
                        } else {
                            result.complete(LiveReply(transcript.toString(), bytes))
                        }
                    }
                } catch (error: Exception) {
                    result.completeExceptionally(error)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                result.completeExceptionally(IOException("Gemini Live returned an unsupported binary frame."))
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                val code = response?.code
                val message = if (code != null) {
                    "Gemini Live connection failed (HTTP $code). Check key, model access, and network."
                } else {
                    "Gemini Live connection failed. Check your key, model access, and network."
                }
                result.completeExceptionally(
                    if (code != null) GeminiApiException(code, message) else IOException(message, error),
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!result.isCompleted) result.completeExceptionally(IOException("Gemini Live closed before finishing the reply."))
            }
        })
        activeSocket = socket
        return try {
            result.await()
        } finally {
            socket.close(1000, "Reply complete")
            if (activeSocket === socket) activeSocket = null
        }
    }

    private suspend fun play(pcm: ByteArray) {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "This device cannot play Gemini Live audio." }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, pcm.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        audioTrack = track
        try {
            val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            check(written == pcm.size) { "Could not buffer the complete Gemini Live reply." }
            track.play()
            val frames = pcm.size / BYTES_PER_FRAME
            while (track.playbackHeadPosition.toLong() < frames && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                delay(20)
            }
        } finally {
            audioTrack = null
            track.release()
        }
    }

    private data class LiveReply(val transcript: String, val audio: ByteArray)

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val BYTES_PER_FRAME = 2
    }
}
