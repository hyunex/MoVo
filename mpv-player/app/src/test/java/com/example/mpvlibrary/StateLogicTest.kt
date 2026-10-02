package com.example.mpvlibrary

import com.example.mpvlibrary.data.AppLog
import com.example.mpvlibrary.data.SettingsRepo
import com.example.mpvlibrary.data.VideoAlign
import com.example.mpvlibrary.data.VideoEntity
import com.example.mpvlibrary.data.ContinuePlaylistMode
import com.example.mpvlibrary.mpv.MpvPath
import com.example.mpvlibrary.ui.naturalKey
import com.example.mpvlibrary.ui.buildContinuePlaylist
import com.example.mpvlibrary.ui.PlaybackEndAction
import com.example.mpvlibrary.ui.playbackEndAction
import com.example.mpvlibrary.ui.playbackRemainingSeconds
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

    @Test fun remainingTimeScalesWithSpeedAndSelectedPosition() {
        assertEquals(90.0, playbackRemainingSeconds(120.0, 30.0, 1.0), 0.0)
        assertEquals(45.0, playbackRemainingSeconds(120.0, 30.0, 2.0), 0.0)
        assertEquals(180.0, playbackRemainingSeconds(120.0, 30.0, 0.5), 0.0)
        assertEquals(30.0, playbackRemainingSeconds(120.0, 60.0, 2.0), 0.0)
        assertEquals(72.0, playbackRemainingSeconds(120.0, 30.0, 1.25), 0.0)
    }

    @Test fun remainingTimeClampsAtAndBeyondEndForEverySpeed() {
        for (speed in listOf(0.5, 1.0, 2.0)) {
            assertEquals(0.0, playbackRemainingSeconds(120.0, 120.0, speed), 0.0)
            assertEquals(0.0, playbackRemainingSeconds(120.0, 121.0, speed), 0.0)
        }
    }

    @Test fun remainingTimeIsZeroForUnknownOrInvalidInputs() {
        for (duration in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(0.0, playbackRemainingSeconds(duration, 30.0, 2.0), 0.0)
        }
        for (position in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(0.0, playbackRemainingSeconds(120.0, position, 2.0), 0.0)
        }
        for (speed in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(0.0, playbackRemainingSeconds(120.0, 30.0, speed), 0.0)
        }
    }

    @Test fun mpvSafeOptionsPreserveValuesAndNormalizeKeys() {
        val parsed = SettingsRepo.parseMpvOptions(
            "# comment\n\n -- HWDEC = auto\nprofile=fast\nVIDEO_ALIGN_Y=-1\nspeed=1.25\nvf=scale=1280:720\naf=volume=0.5\n",
        )
        assertEquals(
            listOf("hwdec" to "auto", "profile" to "fast", "video-align-y" to "-1",
                "speed" to "1.25", "vf" to "scale=1280:720", "af" to "volume=0.5"),
            parsed.options,
        )
        assertTrue(parsed.rejected.isEmpty())
        assertEquals("# comment\n\nhwdec=auto\nprofile=fast\nvideo-align-y=-1\nspeed=1.25\nvf=scale=1280:720\naf=volume=0.5\n",
            parsed.normalizedText)
    }

    @Test fun mpvFileWritersCannotEnterAcceptedOptionsViaAliases() {
        val keys = listOf("log-file", "--LOG_FILE", "-- no-log-file", "stream-record",
            "stream-dump", "screenshot-dir", "screenshot-directory", "screenshot-template",
            "watch-later-directory", "save-position-on-quit", "no-save-position-on-quit",
            "cache-dir", "cache-on-disk", "gpu-shader-cache-dir", "icc-cache-dir",
            "o", "ovc", "oac", "of", "ofopts", "ovcopts", "oacopts", "orawts",
            "ocopy-metadata", "oset-metadata", "oremove-metadata")
        val parsed = SettingsRepo.parseMpvOptions(
            keys.joinToString("\n") { "$it=private-output" } + "\nhwdec=auto",
        )
        assertEquals(listOf("hwdec" to "auto"), parsed.options)
        assertEquals(keys.size, parsed.rejected.size)
        assertTrue(parsed.rejected.all { it.reason == SettingsRepo.OptionRejectionReason.FILE_WRITING })
        assertFalse(parsed.rejectionMessage().contains("private-output"))
    }

    @Test fun mpvOutputSelectorsAndListOperationsRemainAppManaged() {
        val keys = listOf("ao", "vo", "-- AO_APPEND", "vo-add", "ao-pre", "vo-del",
            "ao-clr", "vo-set", "ao-toggle", "vo-remove", "ao-help", "no-vo",
            "no-ao-append")
        val parsed = SettingsRepo.parseMpvOptions(keys.joinToString("\n") { "$it=pcm:file=secret" })
        assertTrue(parsed.options.isEmpty())
        assertEquals(keys.size, parsed.rejected.size)
        assertTrue(parsed.rejected.all { it.reason == SettingsRepo.OptionRejectionReason.OUTPUT_MANAGEMENT })
        assertFalse(parsed.rejectionMessage().contains("secret"))
    }

    @Test fun mpvExistingSecurityRestrictionsKeepCategoricalReasons() {
        val parsed = SettingsRepo.parseMpvOptions(
            "--config-dir=secret\n---load-script=secret\nhttp-header-fields=secret\n" +
                "-- input-conf=secret\nsub-file=secret\n--ytdl-path=secret\nhwdec=auto",
        )
        assertEquals(listOf("hwdec" to "auto"), parsed.options)
        assertEquals(
            listOf(SettingsRepo.OptionRejectionReason.CONFIG, SettingsRepo.OptionRejectionReason.SCRIPTS,
                SettingsRepo.OptionRejectionReason.NETWORK, SettingsRepo.OptionRejectionReason.APP_CONTROLLED,
                SettingsRepo.OptionRejectionReason.EXTERNAL_PATHS, SettingsRepo.OptionRejectionReason.SCRIPTS),
            parsed.rejected.map { it.reason },
        )
        assertFalse(parsed.rejectionMessage().contains("secret"))
    }

    @Test fun mpvMalformedLinesAreRejectedRatherThanSilentlyLost() {
        val parsed = SettingsRepo.parseMpvOptions(
            "# normal comment\n\nprivate-missing-equals\n=secret\nbad key=secret\nhwdec=\nprofile=fast",
        )
        assertEquals(listOf("profile" to "fast"), parsed.options)
        assertEquals(4, parsed.rejected.size)
        assertTrue(parsed.rejected.all { it.reason == SettingsRepo.OptionRejectionReason.MALFORMED })
        assertFalse(parsed.rejectionMessage().contains("secret"))
        assertFalse(parsed.rejectionMessage().contains("private-missing-equals"))
    }

    @Test fun resolveFileRejectsTraversal() {
        assertEquals(null, MpvPath.resolveFile("content://x/document/primary:..%2F..%2Fsecret"))
        assertEquals(null, MpvPath.resolveFile("content://x/document/1234:Movies/a.mp4"))
        assertEquals(null, MpvPath.resolveFile("file:///etc/passwd"))
    }

    @Test fun exportedPathsDoNotLeakSpacedOrQuotedFilenameTails() {
        val records = listOf(
            "10-02 12:34:56.789 I/mpv: config=/data/user/0/example/개인 설정/비밀 파일.conf ready",
            "10-02 12:34:56.789 E/mpv: java.io.FileNotFoundException: /storage/emulated/0/가족 여행/서울 사진.mp4 (Permission denied)",
            "10-02 12:34:56.789 E/mpv: java.io.FileNotFoundException: '/sdcard/가족 여행/서울 사진.mp4'",
            "10-02 12:34:56.789 E/mpv: java.io.FileNotFoundException: \"/mnt/media_rw/1234-ABCD/가족 여행/서울 사진.mp4\"",
        )
        val masked = AppLog.maskPaths(records.joinToString("\n"))
        for (privatePart in listOf("/data/user", "/storage", "/sdcard", "/mnt", "개인 설정",
                "비밀 파일", "가족 여행", "서울 사진")) {
            assertFalse("Export contains $privatePart", masked.contains(privatePart))
        }
        assertTrue(masked.contains("10-02 12:34:56.789 E/mpv: java.io.FileNotFoundException:"))
    }

    @Test fun exportedUrisAndEncodedJsonPathsUseTheSamePrivacyPolicy() {
        val records = listOf(
            "I/library: content://private.provider/document/primary%3AMovies%2FSecret%20Holiday.mp4",
            "E/mpv: java.io.IOException: file:///storage/emulated/0/비밀 여행.mp4",
            """E/mpv: java.io.IOException: {"path":"%2Fstorage%2Femulated%2F0%2FHidden%20Clip.mp4"}""",
            """E/mpv: java.io.IOException: {"path":"\/storage\/emulated\/0\/Private Movie.mp4"}""",
            """E/mpv: java.io.IOException: {"path":"\u002fdata\u002fuser\u002f0\u002fPrivate Cache.mp4"}""",
            "E/mpv: java.io.IOException: %252Fstorage%252Femulated%252F0%252FNested Secret.mp4",
        )
        val masked = AppLog.maskPaths(records.joinToString("\n"))
        for (privatePart in listOf("private.provider", "Secret", "Holiday", "비밀 여행", "Hidden",
                "Private Movie", "Private Cache", "Nested Secret", "storage", "emulated")) {
            assertFalse("Export contains $privatePart", masked.contains(privatePart))
        }
        assertTrue(masked.contains("java.io.IOException"))
    }

    @Test fun exportedTitlesAndShortSafSourceLabelsDoNotExposeMediaNames() {
        val records = listOf(
            "I/Player: loading item 2 resume=15s title=가족 여행 최종.mp4",
            """I/Player: {"filename":"Private Wedding.mkv"}""",
            """I/Player: {\"title\":\"Encoded%20Wedding.mkv\"}""",
            "I/Player: display_name='Secret Birthday.mp4'",
            "I/mpv: open primary:Movies/개인 영상/Hidden Film.mp4 via fd://42",
            "I/mpv: open 1234-ABCD:Movies/Private Volume.mp4 via real path",
            "I/mpv: open Private Filename.mp4 via direct file path",
            "W/mpv: openFileDescriptor failed for Secret Clip.mp4: java.io.FileNotFoundException",
        )
        val masked = AppLog.maskPaths(records.joinToString("\n"))
        for (privatePart in listOf("가족 여행", "Private Wedding", "Encoded%20Wedding", "Secret Birthday", "primary:",
                "개인 영상", "Hidden Film", "1234-ABCD", "Private Volume", "Private Filename", "Secret Clip")) {
            assertFalse("Export contains $privatePart", masked.contains(privatePart))
        }
        assertTrue(masked.contains("loading item 2 resume=15s title="))
        assertTrue(masked.contains("openFileDescriptor failed for"))
        assertTrue(masked.contains("java.io.FileNotFoundException"))
    }

    @Test fun combinedExportProtectsCrashMessagesAndRecentLogsWithoutLosingDiagnostics() {
        val report = """
            MoVo debug report
            device=Generic Model api=28
            --- recent log ---
            10-02 12:34:56.789 I/Player: loading item 1 resume=9s title=Secret Family.mp4
            --- crash report ---
            MoVo crash report
            java.lang.IllegalStateException: cannot read content://private.provider/document/primary:Movies/Secret Crash.mp4
                at com.example.Player.load(Player.kt:42)
            Caused by: java.io.FileNotFoundException: /storage/emulated/0/개인 폴더/비밀 영상.mp4
            --- recent log ---
            W/mpv: cacheCopy failed for primary:Movies/Hidden Subtitle.srt: java.io.IOException
        """.trimIndent()
        val masked = AppLog.maskPaths(report)
        for (privatePart in listOf("Secret Family", "private.provider", "Secret Crash", "/storage",
                "개인 폴더", "비밀 영상", "primary:", "Hidden Subtitle")) {
            assertFalse("Combined export contains $privatePart", masked.contains(privatePart))
        }
        for (diagnostic in listOf("device=Generic Model api=28", "10-02 12:34:56.789",
                "java.lang.IllegalStateException", "java.io.FileNotFoundException", "java.io.IOException",
                "at com.example.Player.load(Player.kt:42)")) {
            assertTrue("Lost diagnostic $diagnostic", masked.contains(diagnostic))
        }
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
    @Test fun onlyCurrentEntryEofCanAdvanceOrRepeat() {
        val id = 9_007_199_254_740_993L
        assertEquals(PlaybackEndAction.ADVANCE, playbackEndAction(0, 0, id, id, false, true))
        assertEquals(PlaybackEndAction.REPEAT, playbackEndAction(0, 0, id, id, true, true))
        assertEquals(PlaybackEndAction.PAUSE, playbackEndAction(0, 0, id, id, false, false))
        for (reason in listOf(2, 3, 5)) {
            assertEquals(PlaybackEndAction.IGNORE, playbackEndAction(reason, 0, id, id, true, true))
        }
        assertEquals(PlaybackEndAction.ERROR, playbackEndAction(4, -13, id, id, true, true))
        assertEquals(PlaybackEndAction.ERROR, playbackEndAction(0, -13, id, id, true, true))
        assertEquals(PlaybackEndAction.IGNORE, playbackEndAction(0, 0, id, id + 1, true, true))
        assertEquals(PlaybackEndAction.IGNORE, playbackEndAction(4, -13, id, id + 1, true, true))
        assertEquals(PlaybackEndAction.IGNORE, playbackEndAction(0, 0, id, null, true, true))
    }
}
