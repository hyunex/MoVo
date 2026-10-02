package com.example.mpvlibrary

import com.example.mpvlibrary.data.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoMetadataTest {
    @Test fun subtitlePresenceRequiresBothInspectionsToClaimAbsence() {
        val cases = listOf(
            Triple(null, null, null),
            Triple(null, false, null),
            Triple(false, null, null),
            Triple(false, false, false),
            Triple(true, null, true),
            Triple(null, true, true),
            Triple(true, false, true),
            Triple(false, true, true),
            Triple(true, true, true),
        )
        for ((embedded, external, expected) in cases) {
            val video = VideoEntity(
                uri = "content://test/video", folderId = 1, name = "video.mkv", dirPath = "",
                hasEmbeddedSubtitles = embedded, hasExternalSubtitles = external,
            )
            assertEquals("embedded=$embedded, external=$external", expected, video.hasSubtitles)
        }
    }
}
