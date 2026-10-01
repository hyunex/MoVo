package com.example.mpvlibrary.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Process-owned work: leaving a screen must not cancel an accepted library mutation. */
object LibraryWork {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class ProgressSnapshot(
        val context: Context,
        val uri: String,
        val position: Double,
        val duration: Double,
        val now: Long,
    )

    private val progress = Channel<ProgressSnapshot>(Channel.UNLIMITED)

    init {
        // Exactly one consumer awaits each write before starting the next. A backwards seek
        // and a final lifecycle snapshot must win over every earlier queued sample.
        scope.launch {
            for (snapshot in progress) {
                try {
                    AppDb.get(snapshot.context).videos().saveProgress(
                        snapshot.uri, snapshot.position, snapshot.duration, snapshot.now,
                    )
                } catch (e: Exception) {
                    AppLog.e("LibraryWork", "saveProgress failed for ${snapshot.uri}: $e")
                }
            }
        }
    }

    /** Call synchronously in capture order; only application context enters the queue. */
    fun saveProgress(context: Context, uri: String, position: Double, duration: Double, now: Long) {
        progress.trySend(ProgressSnapshot(context.applicationContext, uri, position, duration, now))
            .getOrThrow()
    }
}
