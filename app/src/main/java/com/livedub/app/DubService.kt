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

        // Uplink silence gate tuning (chunks are 100ms @ 16kHz)
        const val GATE_CLOSE_RMS = 120              // below this a chunk counts as silence
        const val GATE_SILENT_CHUNKS_TO_CLOSE = 10  // 10 x 100ms = 1s gap closes the gate

        // Dub playback is UNTOUCHED by the uplink gate: when the original
        // source stops, the queued dub tail keeps playing at the SAME fixed
        // volume. The gate only pauses the UPLINK (stop feeding the model
        // once the source is gone) — it never touches playback gain.
    }

    private val running = AtomicBoolean(false)
    private var playbackThread: Thread? = null
    private var captureThread: Thread? = null
    private var ws: WebSocketClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var audioManager: AudioManager? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

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
            override fun onMessage(bytes: ByteBuffer?) {
                bytes?.let {
                    val text = String(it.array(), it.position(), it.remaining(), Charsets.UTF_8)
                    if (text.length < 1200) {
                        log("<< (bin) $text")
                    } else {
                        log("<< (bin ${text.length} chars) ${text.take(300)}…")
                    }
                    try { handleServerMessage(JSONObject(text)) } catch (e: Exception) { log("parse err (bin): ${e.message}") }
                }
            }
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
        // Barge-in style turn interruption from the server: anything still
        // queued is stale — drop it so old dub doesn't keep playing.
        if (content.optBoolean("interrupted") || content.has("interruption")) {
            val n = playbackQueue.size
            if (n > 0) {
                playbackQueue.clear()
                log("interruption — cleared $n queued dub chunks")
            }
        }
        val modelTurn = content.optJSONObject("modelTurn")
        if (modelTurn != null) {
            // NOTE: the uplink gate NEVER gates playback. When the original
            // source stops, the dub tail (~5s of lag) must keep playing at the
            // same fixed volume — every chunk that arrives is queued and
            // played normally.
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
                    if (isAllZeroPcm(pcm)) {
                        // All-zero model audio = the "primed on silence" bug
                        // (session opened with zero-input now emits digital
                        // silence). Don't play it and don't let it silence the
                        // live audio; real dub audio follows a nonzero chunk.
                        if (audioChunksReceived % 50L == 1L) log("model audio all-zero — ignored (prime recovery)")
                        continue
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

        // ---- Track external players so the original-audio duck survives ----
        // New players start at full volume; re-apply the duck whenever the
        // set of active playback configs changes.
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                applyOriginalDuck()
                // A pause/stop of the external player shows up here first —
                // close the gate immediately instead of waiting for silence.
                refreshExternalPlayerActive("config changed")
            }
        }
        playbackCallback = cb
        try {
            audioManager?.registerAudioPlaybackCallback(cb, mainHandler)
            applyOriginalDuck()
            refreshExternalPlayerActive("initial scan")
            log("playback callback registered — original duck kept in sync")
        } catch (e: Exception) {
            log("playback cb register failed: ${e.message}")
        }

        playbackThread = Thread {
            while (running.get()) {
                val chunk = playbackQueue.poll()
                if (chunk == null) {
                    try { Thread.sleep(10) } catch (_: InterruptedException) {}
                    continue
                }
                // Fixed dub volume: the uplink gate has no say here. Whether
                // the original source is playing or has stopped, every dub
                // chunk plays at exactly dubVolumeRatio — no fade, no duck.
                applyGain(chunk.pcm, dubVolumeRatio)
                track.write(chunk.pcm, 0, chunk.pcm.size, AudioTrack.WRITE_BLOCKING)
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
            .excludeUid(android.os.Process.myUid())
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
                    val peak = rms(buf, n)

                    // ---- Silence gate: NEVER feed zero-audio to the model ----
                    // A session opened with stretches of digital silence gets
                    // stuck emitting zero-PCM dub audio forever (prime bug).
                    // Gate = RMS-quiet AND no active external player (AND-gate):
                    // our own dub loopback or UI sounds must not hold it open.
                    if (gateOpen.get()) {
                        val extActive = externalPlayerActive
                        if (peak < GATE_CLOSE_RMS || !extActive) {
                            consecutiveSilentChunks++
                            val quiet = peak < GATE_CLOSE_RMS
                            if (consecutiveSilentChunks >= GATE_SILENT_CHUNKS_TO_CLOSE) {
                                gateOpen.set(false)
                                val why = if (!extActive) "no active external player" else "silence ${GATE_SILENT_CHUNKS_TO_CLOSE}x100ms"
                                // Uplink only: the dub tail keeps playing at
                                // fixed volume — playback is never gated.
                                log("silence gate CLOSED ($why) — uplink paused (last rms=$peak)")
                            } else if (!quiet && consecutiveSilentChunks % 5L == 0L) {
                                // Loud audio but no external player: loopback or
                                // something is feeding us — keep counting down.
                                log("gate closing countdown=$consecutiveSilentChunks (rms=$peak but no external player)")
                            }
                        } else {
                            consecutiveSilentChunks = 0
                        }
                    }

                    if (chunksSent % 100 == 10L) {
                        // periodic heartbeat: ~every 10s, now includes gate state
                        log("capture alive: chunk#$chunksSent (${bytesSent / 1024}KB sent), rms=$peak, gate=" +
                            (if (gateOpen.get()) "OPEN" else "CLOSED"))
                    }
                    if (n < buf.size / 4) {
                        // Mostly-silence chunk: worth logging once in a while
                        if (chunksSent % 300 == 11L) log("capture mostly silent (n=$n) — check audio routing")
                    }
                    if (!gateOpen.get()) {
                        // Hold uplink; media keeps playing locally. Reopen only
                        // when BOTH real audio is present AND an external
                        // player is actively playing (loopback can be loud).
                        if (peak >= GATE_CLOSE_RMS && externalPlayerActive) {
                            gateOpen.set(true)
                            consecutiveSilentChunks = 0
                            log("silence gate OPEN — real audio + external player active (rms=$peak), uplink resumed")
                        }
                        continue
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
            val u = ((buf[i + 1].toInt() and 0xFF) shl 8) or (buf[i].toInt() and 0xFF)
            // Sign-extend: an unsigned read turns -1 into 65535 and would
            // inflate RMS for tiny negative samples, keeping the gate open
            // on noise instead of closing on true silence.
            val s = if (u >= 32768) u - 65536 else u
            sum += s.toLong() * s
            cnt++
            i += 2
        }
        return if (cnt == 0) 0 else kotlin.math.sqrt(sum / cnt.toDouble()).toInt()
    }

    /** True when the PCM16 buffer is entirely zero (digital silence). */
    private fun isAllZeroPcm(pcm: ByteArray): Boolean {
        for (b in pcm) if (b.toInt() != 0) return false
        return true
    }

    private val playbackQueue = java.util.concurrent.ConcurrentLinkedQueue<DubChunk>()

    /** Time-stamped dub chunk (kept for future diagnostics; playback is FIFO). */
    private class DubChunk(val pcm: ByteArray, val atMs: Long)

    // ---- Uplink silence gate ----
    // The translate model's session can get "primed" by an opening stretch of
    // all-zero audio (dub started before any media was playing): it then keeps
    // emitting zero-PCM audio with no transcription even once real audio
    // arrives (seen in logs: rms=0 chunks fed for 22s → dead output). Fix:
    // never stream silence to the model — the uplink only opens when real
    // sound appears, so the session is always primed with actual audio.
    private val gateOpen = AtomicBoolean(true)
    private var consecutiveSilentChunks = 0L

    // True while some OTHER app is actively playing media. The capture stream
    // can contain our own dub playback (loopback) or system sounds, so RMS
    // alone cannot prove the original source is alive. A pause/stop of the
    // external player flips this to false instantly via AudioPlaybackCallback
    // and closes the gate even if captured audio is non-silent.
    @Volatile private var externalPlayerActive = false

    /** Refresh the external-player-active flag from current playback configs. */
    private fun refreshExternalPlayerActive(reason: String) {
        val am = audioManager ?: return
        var active = false
        var uid = 0
        try {
            val myUid = android.os.Process.myUid()
            for (cfg in am.activePlaybackConfigurations) {
                // Only usage MEDIA/GAME count as the "original source" we dub.
                val ua = cfg.audioAttributes.usage
                if (ua != AudioAttributes.USAGE_MEDIA && ua != AudioAttributes.USAGE_GAME) continue
                // Skip our own playback (dub loopback must not hold the gate open).
                val isMine = try {
                    val f = cfg.javaClass.getDeclaredField("mClientUid")
                    f.isAccessible = true
                    f.getInt(cfg) == myUid
                } catch (e: Exception) { false }
                if (isMine) continue
                active = true
                uid = try {
                    val f = cfg.javaClass.getDeclaredField("mClientUid")
                    f.isAccessible = true
                    f.getInt(cfg)
                } catch (e: Exception) { 0 }
                break
            }
        } catch (e: Exception) {
            log("ext player scan failed: ${e.message}")
        }
        val was = externalPlayerActive
        externalPlayerActive = active
        if (was != active) {
            log("external player $reason: active=$was->$active (uid=$uid)")
            if (!active) {
                // Original source paused/stopped/ended → close the UPLINK gate
                // NOW (stop feeding the model once the source is gone — the
                // model stays silent on Persian input, so its own dub tail in
                // the capture is useless uplink). Playback is NOT touched:
                // the queued dub tail keeps playing at the same fixed volume,
                // per user requirement.
                if (gateOpen.getAndSet(false)) {
                    log("source cut ($reason) — uplink gate CLOSED (dub tail keeps playing at fixed volume)")
                }
            } else {
                consecutiveSilentChunks = 0
                gateOpen.set(true)
                log("silence gate OPEN — external player active again")
            }
        }
    }

    private fun enqueuePlayback(pcm: ByteArray) {
        playbackQueue.add(DubChunk(pcm, android.os.SystemClock.elapsedRealtime()))
    }

    /** Public re-apply hook so the UI slider can retune the live duck. */
    fun reapplyOriginalDuck() {
        mainHandler.post { applyOriginalDuck() }
    }

    /**
     * Re-apply the original-audio duck across all currently active external
     * players. Every new AudioTrack starts at full volume, so without this the
     * duck applied at start would be lost the moment the player app changes
     * tracks or a new player appears.
     */
    private fun applyOriginalDuck() {
        val am = audioManager ?: return
        val duck = (1f - dubVolumeRatio).coerceIn(0f, 1f)
        val myPid = android.os.Process.myPid()
        try {
            for (cfg in am.activePlaybackConfigurations) {
                val ua = cfg.audioAttributes.usage
                if (ua != AudioAttributes.USAGE_MEDIA && ua != AudioAttributes.USAGE_GAME) continue
                // mClientPid is hidden — read via reflection; 0 on failure (skip)
                val pid = try {
                    val f = cfg.javaClass.getDeclaredField("mClientPid")
                    f.isAccessible = true
                    f.getInt(cfg)
                } catch (e: Exception) { 0 }
                if (pid == myPid) continue
                RootVolumeHelper.setAppVolume(this, cfg, pid, duck)
            }
        } catch (e: Exception) {
            log("per-app duck failed: ${e.message}")
        }
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
        try { playbackCallback?.let { cb -> audioManager?.unregisterAudioPlaybackCallback(cb) } } catch (_: Exception) {}
        playbackCallback = null
        try { ws?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        running.set(false)
        isRunning = false
        instance = null
        try { playbackCallback?.let { cb -> audioManager?.unregisterAudioPlaybackCallback(cb) } } catch (_: Exception) {}
        playbackCallback = null
        try { ws?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
