package com.example.mpvlibrary

import com.example.mpvlibrary.ui.PlaybackIntent
import com.example.mpvlibrary.ui.RequestedSeek
import com.example.mpvlibrary.ui.playbackResumePosition
import org.junit.Assert.*
import org.junit.Test

class PlayerTransitionsTest {
    @Test fun delayedFocusNeverStartsBeforeGainAndForeground() {
        val intent = PlaybackIntent()
        intent.foreground(true)
        assertFalse(intent.canPlay)
        intent.focus(true)
        assertTrue(intent.canPlay)
        intent.foreground(false)
        assertFalse(intent.canPlay)
        intent.focus(false)
        intent.focus(true)
        assertFalse(intent.canPlay)
    }

    @Test fun manualPauseDuringTransientLossCancelsResumeIntent() {
        val intent = PlaybackIntent()
        intent.foreground(true)
        intent.focus(true)
        intent.focus(false)
        assertFalse(intent.canPlay)
        intent.pause()
        intent.focus(true)
        assertFalse(intent.canPlay)
        intent.play()
        assertTrue(intent.canPlay)
    }

    @Test fun transientLossRetainsPlayButPermanentLossRequiresUserPlay() {
        val intent = PlaybackIntent()
        intent.foreground(true)
        intent.focus(true)
        intent.focus(false)
        intent.focus(true)
        assertTrue(intent.canPlay)
        intent.focus(false, permanentLoss = true)
        intent.focus(true)
        assertFalse(intent.canPlay)
        intent.play()
        assertTrue(intent.canPlay)
    }

    @Test fun immediateExitAndStalePollingKeepRequestedSeek() {
        val seek = RequestedSeek()
        seek.request(50.0)
        assertEquals(50.0, seek.sample(4.0), 0.0)
        assertEquals(50.0, seek.sample(49.0), 0.0)
        assertEquals(50.0, seek.confirm(4.0), 0.0)
        assertEquals(50.0, seek.confirm(49.0), 0.0)
        assertEquals(50.1, seek.confirm(50.1), 0.0)
        assertNull(seek.target)
        assertEquals(51.0, seek.sample(51.0), 0.0)
    }

    @Test fun newerBackwardsSeekSupersedesUnconfirmedForwardSeek() {
        val seek = RequestedSeek()
        seek.request(100.0)
        seek.request(10.0)
        assertEquals(10.0, seek.confirm(100.0), 0.0)
        assertEquals(10.0, seek.sample(100.0), 0.0)
        seek.confirm(10.0)
        assertNull(seek.target)
        seek.request(42.0)
        seek.reset()
        assertEquals(0.0, seek.sample(0.0), 0.0)
    }

    @Test fun delayedNativeRestartCanConfirmProgressAtFastSpeed() {
        var nanos = 0L
        val seek = RequestedSeek { nanos }
        seek.request(100.0)
        nanos = 3_000_000_000L
        assertEquals(100.0, seek.sample(115.0), 0.0)
        assertEquals(115.0, seek.confirm(115.0, effectiveSpeed = 5.0), 0.0)
        assertNull(seek.target)
    }

    @Test fun recreationKeepsExactEarlyPositionEvenForWatchedItem() {
        assertEquals(3.25, playbackResumePosition(false, 3.25, 50.0, true), 0.0)
        assertEquals(0.0, playbackResumePosition(false, 0.0, 50.0, false), 0.0)
        assertEquals(0.0, playbackResumePosition(true, 3.25, 50.0, false), 0.0)
        assertEquals(50.0, playbackResumePosition(false, null, 50.0, false), 0.0)
        assertEquals(0.0, playbackResumePosition(false, null, 50.0, true), 0.0)
        assertEquals(50.0, playbackResumePosition(false, Double.NaN, 50.0, false), 0.0)
    }
}
