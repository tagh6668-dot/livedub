package com.livedub.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class DubService : Service() {

    companion object {
        const val TAG = "LiveDub"
        const val CHANNEL_ID = "livedub_channel"
        var isRunning = false
        var instance: DubService? = null

        /**
         * MediaProjection token set by MainActivity BEFORE starting the service.
         * Consumed once in startAudioPipeline(), then nulled.
         */
        var pendingProjection: MediaProjection? = null
    }

    private val running = AtomicBoolean(false)
    private var playbackThread: Thread? = null
    private var captureThread: Thread? = null
    private var ws: WebSocketClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null

    // Volume ratio: dub volume relative to original (0.0 - 1.0 multiplier applied to dub)
    @Volatile var dubVolumeRatio: Float = 0.8f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apiKey = intent?.getStringExtra("api_key") ?: return START_NOT_STICKY
        dubVolumeRatio = intent.getFloatExtra("dub_volume", 0.8f)
        if (!running.get()) {
            // Consume the MediaProjection token set by MainActivity
            mediaProjection = pendingProjection
            pendingProjection = null
            if (mediaProjection == null) {
                log("ERROR: no MediaProjection token — cannot capture audio")
                setStatus("خطا: دسترسی ضبط صفحه داده نشد")
                stopSelf()
                return START_NOT_STICKY
            }
            startForegroundWithNotification()
            running.set(true)
            isRunning = true
            instance = this
            connectWebSocket(apiKey)
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "LiveDub", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("دوبله زنده فعال")
            .setContentText("در حال ترجمه همزمان صدا به فارسی")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif)
        }
    }

    private fun connectWebSocket(apiKey: String) {
        val url = URI("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey")
        ws = object : WebSocketClient(url) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                log("WS connected")
                sendSetupMessage()
            }
            override fun onMessage(message: String?) {
                message?.let {
                    // Log all server messages; truncate very long ones (audio payloads)
                    if (it.length < 1200) {
                        log("<< $it")
                    } else {
                        log("<< (${it.length} chars) ${it.take(300)}…")
                    }
                    try { handleServerMessage(JSONObject(it)) } catch (e: Exception) { log("parse err: ${e.message}") }
                }
            }
            override fun onMessage(bytes: ByteBuffer?) {}
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                log("WS closed code=$code reason=$reason remote=$remote")
                setStatus("قطع شد: ${reason ?: code}")
                stopSelf()
            }
            override fun onError(ex: Exception?) {
                log("WS error: ${ex?.message}")
                setStatus("خطا: ${ex?.message}")
            }
        }
        ws?.connect()
    }

    private fun sendSetupMessage() {
        // Field map verified against google-genai SDK (_live_converters.py,
        // _LiveConnectConfig_to_mldev):
        //  - input/outputAudioTranscription -> setup level
        //  - translationConfig -> INSIDE generationConfig
        val generationConfig = JSONObject()
            .put("responseModalities", org.json.JSONArray().put("AUDIO"))
            .put(
                "translationConfig",
                JSONObject()
                    .put("target_language_code", "fa")
            )
        val setupInner = JSONObject()
            .put("model", "models/gemini-3.5-live-translate-preview")
            .put("generationConfig", generationConfig)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        val setup = JSONObject().put("setup", setupInner)
        log(">> setup sent (target=fa)")
        log(">> setup JSON: ${setup.toString()}")
        ws?.send(setup.toString())
        setStatus("متصل — منتظر تأیید سرور…")
        // NOTE: Do NOT start audio pipeline here!
        // We MUST wait for "setupComplete" from the server before sending any audio.
    }

    private fun handleServerMessage(msg: JSONObject) {
        if (msg.has("setupComplete")) {
            log("setupComplete received — starting audio pipeline")
            setStatus("جلسه آماده — شروع ضبط صدا…")
            startAudioPipeline()
            return
        }
        val content = msg.optJSONObject("serverContent") ?: return
        content.optJSONObject("inputTranscription")?.optString("text")?.let {
            if (it.isNotBlank()) setStatus("ورودی: $it")
        }
        content.optJSONObject("outputTranscription")?.optString("text")?.let {
            if (it.isNotBlank()) setStatus("دوبله: $it")
        }
        val modelTurn = content.optJSONObject("modelTurn")
        if (modelTurn != null) {
            val parts = modelTurn.optJSONArray("parts") ?: return
            for (i in 0 until parts.length()) {
                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                val b64 = inline.optString("data")
                if (b64.isNotEmpty()) {
                    val pcm = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    audioChunksReceived++
                    if (audioChunksReceived % 50L == 1L) {
                        log("audio out: chunk#$audioChunksReceived (${pcm.size}B pcm, queue=${playbackQueue.size})")
                    }
                    enqueuePlayback(pcm)
                }
            }
        }
        if (content.has("turnComplete") || content.optBoolean("turnComplete")) {
            log("turn complete (total audio chunks: $audioChunksReceived)")
        }
    }

    private var audioChunksReceived = 0L

    @SuppressLint("MissingPermission")
    private fun startAudioPipeline() {
        // ---- Playback: 24kHz PCM output from Gemini ----
        val outMinBuf = AudioTrack.getMinBufferSize(
            24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(24000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(outMinBuf, 24000 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()

        playbackThread = Thread {
            while (running.get()) {
                val chunk = playbackQueue.poll()
                if (chunk == null) {
                    try { Thread.sleep(10) } catch (_: InterruptedException) {}
                    continue
                }
                // Apply dub/original volume ratio by scaling samples
                applyGain(chunk, dubVolumeRatio)
                track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            }
            track.stop(); track.release()
        }.also { it.start() }

        // ---- Capture: AudioPlaybackCapture via MediaProjection ----
        // Unlike REMOTE_SUBMIX, AudioPlaybackCapture captures a COPY of the
        // audio stream from other apps while letting it continue playing
        // through the speakers — the user hears the original audio normally.
        val projection = mediaProjection
        if (projection == null) {
            log("capture ABORT: MediaProjection is null")
            setStatus("خطا: دسترسی ضبط صفحه از دست رفت")
            cleanup()
            stopSelf()
            return
        }

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val inMinBuf = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        val audioRecord = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(16000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(inMinBuf, 16000 * 2))
                .build()
                .also { rec ->
                    if (rec.state != AudioRecord.STATE_INITIALIZED) {
                        throw IllegalStateException("AudioPlaybackCapture init failed")
                    }
                    log("AudioRecord (PlaybackCapture) initialized ok, buffer=${maxOf(inMinBuf, 16000 * 2)} bytes")
                }
        } catch (e: Exception) {
            log("AudioPlaybackCapture init FAILED: ${e.message}")
            setStatus("شروع ضبط ممکن نشد: ${e.message}")
            stopSelf()
            return
        }

        val buf = ByteArray(3200) // 100ms of 16kHz 16-bit mono
        captureThread = Thread {
            try {
                audioRecord.startRecording()
                log("capture thread running (PlaybackCapture), recording=${audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING}")
            } catch (e: Exception) {
                log("startRecording failed: ${e.message}")
                return@Thread
            }
            var chunksSent = 0L
            var bytesSent = 0L
            while (running.get()) {
                val n = audioRecord.read(buf, 0, buf.size)
                if (n > 0) {
                    chunksSent++; bytesSent += n
                    if (chunksSent % 100 == 10L) {
                        // periodic heartbeat: ~every 10s
                        val peak = rms(buf, n)
                        log("capture alive: chunk#$chunksSent (${bytesSent / 1024}KB sent), rms=$peak")
                    }
                    if (n < buf.size / 4) {
                        // Mostly-silence chunk: worth logging once in a while
                        if (chunksSent % 300 == 11L) log("capture mostly silent (n=$n) — check audio routing")
                    }
                    val b64 = android.util.Base64.encodeToString(buf.copyOf(n), android.util.Base64.NO_WRAP)
                    val m = JSONObject().put(
                        "realtimeInput",
                        JSONObject().put(
                            "audio",
                            JSONObject().put("data", b64).put("mimeType", "audio/pcm;rate=16000")
                        )
                    )
                    try { ws?.send(m.toString()) } catch (e: Exception) { log("ws send fail: ${e.message}") }
                } else if (n < 0) {
                    log("AudioRecord.read error n=$n")
                }
            }
            log("capture stopped after $chunksSent chunks / ${bytesSent / 1024}KB")
            audioRecord.stop(); audioRecord.release()
        }.also { it.start() }
    }

    /** Root-mean-square loudness of a PCM16 buffer — for diagnosing silence. */
    private fun rms(buf: ByteArray, len: Int): Int {
        var sum = 0L; var cnt = 0
        var i = 0
        while (i + 1 < len) {
            val sample = ((buf[i + 1].toInt() and 0xFF) shl 8) or (buf[i].toInt() and 0xFF)
            sum += sample.toLong() * sample
            cnt++
            i += 2
        }
        return if (cnt == 0) 0 else kotlin.math.sqrt(sum / cnt.toDouble()).toInt()
    }

    private val playbackQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    private fun enqueuePlayback(pcm: ByteArray) {
        playbackQueue.add(pcm)
    }

    private fun applyGain(samples: ByteArray, gain: Float) {
        if (gain >= 0.999f && gain <= 1.001f) return
        val bb = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples.size step 2) {
            val s = bb.getShort(i).toInt()
            val v = (s * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bb.putShort(i, v.toShort())
        }
    }

    private fun setStatus(s: String) {
        LogBuffer.append("status", s)
        mainHandler.post { MainActivity.updateStatusStatic(s) }
    }

    private fun log(m: String) {
        Log.i(TAG, m)
        LogBuffer.append(TAG, m)
    }

    private fun cleanup() {
        running.set(false)
        try { ws?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        running.set(false)
        isRunning = false
        instance = null
        try { ws?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
