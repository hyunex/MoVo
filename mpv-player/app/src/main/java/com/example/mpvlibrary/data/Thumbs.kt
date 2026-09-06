package com.example.mpvlibrary.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Disk-cached video thumbnails extracted with MediaMetadataRetriever. */
object Thumbs {
    private const val TARGET_PX = 256
    private const val MAX_THUMBS_BYTES = 50L * 1024 * 1024
    private const val MAX_THUMB_FILES = 500
    private fun cacheFile(context: Context, uri: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "thumbs").apply { mkdirs() }, "$hash.jpg")
    }

    suspend fun get(context: Context, uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = cacheFile(context, uriString)
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { return@withContext it }
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(uriString))
            // Grab a frame 10% in (fallback 0) so black intro frames are less likely.
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val usec = ((durationMs ?: 1000L) / 10 * 1000L)
            var bmp: Bitmap? = retriever.getFrameAtTime(usec, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bmp == null) bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bmp == null) return@withContext null
            val scaled = scaleDown(bmp)
            FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            prune(context)
            scaled
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
    private fun prune(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, "thumbs")
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            var count = files.size
            for (f in files) {
                if (total <= MAX_THUMBS_BYTES && count <= MAX_THUMB_FILES) break
                total -= f.length()
                count--
                f.delete()
            }
        }
    }

    private fun scaleDown(bmp: Bitmap): Bitmap {
        val ratio = TARGET_PX.toFloat() / maxOf(bmp.width, bmp.height)
        if (ratio >= 1f) return bmp
        return Bitmap.createScaledBitmap(
            bmp, (bmp.width * ratio).toInt().coerceAtLeast(1),
            (bmp.height * ratio).toInt().coerceAtLeast(1), true,
        )
    }
}
