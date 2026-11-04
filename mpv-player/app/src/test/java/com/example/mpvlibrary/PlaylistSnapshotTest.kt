package com.example.mpvlibrary

import com.example.mpvlibrary.data.PlayerPlaylistStore
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PlaylistSnapshotTest {
    @Test fun largeOrderedPlaylistSurvivesFreshStoreAndCallerChanges() {
        val directory = Files.createTempDirectory("movo-playlist-test").toFile()
        try {
            val original = ArrayList((0 until 50_001).map { "content://provider/tree/library/document/video-$it" })
            val expected = original.toList()
            val token = PlayerPlaylistStore(directory).save(original)
            original.clear()
            val restored = PlayerPlaylistStore(directory).load(token)
            assertEquals(expected, restored)
            assertEquals(expected[500], restored[500])
            assertEquals(expected.last(), restored.last())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun duplicateIdentitiesUnicodeAndLongUrisRoundTripWithoutTruncation() {
        val directory = Files.createTempDirectory("movo-playlist-test").toFile()
        try {
            val uris = listOf("content://provider/영상/🎬", "content://provider/영상/🎬", "content://provider/" + "a".repeat(70_000))
            val store = PlayerPlaylistStore(directory)
            assertEquals(uris, store.load(store.save(uris)))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun missingSnapshotDoesNotInventEmptyOrDifferentPlaylist() {
        val directory = Files.createTempDirectory("movo-playlist-test").toFile()
        try {
            val store = PlayerPlaylistStore(directory)
            val token = store.save(listOf("content://provider/video"))
            store.remove(token)
            assertThrows(FileNotFoundException::class.java) { PlayerPlaylistStore(directory).load(token) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun incompleteSnapshotCannotBeAcceptedAsCompletePlaylist() {
        val directory = Files.createTempDirectory("movo-playlist-test").toFile()
        try {
            val store = PlayerPlaylistStore(directory)
            val token = store.save(listOf("content://provider/first", "content://provider/last"))
            RandomAccessFile(directory.resolve(token), "rw").use { it.setLength(it.length() - 1) }
            assertThrows(IllegalArgumentException::class.java) { store.load(token) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun privateIdentityCannotEscapePlaylistDirectory() {
        val directory = Files.createTempDirectory("movo-playlist-test").toFile()
        try {
            val store = PlayerPlaylistStore(directory)
            assertThrows(IllegalArgumentException::class.java) { store.load("../outside.bin") }
            assertThrows(IllegalArgumentException::class.java) { store.remove("../outside.bin") }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun snapshotsOrphanedByProcessDeathArePrunedAfterAWeek() {
        val directory = Files.createTempDirectory("movo-playlist-test").toFile()
        try {
            val store = PlayerPlaylistStore(directory)
            val now = System.currentTimeMillis()
            val day = 24L * 60 * 60 * 1000
            val stale = store.save(listOf("content://provider/old"))
            directory.resolve(stale).setLastModified(now - 8 * day)
            val interrupted = directory.resolve("playlist-00000000-0000-0000-0000-000000000000.pending")
            interrupted.writeText("partial")
            interrupted.setLastModified(now - 8 * day)
            val recent = store.save(listOf("content://provider/recent"))
            directory.resolve(recent).setLastModified(now - 6 * day)

            store.pruneOlderThan(now, 7 * day)

            assertThrows(FileNotFoundException::class.java) { store.load(stale) }
            assertFalse(interrupted.exists())
            assertEquals(listOf("content://provider/recent"), store.load(recent))
        } finally {
            directory.deleteRecursively()
        }
    }
}
