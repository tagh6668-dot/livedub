package com.livedub.app

import android.app.Application

class LiveDubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val text = "Thread=${t.name}\n" + sw.toString()
                LogBuffer.append("CRASH", text.take(500))
                // Persist full trace for retrieval via `su cat`
                val f = java.io.File(getExternalFilesDir(null), "crash.txt")
                f.writeText(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date()) + "\n" + text)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(t, e)
        }
    }
}
