package com.example.mpvlibrary

import com.example.mpvlibrary.data.ScanDocumentTracker
import com.example.mpvlibrary.data.VideoEntity
import org.junit.Assert.*
import org.junit.Test

class LibraryBehaviorTest {
    private fun video() = VideoEntity(
        uri = "content://fixture/video", folderId = 1, name = "video.mkv", dirPath = "",
        durationSec = 100.0, sizeBytes = 4096, lastModified = 10,
    )

    @Test fun unwatchedStateHonorsOverridesAndThreshold() {
        val source = video()
        assertTrue(source.copy(watchedOverride = 1).isWatched(0.9))
        assertFalse(source.copy(positionSec = 8.0, watchedOverride = -1).isWatched(0.9))
        assertFalse(source.copy(positionSec = 95.0, watchedOverride = -1).isWatched(0.9))
        assertFalse(source.copy(positionSec = 89.0).isWatched(0.9))
        assertTrue(source.copy(positionSec = 90.0).isWatched(0.9))
        assertFalse(source.copy(positionSec = 90.0).isWatched(0.95))
    }

    @Test fun metadataRetryRequiresUncheckedSameSourceRevision() {
        val requested = video()
        val failed = requested.copy(metadataChecked = true)
        assertFalse(failed.needsMetadataProbe(requested))
        // An explicit retry clears checked; ordinary row revisits do not.
        assertTrue(failed.copy(metadataChecked = false).needsMetadataProbe(requested))
        assertFalse(requested.copy(sizeBytes = 8192).needsMetadataProbe(requested))
        assertFalse(requested.copy(lastModified = 11).needsMetadataProbe(requested))
        assertFalse(requested.copy(uri = "content://fixture/replaced").needsMetadataProbe(requested))
        // A completed probe blocks an older queued request, even for the same file.
        assertFalse(requested.copy(metadataChecked = true, hasEmbeddedSubtitles = false)
            .needsMetadataProbe(requested))
    }

    @Test fun activeAncestorCycleFailsInsteadOfCompletingPartialInventory() {
        val identities = ScanDocumentTracker()
        assertTrue(identities.enterDirectory("root"))
        assertTrue(identities.enterDirectory("child"))
        assertThrows(Exception::class.java) { identities.enterDirectory("root") }
    }

    @Test fun completedDirectoryAliasesAndVideoAliasesAreExpandedOnce() {
        val identities = ScanDocumentTracker()
        assertTrue(identities.enterDirectory("root"))
        assertTrue(identities.enterDirectory("shared"))
        assertTrue(identities.addVideo("shared-video"))
        identities.completeDirectory("shared")
        assertFalse(identities.enterDirectory("shared"))
        assertFalse(identities.addVideo("shared-video"))
        assertTrue(identities.enterDirectory("different"))
        assertTrue(identities.addVideo("different-video"))
        identities.completeDirectory("different")
        identities.completeDirectory("root")
        assertFalse(identities.enterDirectory("root"))
        // Identities are local to a scan; a refresh must enumerate the provider again.
        assertTrue(ScanDocumentTracker().enterDirectory("root"))
    }
}
