package com.example.mpvlibrary.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Metadata-keyed disk thumbnails; only cache misses share the extraction lock. */
object Thumbs {
    private const val TARGET_PX = 256
    private const val MAX_THUMBS_BYTES = 50L * 1024 * 1024
    private const val MAX_THUMB_FILES = 500
    private const val PRUNE_EVERY_WRITES = 32
    private val extractionMutex = Mutex()
    private val legacyName = Regex("[0-9a-fA-F]{40}\\.jpg")
    private val cacheName = Regex("v2_[0-9a-f]{64}\\.jpg")
    private var initializedDirectory: File? = null
    private var writesSincePrune = 0

    private fun cacheFile(directory: File, uri: String, sizeBytes: Long, lastModified: Long): File {
        val identity = "v2\n${uri.length}:$uri\n$sizeBytes\n$lastModified"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val hex = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            hex[index * 2] = digits[value ushr 4]
            hex[index * 2 + 1] = digits[value and 0xf]
        }
        return File(directory, "v2_${String(hex)}.jpg")
    }

    suspend fun get(
        context: Context,
        uri: String,
        sizeBytes: Long,
        lastModified: Long,
    ): Bitmap? = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val directory = File(context.cacheDir, "thumbs")
        val file = cacheFile(directory, uri, sizeBytes, lastModified)
        decodeCached(file)?.let { return@withContext it }
        extractionMutex.withLock {
            coroutineContext.ensureActive()
            if (initializedDirectory != directory) {
                directory.mkdirs()
                prune(directory, removeLegacy = true)
                initializedDirectory = directory
                writesSincePrune = 0
            }
            // Another waiter may have published this exact metadata revision.
            decodeCached(file)?.let { return@withLock it }
            // An interrupted or corrupt JPEG must never block future extraction.
            if (file.exists()) file.delete()
            extract(context, uri, file, directory)
        }
    }

    private suspend fun decodeCached(file: File): Bitmap? {
        coroutineContext.ensureActive()
        if (!file.isFile) return null
        val bitmap = try {
            // Our cache is already bounded to 256px; a second bounds pass adds only I/O.
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        try {
            coroutineContext.ensureActive()
            return bitmap
        } catch (cancelled: CancellationException) {
            bitmap?.recycle()
            throw cancelled
        }
    }

    private suspend fun extract(context: Context, uri: String, file: File, directory: File): Bitmap? {
        var retriever: MediaMetadataRetriever? = null
        var ownedBitmap: Bitmap? = null
        try {
            coroutineContext.ensureActive()
            val source = MediaMetadataRetriever()
            retriever = source
            coroutineContext.ensureActive()
            source.setDataSource(context, Uri.parse(uri))
            coroutineContext.ensureActive()
            val durationMs = source.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val usec = ((durationMs ?: 1000L) / 10 * 1000L)
            coroutineContext.ensureActive()
            ownedBitmap = frame(source, usec)
            coroutineContext.ensureActive()
            if (ownedBitmap == null) {
                ownedBitmap = frame(source, 0)
                coroutineContext.ensureActive()
            }
            val original = ownedBitmap ?: return null
            val scaled = scaleDown(original)
            if (scaled !== original) {
                ownedBitmap = scaled
                original.recycle()
            }
            coroutineContext.ensureActive()
            publish(scaled, file, directory)
            coroutineContext.ensureActive()
            // Ownership passes to Compose; never recycle a returned bitmap.
            ownedBitmap = null
            return scaled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            coroutineContext.ensureActive()
            return null
        } finally {
            ownedBitmap?.recycle()
            try {
                retriever?.release()
            } catch (_: Exception) {
                // A release failure must not hide extraction cancellation.
            }
        }
    }

    private fun frame(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, TARGET_PX, TARGET_PX,
            )
        } else {
            // API 26 has no scaled extraction API: temporarily decode the full frame.
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }

    private suspend fun publish(bitmap: Bitmap, file: File, directory: File) {
        var temporary: File? = null
        var published = false
        try {
            coroutineContext.ensureActive()
            temporary = File.createTempFile("v2_", ".tmp", directory)
            FileOutputStream(temporary).use { output ->
                coroutineContext.ensureActive()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)) {
                    throw IOException("Thumbnail compression failed")
                }
            }
            coroutineContext.ensureActive()
            if (!temporary.renameTo(file)) throw IOException("Thumbnail publication failed")
            published = true
            coroutineContext.ensureActive()
            writesSincePrune++
            if (writesSincePrune >= PRUNE_EVERY_WRITES) {
                prune(directory)
                writesSincePrune = 0
            }
        } catch (cancelled: CancellationException) {
            if (published) file.delete()
            throw cancelled
        } catch (_: Exception) {
            // A disk-cache failure should not discard an otherwise usable frame.
            coroutineContext.ensureActive()
        } finally {
            temporary?.delete()
        }
    }

    private fun prune(directory: File, removeLegacy: Boolean = false) {
        // At most 31 newly written 256px JPEGs overshoot the 50MiB / 500-file limits
        // between batches (assuming deletions succeed); never touch unrelated files.
        val entries = directory.listFiles() ?: return
        if (removeLegacy) {
            entries.filter { it.isFile && legacyName.matches(it.name) }.forEach { it.delete() }
        }
        val files = entries.filter { it.isFile && cacheName.matches(it.name) }
            .sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (total <= MAX_THUMBS_BYTES && count <= MAX_THUMB_FILES) break
            val bytes = file.length()
            if (file.delete()) {
                total -= bytes
                count--
            }
        }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val ratio = TARGET_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (ratio >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1), true,
        )
    }
}
