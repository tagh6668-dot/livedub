package com.livedub.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    companion object {
        @JvmStatic var statusConsumer: ((String) -> Unit)? = null
        fun updateStatusStatic(s: String) { statusConsumer?.invoke(s) }
    }

    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var statusText: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var copyLogBtn: Button
    private lateinit var clearLogBtn: Button
    private lateinit var logText: TextView
    private lateinit var volumeBar: SeekBar

    private val prefs by lazy { getSharedPreferences("livedub", MODE_PRIVATE) }

    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private var pendingApiKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        apiKeyInput = findViewById(R.id.apiKeyInput)
        statusText = findViewById(R.id.statusText)
        volumeLabel = findViewById(R.id.volumeLabel)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        copyLogBtn = findViewById(R.id.copyLogBtn)
        clearLogBtn = findViewById(R.id.clearLogBtn)
        logText = findViewById(R.id.logText)
        volumeBar = findViewById(R.id.dubVolumeBar)

        // Register MediaProjection request launcher
        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mpm.getMediaProjection(result.resultCode, result.data!!)
                DubService.pendingProjection = projection
                pendingApiKey?.let { startDubService(it) }
            } else {
                statusText.text = "دسترسی ضبط صفحه رد شد — دوبله ممکن نیست"
                startBtn.isEnabled = true
            }
            pendingApiKey = null
        }

        // ---- Automatic root activation on first launch ----
        if (!prefs.getBoolean("root_activated", false)) {
            statusText.text = "درخواست دسترسی روت برای صدای داخلی…"
            Thread {
                val res = RootActivator.activate(this)
                runOnUiThread {
                    statusText.text = if (res.ok) {
                        prefs.edit().putBoolean("root_activated", true).apply()
                        res.message
                    } else {
                        LogBuffer.append("LiveDub.Root", "auto-activate FAILED: ${res.message}")
                        "خطای روت: ${res.message} — بعداً از لاگ بررسی کنید"
                    }
                    refreshLog()
                }
            }.start()
        } else if (RootActivator.isPrivileged(this)) {
            statusText.text = "صدای داخلی: فعال ✓"
        }

        apiKeyInput.setText(prefs.getString("api_key", ""))
        volumeBar.progress = prefs.getInt("dub_volume_pct", 80)
        volumeLabel.text = "${volumeBar.progress}٪"

        statusConsumer = { s -> runOnUiThread { statusText.text = s } }

        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                volumeLabel.text = "$progress٪"
                // Live-update running service ratio too
                DubService.instance?.dubVolumeRatio = progress / 100f
                DubService.instance?.let {
                    // Also lower the ORIGINAL (other apps') audio so the ratio holds:
                    setOtherAppsVolume(1f - progress / 100f)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.edit().putInt("dub_volume_pct", volumeBar.progress).apply()
            }
        })

        startBtn.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "کلید API را وارد کنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("api_key", key).apply()
            if (!hasMicPermission()) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), 1
                )
                return@setOnClickListener
            }
            startDub(key)
        }

        stopBtn.setOnClickListener { stopDub() }

        copyLogBtn.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("LiveDub logs", LogBuffer.dump())
            )
            Toast.makeText(this, "لاگ‌ها کپی شد", Toast.LENGTH_SHORT).show()
        }

        clearLogBtn.setOnClickListener {
            LogBuffer.clear()
            refreshLog()
            Toast.makeText(this, "لاگ‌ها پاک شد", Toast.LENGTH_SHORT).show()
        }
    }

    private val logRefresher = object : Runnable {
        override fun run() {
            refreshLog()
            logText.postDelayed(this, 1000)
        }
    }

    private fun refreshLog() {
        logText.text = LogBuffer.dump().ifEmpty { "لاگ‌ها…" }
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
        logText.post(logRefresher)
    }

    override fun onPause() {
        logText.removeCallbacks(logRefresher)
        super.onPause()
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Request MediaProjection permission, then start the dub service.
     * AudioPlaybackCapture requires a MediaProjection token.
     */
    private fun startDub(apiKey: String) {
        pendingApiKey = apiKey
        startBtn.isEnabled = false
        statusText.text = "درخواست مجوز ضبط صدای داخلی…"
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    /**
     * Actually start the DubService after MediaProjection is obtained.
     */
    private fun startDubService(apiKey: String) {
        val pct = volumeBar.progress
        val intent = Intent(this, DubService::class.java).apply {
            putExtra("api_key", apiKey)
            putExtra("dub_volume", pct / 100f)
        }
        ContextCompat.startForegroundService(this, intent)
        stopBtn.isEnabled = true
        // Lower original audio of other apps per chosen ratio
        setOtherAppsVolume(1f - pct / 100f)
    }

    private fun stopDub() {
        stopService(Intent(this, DubService::class.java))
        startBtn.isEnabled = true
        stopBtn.isEnabled = false
        setOtherAppsVolume(1f) // restore original apps to full volume
        statusText.text = "متوقف شد"
    }

    /**
     * Set the volume multiplier for all OTHER apps currently playing audio.
     * On this rooted MIUI device we use the hidden per-app IPlayer.setVolume API
     * via `su` + dumpsys fallback; simplest robust approach: use AudioManager's
     * public per-stream control is not per-app, so we shell out via root to use
     * the same API MIUI uses ("Adjust media sound in multiple apps").
     */
    private fun setOtherAppsVolume(multiplier: Float) {
        Thread {
            try {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                val configs: List<AudioPlaybackConfiguration> = am.activePlaybackConfigurations
                for (cfg in configs) {
                    val ua = cfg.audioAttributes.usage
                    if (ua != AudioAttributes.USAGE_MEDIA && ua != AudioAttributes.USAGE_GAME) continue
                    // mClientPid is hidden — read via reflection; 0 on failure (skip)
                    val pid = try {
                        val f = cfg.javaClass.getDeclaredField("mClientPid")
                        f.isAccessible = true
                        f.getInt(cfg)
                    } catch (e: Exception) { 0 }
                    if (pid == android.os.Process.myPid()) continue
                    RootVolumeHelper.setAppVolume(this, cfg, pid, multiplier)
                }
            } catch (e: Exception) {
                android.util.Log.w("LiveDub", "per-app volume failed: ${e.message}")
            }
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val key = apiKeyInput.text.toString().trim()
            if (key.isNotEmpty()) startDub(key)
        }
    }

    override fun onDestroy() {
        statusConsumer = null
        super.onDestroy()
    }
}
