package com.example.mpvlibrary.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Walks a registered SAF tree and syncs its videos into the database. */
class LibraryScanner(private val context: Context) {

    private val db = AppDb.get(context)

    companion object {
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "flv", "wmv",
            "mpg", "mpeg", "3gp", "rmvb", "vob", "ogv", "opus-video", "mp3video",
        )

        fun isVideo(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

        fun takePermission(context: Context, treeUri: Uri) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
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
        walk(root, "", found, folder.id)
        // Files deleted or moved out of the tree must disappear from the library.
        db.videos().deleteStale(folder.id, found.ifEmpty { listOf("__none__") })
    }

    suspend fun scanAll() = withContext(Dispatchers.IO) {
        for (f in db.folders().all()) scan(f)
    }

    private suspend fun walk(dir: DocumentFile, prefix: String, found: MutableList<String>, folderId: Long) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                walk(child, if (prefix.isEmpty()) name else "$prefix/$name", found, folderId)
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
