package com.livedub.app

import android.content.Context
import android.media.AudioPlaybackConfiguration
import android.os.IBinder
import java.lang.reflect.Method

/**
 * Sets the media volume of OTHER apps using the hidden IPlayer.setVolume(float)
 * API (same write-only API behind MIUI "Adjust media sound in multiple apps"),
 * reached via reflection. On this Magisk-rooted device hidden-API restrictions
 * are bypassed because the app runs with root-derived privileges through
 * unsafe-but-permissive SELinux; we still guard every call in try/catch and
 * fall back to adjusting the shared MUSIC stream when reflection fails.
 */
object RootVolumeHelper {

    @Volatile var lastError: String? = null

    fun setAppVolume(context: Context, cfg: AudioPlaybackConfiguration, pid: Int, volume: Float) {
        try {
            val iPlayer = cfg.javaClass.getMethod("getIPlayer").invoke(cfg) ?: return
            val binder = iPlayer.javaClass.getMethod("asBinder").invoke(iPlayer) as? IBinder
            if (binder != null) {
                val stub = Class.forName("android.media.IPlayer\$Stub")
                val player = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                val setVolume: Method = player.javaClass.methods.first { it.name == "setVolume" }
                setVolume.invoke(player, volume.coerceIn(0f, 1f))
                return
            }
            // Direct proxy path
            val setVolume: Method = iPlayer.javaClass.methods.first { it.name == "setVolume" }
            setVolume.invoke(iPlayer, volume.coerceIn(0f, 1f))
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    /** Fallback when per-app fails: scale the whole MUSIC stream. */
    fun setMusicStreamVolume(am: android.media.AudioManager, ratio: Float) {
        try {
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val target = (max * ratio).toInt().coerceIn(0, max)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    private fun pidToPackage(context: Context, pid: Int): String? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == pid }?.pkgList?.firstOrNull()
        } catch (e: Exception) { null }
    }
}
