package com.livedub.app

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Elevates this app to a privileged (priv-app) installation using root, so the
 * signature-level android.permission.CAPTURE_AUDIO_OUTPUT is granted and
 * AudioSource.REMOTE_SUBMIX can capture the device's INTERNAL audio output
 * (works with wired/wireless headphones, zero mic noise).
 *
 * Strategy (no Shizuku): run `su` to:
 *  1. copy our APK into a Magisk module as a priv-app overlay
 *     (system/priv-app/LiveDub/LiveDub.apk) so it survives ROM updates,
 *  2. add a private-app permissions whitelist XML granting CAPTURE_AUDIO_OUTPUT,
 *  3. remount /system via Magisk magic mount on next reboot.
 */
object RootActivator {

    const val TAG = "LiveDub.Root"

    data class Result(val ok: Boolean, val message: String)

    fun hasRoot(): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", "id").start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    fun activate(context: Context): Result {
        if (!hasRoot()) return Result(false, "دسترسی روت در دسترس نیست")

        return try {
            val srcApk = context.applicationInfo.sourceDir
            val work = File(context.cacheDir, "privapp").apply { mkdirs() }

            // 1. Build the Magisk module structure in cache
            val modRoot = File(work, "LiveDubPriv")
            File(modRoot, "META-INF").mkdirs()
            File(modRoot, "system/priv-app/LiveDub").mkdirs()
            File(modRoot, "system/etc/permissions").mkdirs()

            // module.prop
            File(modRoot, "module.prop").writeText(
                """
                id=livedub_priv
                name=LiveDub Privileged Audio
                version=v1
                versionCode=1
                author=LiveDub
                description=Grants LiveDub CAPTURE_AUDIO_OUTPUT by installing it as a priv-app.
                """.trimIndent() + "\n"
            )
            // Magisk skip-mount markers for everything except system/
            File(modRoot, "META-INF/com/google/android/update-binary").let { f ->
                f.parentFile?.mkdirs()
                f.writeText("#MAGISK\n")
            }
            // Copy our own APK into the module
            val destApk = File(modRoot, "system/priv-app/LiveDub/LiveDub.apk")
            File(srcApk).inputStream().use { input ->
                destApk.outputStream().use { output -> input.copyTo(output) }
            }
            // privapp-permissions whitelist
            File(modRoot, "system/etc/permissions/privapp-permissions-com.livedub.app.xml").writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <permissions>
                  <privapp-permissions package="com.livedub.app">
                    <permission name="android.permission.CAPTURE_AUDIO_OUTPUT"/>
                    <permission name="android.permission.MODIFY_AUDIO_ROUTING"/>
                  </privapp-permissions>
                </permissions>
                """.trimIndent() + "\n"
            )

            // 2. Move module dir to /data/adb/modules (Magisk picks it up on reboot)
            val cmd = buildString {
                append("su -c '")
                append("rm -rf /data/adb/modules/livedub_priv && ")
                append("mkdir -p /data/adb/modules && ")
                append("cp -r ${modRoot.absolutePath} /data/adb/modules/livedub_priv && ")
                append("chmod -R 755 /data/adb/modules/livedub_priv && ")
                append("ls /data/adb/modules/livedub_priv/module.prop'")
            }
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            Log.i(TAG, "activate rc=$code out=$out err=$err")
            if (code == 0 && out.contains("module.prop")) {
                Result(true, "ماژول ساخته شد. یکبار ریبوت کن تا صدا داخلی فعال شود.")
            } else {
                Result(false, "خطا در ساخت ماژول: ${err.take(120)}")
            }
        } catch (e: Exception) {
            Result(false, "خطا: ${e.message}")
        }
    }
}
