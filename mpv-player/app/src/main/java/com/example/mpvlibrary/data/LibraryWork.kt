package com.example.mpvlibrary.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** Durable private playlist identity keeps large libraries out of Binder transactions. */
class PlayerPlaylistStore(private val directory: File) {
    companion object {
        private val identityPattern = Regex("playlist-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin")
    }

    fun save(uris: List<String>): String {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create playlist storage" }
        val identity = "playlist-${UUID.randomUUID()}"
        val file = File(directory, "$identity.pending")
        check(file.createNewFile()) { "Cannot create playlist snapshot" }
        try {
            DataOutputStream(file.outputStream().buffered()).use { output ->
                output.writeInt(uris.size)
                for (uri in uris) {
                    val bytes = uri.toByteArray(Charsets.UTF_8)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
            }
            val published = File(directory, "$identity.bin")
            check(file.renameTo(published)) { "Cannot publish playlist snapshot" }
            return published.name
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    fun load(token: String): ArrayList<String> {
        val file = playlistFile(token)
        var remaining = file.length() - 4
        return DataInputStream(file.inputStream().buffered()).use { input ->
            val count = input.readInt()
            require(count >= 0 && count.toLong() <= remaining / 4) { "Invalid playlist count" }
            val uris = ArrayList<String>(count)
            repeat(count) {
                val length = input.readInt()
                remaining -= 4
                require(length >= 0 && length.toLong() <= remaining) { "Invalid playlist entry" }
                val bytes = ByteArray(length)
                input.readFully(bytes)
                remaining -= length
                uris += String(bytes, Charsets.UTF_8)
            }
            require(remaining == 0L) { "Invalid playlist trailing data" }
            uris
        }
    }

    fun remove(token: String) {
        val file = playlistFile(token)
        check(!file.exists() || file.delete()) { "Cannot remove playlist snapshot" }
    }

    private fun playlistFile(token: String): File {
        require(identityPattern.matches(token)) { "Invalid playlist identity" }
        return File(directory, token)
    }
}

data class ProgressUpdate(
    val uri: String,
    val position: Double,
    val duration: Double,
    val now: Long,
    val completed: Boolean = false,
)

/** Coalesce samples, not URI identities or the fact that an item reached EOF. */
class PendingProgress {
    private val pending = LinkedHashMap<String, ProgressUpdate>()

    @Synchronized
    fun offer(update: ProgressUpdate) {
        val previous = pending[update.uri]
        pending[update.uri] = update.copy(
            duration = if (update.duration > 0) update.duration else previous?.duration ?: update.duration,
            completed = update.completed || previous?.completed == true,
        )
    }

    @Synchronized
    fun take(): ProgressUpdate? {
        val entry = pending.entries.firstOrNull() ?: return null
        pending.remove(entry.key)
        return entry.value
    }
}

/** Process-owned work survives Activity destruction, but not process death. */
object LibraryWork {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = PendingProgress()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var application: Context? = null

    init {
        scope.launch {
            for (signal in wake) {
                while (true) {
                    val snapshot = pending.take() ?: break
                    try {
                        val dao = AppDb.get(checkNotNull(application)).videos()
                        if (snapshot.completed) {
                            dao.saveCompletedProgress(snapshot.uri, snapshot.position, snapshot.duration, snapshot.now)
                        } else {
                            dao.saveProgress(snapshot.uri, snapshot.position, snapshot.duration, snapshot.now)
                        }
                    } catch (e: Exception) {
                        AppLog.e("LibraryWork", "saveProgress failed for ${snapshot.uri}: $e")
                    }
                }
            }
        }
    }

    /** Synchronous capture order; at most one pending sample per distinct URI. */
    fun saveProgress(context: Context, uri: String, position: Double, duration: Double, now: Long, completed: Boolean = false) {
        application = context.applicationContext
        pending.offer(ProgressUpdate(uri, position, duration, now, completed))
        wake.trySend(Unit).getOrThrow()
    }
}
