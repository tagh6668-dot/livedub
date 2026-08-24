package com.livedub.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.*
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
    }

    private val running = AtomicBoolean(false)
    private lateinit var audioRecord: AudioRecord
    private var playbackThread: Thread? = null
    private var captureThread: Thread? = null
    private var ws: WebSocketClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Volume ratio: dub volume relative to original (0.0 - 1.0 multiplier applied to dub)
    @Volatile var dubVolumeRatio: Float = 0.8f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apiKey = intent?.getStringExtra("api_key") ?: return START_NOT_STICKY
        dubVolumeRatio = intent.getFloatExtra("dub_volume", 0.8f)
        if (!running.get()) {
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
        val notif: Notification = if (Build.VERSION.SDK_INT >= 33) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("دوبله زنده فعال")
                .setContentText("در حال ترجمه همزمان صدا به فارسی")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("دوبله زنده فعال")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        }
        startForeground(1, notif)
    }

    private fun connectWebSocket(apiKey: String) {
        val url = URI("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey")
        ws = object : WebSocketClient(url) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.i(TAG, "WS connected")
                sendSetupMessage()
            }
            override fun onMessage(message: String?) {
                message?.let { handleServerMessage(JSONObject(it)) }
            }
            override fun onMessage(bytes: ByteBuffer?) {}
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.i(TAG, "WS closed: $reason")
                setStatus("قطع شد: ${reason ?: code}")
                stopSelf()
            }
            override fun onError(ex: Exception?) {
                Log.e(TAG, "WS error", ex)
                setStatus("خطا: ${ex?.message}")
            }
        }
        ws?.connect()
    }

    private fun sendSetupMessage() {
        val setup = JSONObject().put(
            "setup",
            JSONObject()
                .put("model", "models/gemini-3.5-live-translate-preview")
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                        .put("inputAudioTranscription", JSONObject())
                        .put("outputAudioTranscription", JSONObject())
                        .put(
                            "translationConfig",
                            JSONObject()
                                .put("targetLanguageCode", "fa")
                                .put("echoTargetLanguage", true)
                        )
                )
        )
        ws?.send(setup.toString())
        setStatus("متصل — در حال شروع دوبله…")
        startAudioPipeline()
    }

    private fun handleServerMessage(msg: JSONObject) {
        if (msg.has("setupComplete")) {
            setStatus("جلسه آماده است")
            return
        }
        val content = msg.optJSONObject("serverContent") ?: return
        content.optJSONObject("inputTranscription")?.optString("text")?.let {
            if (it.isNotBlank()) setStatus("ورودی: $it")
        }
        content.optJSONObject("outputTranscription")?.optString("text")?.let {
            if (it.isNotBlank()) setStatus("دوبله: $it")
        }
        val modelTurn = content.optJSONObject("modelTurn") ?: return
        val parts = modelTurn.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val b64 = inline.optString("data")
            if (b64.isNotEmpty()) {
                val pcm = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                enqueuePlayback(pcm)
            }
        }
    }

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
                track.write(chunk, chunk.size, AudioTrack.WRITE_BLOCKING)
            }
            track.stop(); track.release()
        }.also { it.start() }

        // ---- Capture: mic / device audio at 16kHz ----
        val inMinBuf = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(inMinBuf, 16000 * 2) // ~100ms+ chunks
        )
        val buf = ByteArray(3200) // 100ms of 16kHz 16-bit mono
        captureThread = Thread {
            audioRecord.startRecording()
            while (running.get()) {
                val n = audioRecord.read(buf, 0, buf.size)
                if (n > 0) {
                    val b64 = android.util.Base64.encodeToString(buf.copyOf(n), android.util.Base64.NO_WRAP)
                    val m = JSONObject().put(
                        "realtimeInput",
                        JSONObject().put(
                            "audio",
                            JSONObject().put("data", b64).put("mimeType", "audio/pcm;rate=16000")
                        )
                    )
                    try { ws?.send(m.toString()) } catch (e: Exception) { Log.e(TAG, "send fail", e) }
                }
            }
            audioRecord.stop(); audioRecord.release()
        }.also { it.start() }
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
        mainHandler.post { MainActivity.updateStatusStatic(s) }
    }

    override fun onDestroy() {
        running.set(false)
        isRunning = false
        instance = null
        try { ws?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
