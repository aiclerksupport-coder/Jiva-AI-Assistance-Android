package com.jiva.accessibility

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal class JivaSpeechEngine(
    private val context: Context,
    private val channel: MethodChannel,
) : MethodChannel.MethodCallHandler {
    private var recognizer: SpeechRecognizer? = null
    private var audioInput: SpeechAudioInput? = null
    private var cancelling = false

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startListening" -> startListening(call, result)
            "stopListening" -> {
                stopListening()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    fun release() {
        cancelling = true
        recognizer?.cancel()
        audioInput?.close()
        audioInput = null
        recognizer?.destroy()
        recognizer = null
    }

    private fun startListening(call: MethodCall, result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            result.success(false)
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            result.error("MIC_PERMISSION", "Microphone permission is required.", null)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            result.error("SPEECH_UNAVAILABLE", "Speech recognition is not available on this phone.", null)
            return
        }
        val patientListening = call.argument<Boolean>("patientListening") ?: true
        try {
            release()
            cancelling = false
            val input = SpeechAudioInput.open(context)
            audioInput = input
            val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = speechRecognizer
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (recognizer !== speechRecognizer) return
                    sendStatus("listening")
                    channel.invokeMethod("audioProcessingStatus", input.status)
                }

                override fun onBeginningOfSpeech() {
                    if (recognizer === speechRecognizer) sendStatus("listening")
                }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (recognizer === speechRecognizer) sendStatus("processing")
                }

                override fun onError(error: Int) {
                    if (recognizer !== speechRecognizer) return
                    val enhancedInputWasUsed = audioInput != null
                    closeInput()
                    if (cancelling) {
                        cancelling = false
                        sendStatus("idle")
                        return
                    }
                    if (enhancedInputWasUsed) {
                        sendStatus("processing")
                        recognizer = null
                        speechRecognizer.destroy()
                        channel.invokeMethod(
                            "speechFallback",
                            "Enhanced microphone audio was rejected; retrying with standard phone speech recognition.",
                        )
                    } else {
                        sendStatus("idle")
                        channel.invokeMethod(
                            "speechError",
                            "Speech recognition failed. Check the phone's speech service and try again.",
                        )
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (recognizer !== speechRecognizer) return
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    closeInput()
                    sendStatus("idle")
                    if (cancelling) {
                        cancelling = false
                    } else {
                        channel.invokeMethod("speechResult", text)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (recognizer !== speechRecognizer) return
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (text.isNotBlank()) channel.invokeMethod("speechPartial", text)
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, input.readEnd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SpeechAudioInput.SAMPLE_RATE)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                if (patientListening) {
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6_000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4_500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_500L)
                }
            }
            input.start()
            speechRecognizer.startListening(request)
            channel.invokeMethod("audioProcessingStatus", input.status)
            result.success(true)
        } catch (error: Exception) {
            release()
            channel.invokeMethod(
                "audioProcessingStatus",
                "Enhanced microphone unavailable (${error.message ?: "device limitation"}); using standard speech recognition.",
            )
            result.success(false)
        }
    }

    private fun stopListening() {
        cancelling = true
        recognizer?.cancel()
        closeInput()
        sendStatus("idle")
    }

    private fun closeInput() {
        audioInput?.close()
        audioInput = null
    }

    private fun sendStatus(status: String) {
        channel.invokeMethod("speechStatus", status)
    }
}

private class SpeechAudioInput private constructor(
    private val recorder: AudioRecord,
    val readEnd: ParcelFileDescriptor,
    private val writeEnd: ParcelFileDescriptor,
    private val noiseSuppressor: NoiseSuppressor?,
    private val automaticGainControl: AutomaticGainControl?,
) : AutoCloseable {
    val status: String
        get() = listOfNotNull(
            "Speech-focused microphone",
            "noise reduction".takeIf { noiseSuppressor?.enabled == true },
            "automatic gain".takeIf { automaticGainControl?.enabled == true },
        ).joinToString(" · ")

    private val recording = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val writerClosed = AtomicBoolean(false)
    private var writerThread: Thread? = null

    fun start() {
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "The speech-focused microphone could not initialize."
        }
        recorder.startRecording()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            "The speech-focused microphone did not start."
        }
        recording.set(true)
        writerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            pumpAudio()
        }, "jiva-processed-mic").apply {
            start()
        }
    }

    fun finishInput() {
        if (recording.getAndSet(false)) runCatching { recorder.stop() }
        if (writerClosed.compareAndSet(false, true)) runCatching { writeEnd.close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        finishInput()
        writerThread?.join(500)
        noiseSuppressor?.release()
        automaticGainControl?.release()
        recorder.release()
        readEnd.close()
    }

    private fun pumpAudio() {
        val buffer = ByteArray(CHUNK_BYTES)
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { output ->
                while (recording.get()) {
                    val count = recorder.read(buffer, 0, buffer.size)
                    if (count < 0) throw IOException("Microphone read failed ($count).")
                    if (count > 0) output.write(buffer, 0, count)
                }
            }
        } catch (_: IOException) {
            // The recognition service closed its pipe or capture was stopped.
        } finally {
            recording.set(false)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHUNK_BYTES = 3_200

        fun open(context: Context): SpeechAudioInput {
            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimumBuffer > 0) { "The microphone input format is unavailable." }
            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimumBuffer, CHUNK_BYTES * 4))
                .build()
            var pipe: Array<ParcelFileDescriptor>? = null
            var noiseSuppressor: NoiseSuppressor? = null
            var automaticGainControl: AutomaticGainControl? = null
            try {
                pipe = ParcelFileDescriptor.createPipe()
                noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
                } else {
                    null
                }
                automaticGainControl = if (AutomaticGainControl.isAvailable()) {
                    AutomaticGainControl.create(recorder.audioSessionId)?.apply { enabled = true }
                } else {
                    null
                }
                return SpeechAudioInput(
                    recorder,
                    pipe[0],
                    pipe[1],
                    noiseSuppressor,
                    automaticGainControl,
                )
            } catch (error: Exception) {
                noiseSuppressor?.release()
                automaticGainControl?.release()
                pipe?.forEach { runCatching { it.close() } }
                recorder.release()
                throw error
            }
        }
    }
}
