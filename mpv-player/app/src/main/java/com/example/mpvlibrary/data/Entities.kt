package com.example.mpvlibrary.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user-registered video folder (SAF tree URI). */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val treeUri: String,
    val displayName: String,
    val addedAt: Long,
)

/** One video file inside a registered folder. Primary key = document URI. */
@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey val uri: String,
    val folderId: Long,
    val name: String,
    /** Relative directory path inside the folder, e.g. "Anime/Season 1". "" = root. */
    val dirPath: String,
    val durationSec: Double = 0.0,
    val positionSec: Double = 0.0,
    val lastPlayedAt: Long = 0,
    /** 0 = auto (threshold), 1 = force watched, -1 = force unwatched. */
    val watchedOverride: Int = 0,
    val sizeBytes: Long = 0,
    val lastModified: Long = 0,
) {
    val fraction: Double
        get() = if (durationSec > 0) (positionSec / durationSec).coerceIn(0.0, 1.0) else 0.0

    fun isWatched(threshold: Double): Boolean =
        watchedOverride == 1 || (watchedOverride == 0 && fraction >= threshold)

    /** In-progress: started but not watched, some progress recorded. */
    fun isInProgress(threshold: Double): Boolean =
        !isWatched(threshold) && positionSec > 0
}
