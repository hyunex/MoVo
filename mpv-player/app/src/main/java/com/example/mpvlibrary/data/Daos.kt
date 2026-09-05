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
}

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos WHERE folderId = :folderId")
    fun observeFolder(folderId: Long): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos")
    suspend fun all(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE uri = :uri")
    suspend fun byUri(uri: String): VideoEntity?

    @Query("SELECT * FROM videos WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<VideoEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(videos: List<VideoEntity>)

    /** Refresh filesystem metadata only — never touches playback progress. */
    @Query("UPDATE videos SET name = :name, dirPath = :dirPath, sizeBytes = :size, lastModified = :modified WHERE uri = :uri")
    suspend fun refreshMeta(uri: String, name: String, dirPath: String, size: Long, modified: Long)

    @Query("DELETE FROM videos WHERE folderId = :folderId AND uri NOT IN (:uris)")
    suspend fun deleteStale(folderId: Long, uris: List<String>)

    @Query("DELETE FROM videos WHERE folderId = :folderId")
    suspend fun deleteForFolder(folderId: Long)

    @Query("UPDATE videos SET positionSec = :position, durationSec = :duration, lastPlayedAt = :now WHERE uri = :uri")
    suspend fun saveProgress(uri: String, position: Double, duration: Double, now: Long)

    @Query("UPDATE videos SET watchedOverride = :override WHERE uri = :uri")
    suspend fun setOverride(uri: String, override: Int)

    @Query("DELETE FROM videos WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    @Query("UPDATE videos SET watchedOverride = :override WHERE uri IN (:uris)")
    suspend fun setOverrideBatch(uris: List<String>, override: Int)

    @Query("UPDATE videos SET positionSec = 0.0, lastPlayedAt = 0, watchedOverride = 0 WHERE uri IN (:uris)")
    suspend fun resetProgressBatch(uris: List<String>)
}
