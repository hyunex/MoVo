package com.example.mpvlibrary.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
    private const val MAX_LOG_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_LOG_BYTES = 20L * 1024 * 1024
    private const val MAX_CRASH_FILES = 5
    private const val PRUNE_INTERVAL_MS = 60L * 60 * 1000
    private val buf = ArrayDeque<String>(MAX_LINES + 1)
    private var dir: File? = null
    private var hookInstalled = false
    private var debuggable = false
    private var lastPruneMs = 0L
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    @Synchronized
    fun install(ctx: Context) {
        if (dir == null) {
            dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        }
        debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        pruneLocked()
        if (!hookInstalled) {
            hookInstalled = true
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    e("crash", "UNCAUGHT on ${t.name}: $e\n${e.stackTrace.take(30).joinToString("\n")}")
                    writeCrashReport(t, e)
                } catch (_: Exception) { }
                prev?.uncaughtException(t, e)
            }
        }
    }

    /** Synchronous crash snapshot for the next-launch report dialog. */
    private fun writeCrashReport(t: Thread, e: Throwable) {
        val d = dir ?: return
        val name = "crash-${fileFmt.format(Date())}.log"
        val sb = StringBuilder()
        sb.append("MoVo crash report\n")
        sb.append("time=${dateFmt.format(Date())} thread=${t.name}\n")
        sb.append("device=${Build.MODEL} api=${Build.VERSION.SDK_INT}\n\n")
        sb.append("$e\n")
        sb.append(e.stackTrace.take(40).joinToString("\n"))
        sb.append("\n\n--- recent log ---\n")
        sb.append(buf.toList().takeLast(200).joinToString("\n"))
        sb.append("\n")
        File(d, name).writeText(sb.toString())
        pruneCrashLocked()
    }

    /** Caller must hold the AppLog monitor. */
    private fun pruneLocked() {
        val d = dir ?: return
        val now = System.currentTimeMillis()
        lastPruneMs = now
        runCatching {
            d.listFiles()?.forEach { f ->
                if (f.name.startsWith("crash-")) return@forEach
                if (now - f.lastModified() > MAX_LOG_AGE_MS) f.delete()
            }
            val logs = d.listFiles { f -> !f.name.startsWith("crash-") }
                ?.sortedBy { it.lastModified() } ?: return
            var total = logs.sumOf { it.length() }
            for (f in logs) {
                if (total <= MAX_LOG_BYTES) break
                total -= f.length()
                f.delete()
            }
        }
        pruneCrashLocked()
    }

    /** Caller must hold the AppLog monitor. */
    private fun pruneCrashLocked() {
        runCatching {
            val crashes = dir?.listFiles { f -> f.name.startsWith("crash-") }
                ?.sortedBy { it.name } ?: return
            if (crashes.size > MAX_CRASH_FILES) {
                crashes.take(crashes.size - MAX_CRASH_FILES).forEach { it.delete() }
            }
        }
    }

    @Synchronized
    fun pendingCrashReports(): List<File> =
        dir?.listFiles { f -> f.name.startsWith("crash-") }?.sortedBy { it.name } ?: emptyList()

    fun shareFileIntent(ctx: Context, f: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Synchronized
    fun dismissCrashReports() {
        runCatching { dir?.listFiles { f -> f.name.startsWith("crash-") }?.forEach { it.delete() } }
    }
    private fun toLogcat(level: String, tag: String, msg: String) {
        if (!debuggable) return
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
            if (System.currentTimeMillis() - lastPruneMs > PRUNE_INTERVAL_MS) pruneLocked()
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
