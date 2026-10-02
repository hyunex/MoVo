package com.example.mpvlibrary.data

import android.content.Context
import android.net.Uri
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Visible rows share one cancellable queue; Room is the only persistent probe cache. */
object VideoMetadata {
    private val probeMutex = Mutex()

    suspend fun ensure(context: Context, video: VideoEntity) = withContext(Dispatchers.IO) {
        if (video.metadataChecked) return@withContext
        probeMutex.withLock {
            currentCoroutineContext().ensureActive()
            val dao = AppDb.get(context).videos()
            val current = dao.byUri(video.uri) ?: return@withLock
            if (current.metadataChecked || current.sizeBytes != video.sizeBytes ||
                current.lastModified != video.lastModified
            ) return@withLock
            currentCoroutineContext().ensureActive()
            val result = try {
                context.contentResolver.openFileDescriptor(Uri.parse(current.uri), "r")?.use {
                    // Native borrows this descriptor only until the synchronous call returns.
                    MPVLib.probeMedia(it.fd)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }
            val metadata = result?.takeIf { it.size == 4 && it[3] in 0L..1L }
            val width = metadata?.get(1)?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
            val height = metadata?.get(2)?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
            val duration = metadata?.get(0)?.takeIf { it > 0 }?.let { it / 1_000_000.0 }
            // The PFD is closed before this tiny write. Preserve a completed probe even
            // if its row went offscreen, without launching detached work.
            withContext(NonCancellable) {
                dao.saveMetadata(current.uri, current.sizeBytes, current.lastModified,
                    width, height, metadata?.let { it[3] == 1L }, duration)
            }
        }
    }
}
