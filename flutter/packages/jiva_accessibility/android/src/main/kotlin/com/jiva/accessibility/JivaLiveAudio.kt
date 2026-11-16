package com.jiva.accessibility

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

/** Explicit foreground user session only. No automatic background microphone start. */
class JivaLiveAudio(private val context: Context, messenger: BinaryMessenger) :
    MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private val methods = MethodChannel(messenger, "com.jiva/live_audio")
    private val events = EventChannel(messenger, "com.jiva/live_audio_pcm")
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var sink: EventChannel.EventSink? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var player: AudioTrack? = null
    private val output = Executors.newSingleThreadExecutor()

    init { methods.setMethodCallHandler(this); events.setStreamHandler(this) }
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { sink = events }
    override fun onCancel(arguments: Any?) { sink = null; stop() }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "start" -> { start(); result.success(null) }
                "stop" -> { stop(); result.success(null) }
                "play" -> {
                    val pcm = call.argument<ByteArray>("pcm") ?: throw IllegalArgumentException("PCM required")
                    require(pcm.isNotEmpty() && pcm.size <= 96000 && pcm.size % 2 == 0)
                    play(pcm); result.success(null)
                }
                "interrupt" -> { output.execute { player?.pause(); player?.flush() }; result.success(null) }
                else -> result.notImplemented()
            }
        } catch (error: Exception) { result.error("JIVA_LIVE_AUDIO", error.message, null) }
    }
    private fun start() {
        if (running.get()) return
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission required"
        }
        check(sink != null) { "Subscribe to PCM stream first" }
        val size = maxOf(6400, AudioRecord.getMinBufferSize(16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        check(size > 0) { "Unsupported microphone format" }
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        check(audio.state == AudioRecord.STATE_INITIALIZED) { audio.release(); "Microphone unavailable" }
        recorder = audio
        try { audio.startRecording() } catch (error: Exception) { recorder = null; audio.release(); throw error }
        running.set(true)
        worker = Thread({
            val block = ByteArray(3200) // 100ms PCM16 at 16kHz
            while (running.get()) {
                val count = audio.read(block, 0, block.size)
                if (count > 0 && count % 2 == 0 && running.get()) {
                    val chunk = block.copyOf(count)
                    main.post { if (running.get()) sink?.success(chunk) }
                } else if (count < 0) break
            }
            main.post { if (running.get()) sink?.error("MIC_STOP", "Microphone stopped", null) }
        }, "JivaLivePCM").also { it.start() }
    }
    private fun play(bytes: ByteArray) {
        val current = player ?: AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(96000).setTransferMode(AudioTrack.MODE_STREAM).build()
            .also { check(it.state == AudioTrack.STATE_INITIALIZED); player = it; it.play() }
        // Single ordered writer; do not run AudioTrack.write on main thread.
        output.execute {
            if (current.playState != AudioTrack.PLAYSTATE_PLAYING) current.play()
            current.write(bytes, 0, bytes.size)
        }
    }
    private fun stop() {
        running.set(false)
        val audio = recorder
        recorder = null
        try { audio?.stop() } catch (_: Exception) { }
        worker?.interrupt(); worker = null
        audio?.release()
        output.execute { player?.pause(); player?.flush(); player?.release(); player = null }
    }
    fun release() { stop(); output.shutdown(); sink = null; methods.setMethodCallHandler(null); events.setStreamHandler(null) }
}
