package com.example.mpvlibrary

import com.example.mpvlibrary.data.AppLog
import com.example.mpvlibrary.data.SettingsRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityBoundaryTest {
    @Test fun fileOpeningGraphsAndTheirListAliasesCannotReachPlayback() {
        val unsafe = listOf(
            "vf=lavfi=[movie=/tmp/MOVO_FAKE_SENTINEL.mp4]",
            "--VF_APPEND=movie=filename=/tmp/MOVO_FAKE_SENTINEL.mp4",
            "af=amovie=/tmp/MOVO_FAKE_SENTINEL.wav",
            "af-add=lavfi=[amovie=/tmp/MOVO_FAKE_SENTINEL.wav]",
            "vf=scale=1280:720,subtitles=/tmp/MOVO_FAKE_SENTINEL.srt",
            "vf=drawtext=textfile=/tmp/MOVO_FAKE_SENTINEL.txt",
            "lavfi-complex=movie=/tmp/MOVO_FAKE_SENTINEL.mp4[vo]",
            "vf=scale=1280:720;movie=/tmp/MOVO_FAKE_SENTINEL.mp4[out]",
            "vf=lavfi-movie=/tmp/MOVO_FAKE_SENTINEL.mp4",
            "vf=scale=1280:720,movie=%30%/tmp/MOVO_FAKE_SENTINEL.mp4",
        )
        val parsed = SettingsRepo.parseMpvOptions(unsafe.joinToString("\n"))
        assertTrue(parsed.options.isEmpty())
        assertEquals(unsafe.size, parsed.rejected.size)
        assertFalse(parsed.rejectionMessage().contains("MOVO_FAKE_SENTINEL"))
    }

    @Test fun renderingAudioAndHardwareTuningRemainAvailable() {
        val options = listOf(
            "hwdec" to "auto", "profile" to "fast", "scale" to "ewa_lanczossharp",
            "deband" to "yes", "video-align-y" to "-1",
            "vf" to "scale=1280:720,crop=1200:700,hflip",
            "af-append" to "volume=0.5,atempo=1.25,equalizer=f=1000:width_type=q:width=1:g=2",
            "icc-profile-auto" to "yes", "lut-type" to "normalized",
        )
        val parsed = SettingsRepo.parseMpvOptions(options.joinToString("\n") { "${it.first}=${it.second}" })
        assertEquals(options, parsed.options)
        assertTrue(parsed.rejected.isEmpty())
    }

    @Test fun pinnedFileOptionsCannotBypassPolicyWithAliases() {
        val keys = listOf(
            "GLSL_SHADER", "glsl-shaders-append", "no-glsl-shader", "icc-profile",
            "sub-ass-styles", "playlist", "chapters-file", "ordered-chapters-files",
            "image-lut", "target-lut", "lut", "demuxer-cache-dir", "watch-history-path",
            "vo-image-outdir", "autoload-files", "sub-auto", "audio-file-auto",
            "sub-fonts-dir", "OSD_FONTS_DIR", "no-osd-fonts-dir", "osd-bar-fonts-dir",
        )
        val parsed = SettingsRepo.parseMpvOptions(keys.joinToString("\n") { "$it=/tmp/MOVO_FAKE_SENTINEL" })
        assertTrue(parsed.options.isEmpty())
        assertEquals(keys.size, parsed.rejected.size)
        assertFalse(parsed.rejectionMessage().contains("MOVO_FAKE_SENTINEL"))
    }

    @Test fun playlistReferenceOverridesRemainAppManaged() {
        val keys = listOf(
            "ACCESS-REFERENCES", "access-references-append", "no-access-references",
            "load-unsafe-playlists", "LOAD_UNSAFE_PLAYLISTS-ADD", "no-load-unsafe-playlists",
        )
        val parsed = SettingsRepo.parseMpvOptions(keys.joinToString("\n") { "$it=no" })
        assertTrue(parsed.options.isEmpty())
        assertEquals(keys.size, parsed.rejected.size)
        assertTrue(parsed.rejected.all { it.reason == SettingsRepo.OptionRejectionReason.APP_CONTROLLED })
    }

    @Test fun nativeAndProviderDiagnosticExportsRemoveNoncanonicalPrivateNames() {
        val records = listOf(
            "E/ffmpeg: Failed to avformat_open_input 'relative MOVO_FAKE_SENTINEL.mp4'",
            "E/file: Cannot open file 'MOVO_FAKE_SENTINEL.mp4': Permission denied",
            "V/icc: Opening ICC profile 'MOVO_FAKE_SENTINEL.icc'",
            "V/icc: Opening 3D LUT cache in file 'MOVO_FAKE_SENTINEL.cache'.",
            "E/provider: java.io.FileNotFoundException: /tmp/MOVO_FAKE_SENTINEL.mp4",
            "E/provider: java.io.IOException: C:\\Movies\\MOVO_FAKE_SENTINEL.mp4",
            "E/provider: java.io.IOException: %252Fvendor%252Fmedia%252FMOVO_FAKE_SENTINEL.mp4",
            "V/gpu: Loading custom LUT 'MOVO_FAKE_SENTINEL.cube'",
            "E/gpu: Failed to read LUT data from MOVO_FAKE_SENTINEL.cube, make sure it's a valid file",
        )
        val exported = AppLog.maskPaths(records.joinToString("\n"))
        assertFalse(exported.contains("MOVO_FAKE_SENTINEL"))
        assertFalse(exported.contains("relative"))
        assertTrue(exported.contains("java.io.FileNotFoundException"))
        assertTrue(exported.contains("java.io.IOException"))
        assertEquals("I/mpv: dropped frames=0\n    at com.example.Player.load(Player.kt:42)",
            AppLog.maskPaths("I/mpv: dropped frames=0\n    at com.example.Player.load(Player.kt:42)"))
    }
}
