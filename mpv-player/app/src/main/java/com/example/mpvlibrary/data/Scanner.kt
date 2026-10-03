package com.example.mpvlibrary.data

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.CancellationSignal
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.example.mpvlibrary.mpv.MpvPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException

data class ScanStatus(val running: Boolean, val completed: Boolean, val error: String? = null)

private data class ScannedVideo(val uri: String, val name: String, val dirPath: String, val size: Long, val modified: Long, val hasExternalSubtitles: Boolean = false)
private class IncompleteScan(message: String) : Exception(message)
private class CompletedScanAttempt(val treeUri: String)

/** Document IDs are provider identities, not paths. Completed aliases need no second query. */
internal class ScanDocumentTracker {
    private val activeDirectories = HashSet<String>()
    private val completedDirectories = HashSet<String>()
    private val videos = HashSet<String>()

    fun enterDirectory(id: String): Boolean {
        if (id in activeDirectories) throw IncompleteScan("폴더 순환 참조가 있습니다")
        if (id in completedDirectories) return false
        activeDirectories.add(id)
        return true
    }

    fun completeDirectory(id: String) {
        activeDirectories.remove(id)
        completedDirectories.add(id)
    }

    fun addVideo(id: String): Boolean = videos.add(id)
}

/** Walks a registered SAF tree and syncs its videos into the database. */
class LibraryScanner(private val context: Context) {

    private val db = AppDb.get(context)

    companion object {
        const val MAX_SCAN_DEPTH = 24
        private const val BATCH_SIZE = 400
        private val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        private val scanMutex = Mutex()
        private val registrationMutex = Mutex()
        private val completedAttempts = ConcurrentHashMap<Long, CompletedScanAttempt>()

        private val mutableStatuses = MutableStateFlow<Map<Long, ScanStatus>>(emptyMap())
        val statuses: StateFlow<Map<Long, ScanStatus>> = mutableStatuses.asStateFlow()
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "flv", "wmv",
            "mpg", "mpeg", "3gp", "rmvb", "vob", "ogv", "opus-video", "mp3video",
        )
        fun isVideo(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

        fun hasPersistedPermission(context: Context, treeUri: Uri, write: Boolean = false): Boolean {
            val held = runCatching {
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == treeUri && it.isReadPermission && (!write || it.isWritePermission)
                }
            }.getOrDefault(false)
            if (!held) AppLog.w("library", "persisted permission missing (write=$write)")
            return held
        }
        fun takePermission(context: Context, treeUri: Uri) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
                .onFailure { runCatching { context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        }
        fun releasePermission(context: Context, treeUri: Uri) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.releasePersistableUriPermission(treeUri, flags) }
                .onFailure { runCatching { context.contentResolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        }
        fun displayName(context: Context, treeUri: Uri): String =
            DocumentFile.fromTreeUri(context, treeUri)?.name ?: treeUri.lastPathSegment?.substringAfterLast('/') ?: treeUri.toString()
    }
    suspend fun register(treeUri: Uri): FolderEntity = withContext(Dispatchers.IO) {
        registrationMutex.withLock {
            takePermission(context, treeUri)
            db.withTransaction {
                val folders = db.folders()
                val tree = treeUri.toString()
                folders.byTreeUri(tree) ?: run {
                    folders.insert(
                        FolderEntity(
                            treeUri = tree,
                            displayName = displayName(context, treeUri),
                            addedAt = System.currentTimeMillis(),
                        ),
                    )
                    folders.byTreeUri(tree)
                }
            } ?: error("Could not register selected folder")
        }
    }

    suspend fun unregister(folder: FolderEntity): Boolean = withContext(Dispatchers.IO) {
        registrationMutex.withLock {
            val removedFolder = db.withTransaction {
                val folders = db.folders()
                val current = folders.byId(folder.id)
                if (current == null || current.treeUri != folder.treeUri) {
                    null
                } else {
                    db.videos().deleteForFolder(current.id)
                    folders.delete(current.id)
                    current
                }
            }
            if (removedFolder != null) {
                mutableStatuses.update { it - removedFolder.id }
                completedAttempts.remove(removedFolder.id)
            }
            if (removedFolder != null && db.folders().byTreeUri(folder.treeUri) == null) {
                runCatching { releasePermission(context, Uri.parse(folder.treeUri)) }
            }
            removedFolder != null
        }
    }


