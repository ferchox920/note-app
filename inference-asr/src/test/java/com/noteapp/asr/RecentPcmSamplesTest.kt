package com.noteapp.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RecentPcmSamplesTest {
    @Test
    fun `retains only the newest primitive samples in stream order`() {
        val samples = RecentPcmSamples(capacity = 4)

        samples.append(shortArrayOf(1, 2, 3))
        samples.append(shortArrayOf(4, 5, 6))

        assertArrayEquals(shortArrayOf(3, 4, 5, 6), samples.snapshot())
    }

    @Test
    fun `clear removes buffered pre-roll`() {
        val samples = RecentPcmSamples(capacity = 3)
        samples.append(shortArrayOf(1, 2, 3))

        samples.clear()
        samples.append(shortArrayOf(4, 5))

        assertArrayEquals(shortArrayOf(4, 5), samples.snapshot())
    }
}
