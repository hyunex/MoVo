package com.example.mpvlibrary.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Walks a registered SAF tree and syncs its videos into the database. */
class LibraryScanner(private val context: Context) {

    private val db = AppDb.get(context)

    companion object {
        const val MAX_SCAN_DEPTH = 24
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "flv", "wmv",
            "mpg", "mpeg", "3gp", "rmvb", "vob", "ogv", "opus-video", "mp3video",
        )

        fun isVideo(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

        fun takePermission(context: Context, treeUri: Uri) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
            }.onFailure {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }

        fun releasePermission(context: Context, treeUri: Uri) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.releasePersistableUriPermission(treeUri, flags)
            }.onFailure {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }

        fun displayName(context: Context, treeUri: Uri): String {
            val doc = DocumentFile.fromTreeUri(context, treeUri)
            doc?.name?.let { return it }
            val seg = treeUri.lastPathSegment ?: return treeUri.toString()
            return seg.substringAfterLast('/')
        }
    }

    /** Re-scan one registered folder; adds new videos, refreshes metadata, drops deleted ones. */
    suspend fun scan(folder: FolderEntity) = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri)) ?: return@withContext
        val found = ArrayList<String>()
        walk(root, "", found, folder.id, 0)
        // Files deleted or moved out of the tree must disappear from the library.
        db.videos().deleteStale(folder.id, found.ifEmpty { listOf("__none__") })
    }

    suspend fun scanAll() = withContext(Dispatchers.IO) {
        AppLog.i("library", "scan started")
        for (f in db.folders().all()) {
            val u = runCatching { Uri.parse(f.treeUri) }.getOrNull()
            if (u != null) {
                // Ensure write permission is held for file deletion
                takePermission(context, u)
            }
            scan(f)
        }
        AppLog.i("library", "scan finished")
    }

    private suspend fun walk(dir: DocumentFile, prefix: String, found: MutableList<String>, folderId: Long, depth: Int = 0) {
        if (depth > MAX_SCAN_DEPTH) {
            AppLog.w("library", "scan depth limit reached at $prefix")
            return
        }
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            val name = child.name ?: continue
            if (name == "." || name == "..") continue
            if (child.isDirectory) {
                if (depth + 1 > MAX_SCAN_DEPTH) {
                    AppLog.w("library", "scan depth limit reached at $prefix/$name")
                    continue
                }
                walk(child, if (prefix.isEmpty()) name else "$prefix/$name", found, folderId, depth + 1)
            } else if (child.isFile && isVideo(name)) {
                val uri = child.uri.toString()
                found.add(uri)
                val size = runCatching { child.length() }.getOrDefault(0L)
                val modified = runCatching { child.lastModified() }.getOrDefault(0L)
                db.videos().insertNew(
                    listOf(VideoEntity(uri = uri, folderId = folderId, name = name, dirPath = prefix, sizeBytes = size, lastModified = modified)),
                )
                db.videos().refreshMeta(uri, name, prefix, size, modified)
            }
        }
    }
}
