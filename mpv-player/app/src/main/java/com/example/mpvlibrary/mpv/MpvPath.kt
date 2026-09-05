package com.example.mpvlibrary.mpv

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.mpvlibrary.data.AppLog
import java.io.File

/**
 * Resolve a playable path for libmpv from a SAF content URI.
 *
 * Native mpv cannot open Android `content://` URIs, so the primary path is
 * `fd://N`: the FD is opened in this process via ContentResolver and kept
 * open in [Playable] for the whole playback, which mpv reads (seekable).
 * Callers MUST [Playable.close] on advance/destroy, else FDs leak.
 */
object MpvPath {
    private const val TAG = "mpv"

    class Playable(
        val path: String,
        val note: String,
        private val pfd: ParcelFileDescriptor? = null,
    ) : AutoCloseable {
        override fun close() {
            runCatching { pfd?.close() }
        }
    }

    fun open(context: Context, uri: Uri): Playable {
        if (uri.scheme == null || uri.scheme == "file") {
            val p = uri.path ?: uri.toString()
            AppLog.i(TAG, "open ${short(uri)} via direct file path")
            return Playable(p, "직접 경로")
        }
        // 1) fd:// — works for SAF/MediaStore URIs, seek preserved.
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                AppLog.i(TAG, "open ${short(uri)} via fd://${pfd.fd}")
                return Playable("fd://${pfd.fd}", "SAF fd://${pfd.fd} (유지 중)", pfd)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "openFileDescriptor failed for ${short(uri)}: ${e.message}")
        }
        // 2) Real filesystem path, only if this process can actually read it.
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { probe ->
                val real = File("/proc/self/fd/${probe.fd}").canonicalPath
                if (!real.startsWith("/proc") && File(real).canRead()) {
                    AppLog.i(TAG, "open ${short(uri)} via real path $real")
                    return Playable(real, "실제 경로")
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "real-path probe failed for ${short(uri)}: ${e.message}")
        }
        // 3) Last resort: mpv almost certainly cannot play this.
        AppLog.e(TAG, "no readable source for ${short(uri)} — passing raw uri, playback will likely fail")
        return Playable(uri.toString(), "raw URI (재생 실패 가능)")
    }

    private fun short(u: Uri): String = u.lastPathSegment ?: u.toString()
}