    private suspend fun updateStatusIfRegistered(folder: FolderEntity, status: ScanStatus, completedAttempt: Boolean = false) {
        registrationMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (db.folders().byId(folder.id)?.treeUri == folder.treeUri) {
                currentCoroutineContext().ensureActive()
                if (completedAttempt) completedAttempts[folder.id] = CompletedScanAttempt(folder.treeUri)
                mutableStatuses.update { it + (folder.id to status) }
            }
        }
    }

    suspend fun scan(folder: FolderEntity, retryMetadata: Boolean = false) = withContext(Dispatchers.IO) {
        // Automatic requests already waiting for this tree reuse the finished attempt.
        // Explicit retries must still invalidate failures, even after an automatic scan.
        val previousAttempt = completedAttempts[folder.id]
        scanMutex.withLock {
            val latestAttempt = completedAttempts[folder.id]
            if (!retryMetadata && latestAttempt !== previousAttempt && latestAttempt?.treeUri == folder.treeUri) return@withLock
            updateStatusIfRegistered(folder, ScanStatus(true, false))
            try {
                if (retryMetadata) VideoMetadata.retryFailed(context, folder.id)
                val tree = Uri.parse(folder.treeUri)
                if (!hasPersistedPermission(context, tree, write = false)) throw IncompleteScan("폴더 읽기 권한이 없습니다")
                val originalTreeUri = folder.treeUri
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
                val snapshot = ArrayList<ScannedVideo>()
                walk(tree, rootUri, "", 0, snapshot, ScanDocumentTracker())
                db.withTransaction {
                    val stillRegistered = db.folders().byId(folder.id)
                    if (stillRegistered == null || stillRegistered.treeUri != originalTreeUri) {
                        throw IncompleteScan("폴더 등록 또는 권한이 변경되었습니다")
                    }
                    val videos = db.videos()
                    val existing = videos.forFolder(folder.id).associateBy { it.uri }
                    val foundUris = HashSet<String>(snapshot.size)
                    val additions = ArrayList<VideoEntity>(BATCH_SIZE)
                    for (item in snapshot) {
                        foundUris.add(item.uri)
                        val previous = existing[item.uri]
                        if (previous == null) {
                            additions.add(
                                VideoEntity(item.uri, folder.id, item.name, item.dirPath, sizeBytes = item.size, lastModified = item.modified, hasExternalSubtitles = item.hasExternalSubtitles),
                            )
                            if (additions.size == BATCH_SIZE) {
                                videos.insertNew(additions)
                                additions.clear()
                            }
                        } else if (previous.name != item.name || previous.dirPath != item.dirPath ||
                            previous.sizeBytes != item.size || previous.lastModified != item.modified
                        ) {
                            videos.refreshMetaIfChanged(item.uri, item.name, item.dirPath, item.size, item.modified)
                        }
                        if (previous != null) videos.updateExternalSubtitles(item.uri, item.hasExternalSubtitles)
                    }
                    if (additions.isNotEmpty()) videos.insertNew(additions)
                    val removals = ArrayList<String>(BATCH_SIZE)
                    for (uri in existing.keys) {
                        if (uri !in foundUris) {
                            removals.add(uri)
                            if (removals.size == BATCH_SIZE) {
                                videos.deleteByUris(removals)
                                removals.clear()
                            }
                        }
                    }
                    if (removals.isNotEmpty()) videos.deleteByUris(removals)
                }
                updateStatusIfRegistered(folder, ScanStatus(false, true), completedAttempt = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                AppLog.w("library", "scan incomplete for folder ${folder.id}: ${failure.message}")
                updateStatusIfRegistered(folder, ScanStatus(false, false, failure.message ?: "스캔 실패"), completedAttempt = true)
            } finally {
                if (!currentCoroutineContext().isActive) mutableStatuses.update { it - folder.id }
            }
        }
    }

    suspend fun scanAll(retryMetadata: Boolean = false) = withContext(Dispatchers.IO) {
        for (folder in db.folders().all()) scan(folder, retryMetadata)
    }

    private suspend fun walk(tree: Uri, directory: Uri, prefix: String, depth: Int, found: MutableList<ScannedVideo>, identities: ScanDocumentTracker) {
        val directoryId = DocumentsContract.getDocumentId(directory)
        if (!identities.enterDirectory(directoryId)) return
        if (depth > MAX_SCAN_DEPTH) throw IncompleteScan("폴더 깊이 제한 초과: $prefix")
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directoryId)
        val cursor = queryChildren(childrenUri)
            ?: throw IncompleteScan("폴더 목록을 읽을 수 없습니다: $prefix")
        val siblingNames = ArrayList<String>()
        val directoryVideos = ArrayList<ScannedVideo>()
        cursor.use {
            currentCoroutineContext().ensureActive()
            if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                throw IncompleteScan("폴더 목록을 아직 불러오는 중입니다: $prefix")
            }
            val idCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idCol < 0 || nameCol < 0 || mimeCol < 0 || sizeCol < 0 || modifiedCol < 0) throw IncompleteScan("폴더 목록 메타데이터가 올바르지 않습니다")
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!it.moveToNext()) break
                if (it.isNull(idCol) || it.isNull(nameCol) || it.isNull(mimeCol)) throw IncompleteScan("잘못된 문서 행")
                val id = it.getString(idCol)
                val name = it.getString(nameCol)
                val mime = it.getString(mimeCol)
                if (id.isBlank() || name.isBlank()) throw IncompleteScan("잘못된 문서 행")
                if (mime != DocumentsContract.Document.MIME_TYPE_DIR && name.substringAfterLast('.', "").lowercase() in MpvPath.SUB_EXTS) {
                    siblingNames.add(name)
                }
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val child = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    walk(tree, child, if (prefix.isEmpty()) name else "$prefix/$name", depth + 1, found, identities)
                } else if (isVideo(name) && identities.addVideo(id)) {
                    val child = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    directoryVideos.add(ScannedVideo(child.toString(), name, prefix, if (it.isNull(sizeCol)) 0 else it.getLong(sizeCol), if (it.isNull(modifiedCol)) 0 else it.getLong(modifiedCol)))
                }
            }
            if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                throw IncompleteScan("폴더 목록을 아직 불러오는 중입니다: $prefix")
            }
        }
        for (video in directoryVideos) {
            currentCoroutineContext().ensureActive()
            found.add(video.copy(hasExternalSubtitles = siblingNames.isNotEmpty() && MpvPath.matchSubtitles(video.name, siblingNames).isNotEmpty()))
        }
        identities.completeDirectory(directoryId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun queryChildren(childrenUri: Uri): Cursor? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                val cursor = context.contentResolver.query(childrenUri, projection, null, null, null, signal)
                // Cancellation can win after the provider returns but before the
                // continuation hands the cursor to walk's use block.
                continuation.resume(cursor) { cursor?.close() }
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        }
}
