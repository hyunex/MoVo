package com.example.mpvlibrary

import com.example.mpvlibrary.data.PendingProgress
import com.example.mpvlibrary.data.ProgressUpdate
import org.junit.Assert.*
import org.junit.Test

class ProgressCoalescingTest {
    @Test fun repeatedSamplesBoundPendingWorkAndKeepLatestBackwardSeek() {
        val pending = PendingProgress()
        repeat(10_000) { pending.offer(ProgressUpdate("video", it.toDouble(), 20_000.0, it.toLong())) }
        pending.offer(ProgressUpdate("video", 5.0, 20_000.0, 10_000))
        assertEquals(ProgressUpdate("video", 5.0, 20_000.0, 10_000), pending.take())
        assertNull(pending.take())
    }

    @Test fun coalescingNeverDropsAnotherItemOrCompletedMeaning() {
        val pending = PendingProgress()
        pending.offer(ProgressUpdate("first", 60.0, 60.0, 1, completed = true))
        pending.offer(ProgressUpdate("second", 7.0, 90.0, 2))
        pending.offer(ProgressUpdate("first", 4.0, 60.0, 3))
        assertEquals(ProgressUpdate("first", 4.0, 60.0, 3, completed = true), pending.take())
        assertEquals(ProgressUpdate("second", 7.0, 90.0, 2), pending.take())
        assertNull(pending.take())
    }

    @Test fun updatesAcceptedDuringWriteRemainPending() {
        val pending = PendingProgress()
        pending.offer(ProgressUpdate("video", 20.0, 60.0, 1))
        val writing = pending.take()
        pending.offer(ProgressUpdate("video", 8.0, 60.0, 2))
        assertEquals(20.0, writing!!.position, 0.0)
        assertEquals(8.0, pending.take()!!.position, 0.0)
    }

    @Test fun unknownDurationEofRetainsCompletionWithoutInventedDuration() {
        val pending = PendingProgress()
        pending.offer(ProgressUpdate("video", 8.0, 0.0, 1, completed = true))
        assertEquals(ProgressUpdate("video", 8.0, 0.0, 1, completed = true), pending.take())
    }

    @Test fun unknownDurationDoesNotDiscardPreviouslyAcceptedKnownDuration() {
        val pending = PendingProgress()
        pending.offer(ProgressUpdate("video", 60.0, 60.0, 1, completed = true))
        pending.offer(ProgressUpdate("video", 5.0, 0.0, 2))
        assertEquals(ProgressUpdate("video", 5.0, 60.0, 2, completed = true), pending.take())
    }
}
