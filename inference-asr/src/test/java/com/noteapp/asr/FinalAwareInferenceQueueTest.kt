package com.noteapp.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalAwareInferenceQueueTest {
    @Test
    fun `final work evicts pending partials but not another final`() {
        val queue = FinalAwareInferenceQueue(capacity = 2)
        queue.offer(task(id = 1, final = false))
        queue.offer(task(id = 2, final = false))

        val finalOffer = queue.offer(task(id = 3, final = true))

        assertTrue(finalOffer.accepted)
        assertEquals(2, finalOffer.droppedPartials)
        assertTrue(requireNotNull(queue.poll()).final)
    }

    @Test
    fun `bounded queue refuses a final instead of silently evicting finals`() {
        val queue = FinalAwareInferenceQueue(capacity = 2)
        queue.offer(task(id = 1, final = true))
        queue.offer(task(id = 2, final = true))

        val overflow = queue.offer(task(id = 3, final = true))

        assertFalse(overflow.accepted)
        assertEquals(2, queue.size())
    }

    private fun task(id: Int, final: Boolean) = IncrementalInferenceTask(
        window = IncrementalPcmWindow(id.toLong(), id + 1L, shortArrayOf(id.toShort())),
        segmentStartMs = 0,
        final = final,
        streamEndMs = 1_000,
        enqueuedAtNanos = 0,
    )
}
