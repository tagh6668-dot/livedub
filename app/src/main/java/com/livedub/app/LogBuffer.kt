package com.livedub.app

/**
 * Simple in-memory ring log shared between DubService and MainActivity.
 * Thread-safe; keeps last MAX_LINES lines with timestamps.
 */
object LogBuffer {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun append(tag: String, message: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        lines.addLast("[$ts] $tag: $message")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
