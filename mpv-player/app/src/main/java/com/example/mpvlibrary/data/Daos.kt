package com.example.mpvlibrary.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun all(): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity): Long

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun byId(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE treeUri = :treeUri")
    suspend fun byTreeUri(treeUri: String): FolderEntity?
    @Query("UPDATE folders SET treeUri = :treeUri, displayName = :displayName WHERE id = :id")
    suspend fun updateTree(id: Long, treeUri: String, displayName: String)
}

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos WHERE folderId = :folderId")
    fun observeFolder(folderId: Long): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE folderId = :folderId")
    suspend fun forFolder(folderId: Long): List<VideoEntity>

    @Query("SELECT * FROM videos")
    suspend fun all(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE uri = :uri")
    suspend fun byUri(uri: String): VideoEntity?

    /** Mirrors [VideoEntity.isInProgress]; filtering after LIMIT let finished rows evict unfinished ones. */
    @Query("SELECT * FROM videos WHERE lastPlayedAt > 0 AND positionSec > 0 AND watchedOverride != 1 AND (watchedOverride != 0 OR (CASE WHEN durationSec > 0 THEN MIN(MAX(positionSec / durationSec, 0.0), 1.0) ELSE 0.0 END) < :threshold) ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeContinueWatching(threshold: Double, limit: Int): Flow<List<VideoEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(videos: List<VideoEntity>)

    /** Refresh filesystem metadata only — never touches playback progress. */
    @Query("UPDATE videos SET videoWidth = CASE WHEN sizeBytes != :size OR lastModified != :modified THEN NULL ELSE videoWidth END, videoHeight = CASE WHEN sizeBytes != :size OR lastModified != :modified THEN NULL ELSE videoHeight END, hasEmbeddedSubtitles = CASE WHEN sizeBytes != :size OR lastModified != :modified THEN NULL ELSE hasEmbeddedSubtitles END, metadataChecked = CASE WHEN sizeBytes != :size OR lastModified != :modified THEN 0 ELSE metadataChecked END, name = :name, dirPath = :dirPath, sizeBytes = :size, lastModified = :modified WHERE uri = :uri AND (name != :name OR dirPath != :dirPath OR sizeBytes != :size OR lastModified != :modified)")
    suspend fun refreshMetaIfChanged(uri: String, name: String, dirPath: String, size: Long, modified: Long)

    @Query("UPDATE videos SET hasExternalSubtitles = :present WHERE uri = :uri AND (hasExternalSubtitles IS NULL OR hasExternalSubtitles != :present)")
    suspend fun updateExternalSubtitles(uri: String, present: Boolean)

    /** A replaced file or another completed probe cannot receive this stale result. */
    @Query("UPDATE videos SET videoWidth = :width, videoHeight = :height, hasEmbeddedSubtitles = :subtitles, durationSec = CASE WHEN :duration > 0 THEN :duration ELSE durationSec END, metadataChecked = 1 WHERE uri = :uri AND sizeBytes = :size AND lastModified = :modified AND metadataChecked = 0")
    suspend fun saveMetadata(uri: String, size: Long, modified: Long, width: Int?, height: Int?, subtitles: Boolean?, duration: Double?)

    /** Explicit user retries reopen failed probes, never already inspected media. */
    @Query("UPDATE videos SET metadataChecked = 0 WHERE folderId = :folderId AND metadataChecked = 1 AND hasEmbeddedSubtitles IS NULL")
    suspend fun retryFailedMetadata(folderId: Long)

    @Query("DELETE FROM videos WHERE folderId = :folderId")
    suspend fun deleteForFolder(folderId: Long)

    @Query("UPDATE videos SET positionSec = :position, durationSec = CASE WHEN :duration > 0 THEN :duration ELSE durationSec END, lastPlayedAt = :now WHERE uri = :uri")
    suspend fun saveProgress(uri: String, position: Double, duration: Double, now: Long)

    /** EOF is completion even when the demuxer never supplied a duration. */
    @Query("UPDATE videos SET positionSec = :position, durationSec = CASE WHEN :duration > 0 THEN :duration ELSE durationSec END, lastPlayedAt = :now, watchedOverride = CASE WHEN watchedOverride = -1 THEN -1 ELSE 1 END WHERE uri = :uri")
    suspend fun saveCompletedProgress(uri: String, position: Double, duration: Double, now: Long)

    @Query("UPDATE videos SET watchedOverride = 1 WHERE uri IN (:uris)")
    suspend fun markWatched(uris: List<String>)

    @Query("DELETE FROM videos WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    /** Watched rows restart at 0:00 (a kept end position would resume at EOF); in-progress rows keep their resume point. */
    @Query("UPDATE videos SET positionSec = CASE WHEN watchedOverride = 1 OR (CASE WHEN durationSec > 0 THEN MIN(MAX(positionSec / durationSec, 0.0), 1.0) ELSE 0.0 END) >= :threshold THEN 0.0 ELSE positionSec END, watchedOverride = 0 WHERE uri IN (:uris)")
    suspend fun markUnwatched(uris: List<String>, threshold: Double)

    @Query("UPDATE videos SET positionSec = 0.0, lastPlayedAt = 0, watchedOverride = 0 WHERE uri IN (:uris)")
    suspend fun resetProgressBatch(uris: List<String>)
}
