package com.example.mpvlibrary

import com.example.mpvlibrary.data.SettingsRepo
import com.example.mpvlibrary.data.VideoAlign
import com.example.mpvlibrary.data.VideoEntity
import com.example.mpvlibrary.data.ContinuePlaylistMode
import com.example.mpvlibrary.mpv.MpvPath
import com.example.mpvlibrary.ui.naturalKey
import com.example.mpvlibrary.ui.buildContinuePlaylist
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

    @Test fun mpvBlockedOptionDashBypassClosed() {
        // "--config-dir"처럼 대시를 붙여도 차단되어야 한다.
        assertTrue(SettingsRepo.isBlockedOption("--config-dir"))
        assertTrue(SettingsRepo.isBlockedOption("---load-script"))
        assertTrue(SettingsRepo.isBlockedOption("-- input-conf"))
        assertTrue(SettingsRepo.isBlockedOption("sub-file"))
        assertTrue(SettingsRepo.isBlockedOption("--ytdl-path"))
        assertTrue(SettingsRepo.isBlockedOption("--stream-dump"))
        assertTrue(SettingsRepo.isBlockedOption("input-commands"))
        assertFalse(SettingsRepo.isBlockedOption("hwdec"))
        val opts = SettingsRepo.parseOptions("--hwdec=auto\n--config-dir=/tmp\nsub-file=x.srt\n")
        assertEquals(listOf("hwdec" to "auto"), opts)
    }

    @Test fun resolveFileRejectsTraversal() {
        assertEquals(null, MpvPath.resolveFile("content://x/document/primary:..%2F..%2Fsecret"))
        assertEquals(null, MpvPath.resolveFile("content://x/document/1234:Movies/a.mp4"))
        assertEquals(null, MpvPath.resolveFile("file:///etc/passwd"))
    }

    @Test fun logPathMasking() {
        val masked = com.example.mpvlibrary.data.AppLog.maskPaths("open /storage/emulated/0/Movies/a.mp4 ok")
        assertFalse(masked.contains("/storage/emulated/0"))
        assertTrue(masked.contains("<path>"))
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

    @Test fun continuePlaylistBehavior() {
        val v1 = VideoEntity(uri = "u1", folderId = 1, name = "Ep 1.mp4", dirPath = "")
        val v2 = VideoEntity(uri = "u2", folderId = 1, name = "Ep 10.mp4", dirPath = "")
        val v3 = VideoEntity(uri = "u3", folderId = 1, name = "SP 1.mp4", dirPath = "Specials")
        val v4 = VideoEntity(uri = "u4", folderId = 2, name = "Ep 1.mp4", dirPath = "")
        val allVideos = listOf(v2, v4, v1, v3) // un-ordered
        val continueWatching = listOf(v3, v1, v4)

        // ORIGINAL_FOLDER: same folderId AND dirPath, ordered naturalKey
        val (uris1, idx1) = buildContinuePlaylist(
            mode = ContinuePlaylistMode.ORIGINAL_FOLDER,
            target = v1,
            continueWatchingList = continueWatching,
            folderVideos = allVideos,
        )
        assertEquals(listOf("u1", "u2"), uris1)
        assertEquals(0, idx1)

        val (uris2, idx2) = buildContinuePlaylist(
            mode = ContinuePlaylistMode.ORIGINAL_FOLDER,
            target = v2,
            continueWatchingList = continueWatching,
            folderVideos = allVideos,
        )
        assertEquals(listOf("u1", "u2"), uris2)
        assertEquals(1, idx2)

        val (uris3, idx3) = buildContinuePlaylist(
            mode = ContinuePlaylistMode.ORIGINAL_FOLDER,
            target = v3,
            continueWatchingList = continueWatching,
            folderVideos = allVideos,
        )
        assertEquals(listOf("u3"), uris3)
        assertEquals(0, idx3)

        // CONTINUE_LIST: preserves continue-watching displayed order and clicked index
        val (urisCont, idxCont) = buildContinuePlaylist(
            mode = ContinuePlaylistMode.CONTINUE_LIST,
            target = v1,
            continueWatchingList = continueWatching,
            folderVideos = allVideos,
        )
        assertEquals(listOf("u3", "u1", "u4"), urisCont)
        assertEquals(1, idxCont)
    }

    @Test fun bulkSpeedPresetsStrictValidation() {
        // Supports comma, whitespace, newline separation
        val validRes = SettingsRepo.validateAndParseSpeedPresets("0.5, 0.75\n1.0\t1.25  1.5, 2.0")
        assertTrue(validRes.isSuccess)
        assertEquals(listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0), validRes.getOrThrow())

        // Merges duplicates, sorts, 2 decimals
        val dedupRes = SettingsRepo.validateAndParseSpeedPresets("2.0, 1.0, 1.5, 1.0, 2.00")
        assertTrue(dedupRes.isSuccess)
        assertEquals(listOf(1.0, 1.5, 2.0), dedupRes.getOrThrow())

        // Boundary values (0.1..5.0)
        val boundRes = SettingsRepo.validateAndParseSpeedPresets("0.1, 5.0")
        assertTrue(boundRes.isSuccess)
        assertEquals(listOf(0.1, 5.0), boundRes.getOrThrow())

        // Strict rejection: empty / blank
        assertTrue(SettingsRepo.validateAndParseSpeedPresets("").isFailure)
        assertTrue(SettingsRepo.validateAndParseSpeedPresets("   \n\t").isFailure)

        // Strict rejection: out of range (does not silently drop)
        assertTrue(SettingsRepo.validateAndParseSpeedPresets("0.05").isFailure)
        assertTrue(SettingsRepo.validateAndParseSpeedPresets("5.1").isFailure)
        assertTrue(SettingsRepo.validateAndParseSpeedPresets("1.0, 0.05, 2.0").isFailure)

        // Strict rejection: >2 decimals (does not silently drop)
        assertTrue(SettingsRepo.validateAndParseSpeedPresets("1.125").isFailure)
        assertTrue(SettingsRepo.validateAndParseSpeedPresets("1.0, 1.234, 2.0").isFailure)

        // Strict rejection: invalid tokens (does not silently drop)
        assertTrue(SettingsRepo.validateAndParseSpeedPresets("1.0, abc, 2.0").isFailure)
        assertTrue(SettingsRepo.validateAndParseSpeedPresets("NaN").isFailure)
        assertTrue(SettingsRepo.validateAndParseSpeedPresets("Infinity").isFailure)
    }

    @Test fun speedStepValidation() {
        // Valid steps: finite 0.01..1.0 with max 2 decimals
        val s1 = SettingsRepo.validateSpeedStep("0.05")
        assertTrue(s1.isSuccess)
        assertEquals(0.05, s1.getOrThrow(), 0.0001)

        val s2 = SettingsRepo.validateSpeedStep("0.01")
        assertTrue(s2.isSuccess)
        assertEquals(0.01, s2.getOrThrow(), 0.0001)

        val s3 = SettingsRepo.validateSpeedStep("1.0")
        assertTrue(s3.isSuccess)
        assertEquals(1.0, s3.getOrThrow(), 0.0001)

        val s4 = SettingsRepo.validateSpeedStep("0.25")
        assertTrue(s4.isSuccess)
        assertEquals(0.25, s4.getOrThrow(), 0.0001)

        // Invalid steps
        assertTrue(SettingsRepo.validateSpeedStep("").isFailure)
        assertTrue(SettingsRepo.validateSpeedStep("0.0").isFailure)
        assertTrue(SettingsRepo.validateSpeedStep("0.005").isFailure)
        assertTrue(SettingsRepo.validateSpeedStep("1.01").isFailure)
        assertTrue(SettingsRepo.validateSpeedStep("-0.05").isFailure)
        assertTrue(SettingsRepo.validateSpeedStep("abc").isFailure)
        assertTrue(SettingsRepo.validateSpeedStep("0.025").isFailure)
    }
}
