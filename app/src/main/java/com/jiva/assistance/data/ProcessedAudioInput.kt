package com.jiva.assistance.data

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal class ProcessedAudioInput private constructor(
    private val recorder: AudioRecord,
    private val readEnd: ParcelFileDescriptor,
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

    val descriptor: ParcelFileDescriptor
        get() = readEnd

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
        if (recording.getAndSet(false)) {
            runCatching { recorder.stop() }
        }
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
            // The recognizer closed its input pipe or capture was stopped.
        } finally {
            recording.set(false)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_COUNT = 1
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_BYTES = 3_200

        fun open(): ProcessedAudioInput {
            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                ENCODING,
            )
            check(minimumBuffer > 0) { "The microphone input format is unavailable." }
            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(ENCODING)
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
                return ProcessedAudioInput(
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
