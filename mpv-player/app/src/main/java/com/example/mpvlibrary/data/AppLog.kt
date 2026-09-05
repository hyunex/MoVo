package com.example.mpvlibrary.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Persistent debug log: in-memory ring buffer + append-through file.
 * Survives playback failures so the debug sheet / share always has evidence,
 * even if the process later dies (uncaught-exception hook flushes first).
 */
object AppLog {
    private const val MAX_LINES = 600
    private val buf = ArrayDeque<String>(MAX_LINES + 1)
    private var dir: File? = null
    private var hookInstalled = false
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    @Synchronized
    fun install(ctx: Context) {
        if (dir == null) {
            dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        }
        if (!hookInstalled) {
            hookInstalled = true
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    e("crash", "UNCAUGHT on ${t.name}: $e\n${e.stackTrace.take(30).joinToString("\n")}")
                } catch (_: Exception) { }
                prev?.uncaughtException(t, e)
            }
        }
    }

    private fun toLogcat(level: String, tag: String, msg: String) {
        val m = if (msg.length > 3500) msg.take(3500) + "…" else msg
        when (level) {
            "E", "M" -> android.util.Log.e(tag, m)
            "W" -> android.util.Log.w(tag, m)
            else -> android.util.Log.i(tag, m)
        }
    }
    @Synchronized
    fun line(level: String, tag: String, msg: String) {
        toLogcat(level, tag, msg)
        val row = "${dateFmt.format(Date())} $level/$tag: $msg"
        if (buf.size >= MAX_LINES) buf.removeFirst()
        buf.addLast(row)
        runCatching {
            val d = dir ?: return
            // One file per day keeps share/export trivial.
            val f = File(d, "mpv-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.log")
            f.appendText(row + "\n")
        }
    }

    fun i(tag: String, msg: String) = line("I", tag, msg)
    fun w(tag: String, msg: String) = line("W", tag, msg)
    fun e(tag: String, msg: String) = line("E", tag, msg)

    fun tail(n: Int = 200): String = synchronized(this) { buf.toList().takeLast(n) }.joinToString("\n")

    @Synchronized
    fun clear() {
        buf.clear()
        runCatching { dir?.listFiles()?.forEach { it.delete() } }
    }

    fun info(): String {
        val files = dir?.listFiles()?.sortedBy { it.name } ?: emptyList()
        val size = files.sumOf { it.length() }
        return "메모리 ${buf.size}줄 · 파일 ${files.size}개 (${size / 1024}KB) · ${Build.MODEL} API ${Build.VERSION.SDK_INT}"
    }

    fun shareIntent(ctx: Context): Intent? {
        val d = dir ?: return null
        val f = d.listFiles()?.maxByOrNull { it.name } ?: return null
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
