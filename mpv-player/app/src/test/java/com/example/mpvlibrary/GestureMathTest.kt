package com.example.mpvlibrary

import com.example.mpvlibrary.ui.DOUBLE_TAP_TIMEOUT_MS
import com.example.mpvlibrary.ui.TapAction
import com.example.mpvlibrary.ui.TapSequence
import com.example.mpvlibrary.ui.pinchZoom
import org.junit.Assert.assertEquals
import org.junit.Test

class GestureMathTest {
    private val w = 1000f

    @Test
    fun secondTapAt310msIsADoubleTapNotASingleTap() {
        val taps = TapSequence()
        assertEquals(TapAction.Pending, taps.onTap(0, 900f, 0f, w, 10.0))
        assertEquals(TapAction.Seek(10.0, 10.0), taps.onTap(310, 900f, 0f, w, 10.0))
        assertEquals(TapAction.Pending, TapSequence().also { it.onTap(0, 900f, 0f, w, 10.0) }
            .onTap(DOUBLE_TAP_TIMEOUT_MS, 900f, 0f, w, 10.0))
    }

    @Test
    fun repeatedTapsOnSameSideAccumulateSeek() {
        val taps = TapSequence()
        taps.onTap(0, 900f, 0f, w, 10.0)
        assertEquals(TapAction.Seek(10.0, 10.0), taps.onTap(200, 900f, 0f, w, 10.0))
        assertEquals(TapAction.Seek(10.0, 20.0), taps.onTap(400, 880f, 0f, w, 10.0))
        assertEquals(TapAction.Seek(10.0, 30.0), taps.onTap(600, 900f, 0f, w, 10.0))
        // Window expired: next tap starts over as a pending single tap.
        assertEquals(TapAction.Pending, taps.onTap(1200, 900f, 0f, w, 10.0))
    }

    @Test
    fun leftSideSeeksBackAndCenterTogglesOnce() {
        val taps = TapSequence()
        taps.onTap(0, 100f, 0f, w, 10.0)
        assertEquals(TapAction.Seek(-10.0, -10.0), taps.onTap(150, 100f, 0f, w, 10.0))
        val center = TapSequence()
        center.onTap(0, 500f, 0f, w, 10.0)
        assertEquals(TapAction.TogglePause, center.onTap(150, 500f, 0f, w, 10.0))
        assertEquals(TapAction.Pending, center.onTap(300 - 1, 500f, 0f, w, 10.0))
    }

    @Test
    fun secondPinchContinuesFromCurrentZoom() {
        val first = pinchZoom(baseZoom = 0.0, startDist = 100f, dist = 200f)
        assertEquals(1.0, first, 1e-9)
        assertEquals(1.0, pinchZoom(baseZoom = first, startDist = 300f, dist = 300f), 1e-9)
        assertEquals(1.5, pinchZoom(baseZoom = first, startDist = 100f, dist = 141.42136f), 1e-6)
        assertEquals(kotlin.math.log2(3.0), pinchZoom(baseZoom = first, startDist = 100f, dist = 1000f), 1e-9)
        assertEquals(-1.0, pinchZoom(baseZoom = first, startDist = 1000f, dist = 10f), 1e-9)
    }
}
