package com.example.mpvlibrary

import com.example.mpvlibrary.data.SettingsRepo
import com.example.mpvlibrary.data.VideoAlign
import com.example.mpvlibrary.data.VideoEntity
import com.example.mpvlibrary.mpv.MpvPath
import com.example.mpvlibrary.ui.naturalKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario B/C/D core logic: watched-state thresholds, resume eligibility,
 * MPV option parsing, customizable speed presets, and natural video alignment.
 */
class StateLogicTest {

    private fun video(pos: Double, dur: Double, override: Int = 0) = VideoEntity(
        uri = "content://test/$pos$dur", folderId = 1, name = "Ep", dirPath = "",
        durationSec = dur, positionSec = pos, watchedOverride = override,
    )

    @Test fun fractionAndThresholdBoundaries() {
        // Scenario C: 89.9% is still in progress, 90% counts as watched.
        assertFalse(video(899.0, 1000.0).isWatched(0.9))
        assertTrue(video(900.0, 1000.0).isWatched(0.9))
        assertTrue(video(899.0, 1000.0).isInProgress(0.9))
        // Never played: not watched, not in progress -> NEW.
        val fresh = video(0.0, 1000.0)
        assertFalse(fresh.isWatched(0.9))
        assertFalse(fresh.isInProgress(0.9))
    }

    @Test fun manualOverrideBeatsThreshold() {
        assertTrue(video(10.0, 1000.0, override = 1).isWatched(0.9))   // force watched
        assertFalse(video(990.0, 1000.0, override = -1).isWatched(0.9)) // force unwatched
    }

    @Test fun resumePositionTracksProgress() {
        // Scenario B: saved position survives as fraction for the library row.
        val v = video(751.0, 2120.0) // 12:31 / 35:20
        assertEquals(0.3542, v.fraction, 0.001)
        assertTrue(v.isInProgress(0.9))
    }

    @Test fun unknownDurationIsZeroProgress() {
        assertEquals(0.0, video(100.0, 0.0).fraction, 0.0)
    }

    @Test fun mpvOptionsParsing() {
        val opts = SettingsRepo.parseOptions(
            "# comment\n\nhwdec=auto\nprofile=fast\n",
        )
        assertEquals(
            listOf("hwdec" to "auto", "profile" to "fast"),
            opts,
        )
    }
    @Test fun mpvDangerousOptionsBlocked() {
        val opts = SettingsRepo.parseOptions(
            "hwdec=auto\nconfig-dir=/tmp\nload-script=evil.lua\nhttp-header-fields=x\nscreenshot-directory=/tmp\n",
        )
        assertEquals(listOf("hwdec" to "auto"), opts)
        assertTrue(SettingsRepo.isBlockedOption("config-dir"))
        assertTrue(SettingsRepo.isBlockedOption("SCRIPT-OPTS"))
        assertFalse(SettingsRepo.isBlockedOption("hwdec"))
    }

    @Test fun videoAlignEnumMappings() {
        assertEquals("-1", VideoAlign.TOP.value)
        assertEquals("0", VideoAlign.CENTER.value)
        assertEquals("1", VideoAlign.BOTTOM.value)
        assertEquals(VideoAlign.TOP, VideoAlign.fromValue("-1"))
        assertEquals(VideoAlign.CENTER, VideoAlign.fromValue("0"))
        assertEquals(VideoAlign.BOTTOM, VideoAlign.fromValue("1"))
        assertEquals(VideoAlign.TOP, VideoAlign.fromValue("unknown")) // fallback
    }

    @Test fun speedPresetsParsingAndFormatting() {
        val parsed = SettingsRepo.parseSpeedPresets("1.0, 1.25, 0.75, 2.0, invalid, 10.0, 0.05, 1.25")
        // Duplicates removed, out-of-range (<0.1 or >5.0) filtered, sorted
        assertEquals(listOf(0.75, 1.0, 1.25, 2.0), parsed)

        val formatted = SettingsRepo.formatSpeedPresets(listOf(2.0, 1.25, 0.75, 1.0))
        assertEquals("0.75,1,1.25,2", formatted)

        // Empty fallback
        val emptyParsed = SettingsRepo.parseSpeedPresets("")
        assertEquals(SettingsRepo.DEFAULT_SPEED_PRESETS, emptyParsed)
    }

    @Test fun naturalOrderingOfEpisodes() {
        val names = listOf("Episode 10.mkv", "Episode 2.mkv", "Episode 1.mkv")
        assertEquals(
            listOf("Episode 1.mkv", "Episode 2.mkv", "Episode 10.mkv"),
            names.sortedBy { naturalKey(it) },
        )
    }

    @Test fun subtitleBasenameMatching() {
        val sibs = listOf("Ep01.mp4", "Ep01.srt", "Ep01.ko.vtt", "Ep01.nfo", "Ep02.srt", "readme.txt")
        assertEquals(
            listOf("Ep01.ko.vtt", "Ep01.srt"),
            MpvPath.matchSubtitles("Ep01.mp4", sibs),
        )
        // Video file itself and non-subtitle extensions never match.
        assertTrue(MpvPath.matchSubtitles("Ep01.mp4", listOf("Ep01.mp4", "Ep01.jpg")).isEmpty())
        // Case-insensitive extension.
        assertEquals(
            listOf("Ep01.SRT"),
            MpvPath.matchSubtitles("Ep01.mp4", listOf("Ep01.SRT")),
        )
    }
}
