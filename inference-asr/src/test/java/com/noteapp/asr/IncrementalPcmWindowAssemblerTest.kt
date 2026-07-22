package com.noteapp.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class IncrementalPcmWindowAssemblerTest {
    @Test
    fun `emits four second windows every three seconds with one second overlap`() {
        val assembler = IncrementalPcmWindowAssembler(sampleRateHz = 10)

        val windows = assembler.append(ShortArray(90) { it.toShort() })

        assertEquals(listOf(0L to 30L, 20L to 60L, 50L to 90L), windows.map { it.startSample to it.endSample })
        assertArrayEquals(ShortArray(40) { (it + 50).toShort() }, windows.last().samples)
    }

    @Test
    fun `output does not depend on input buffer boundaries`() {
        val once = IncrementalPcmWindowAssembler(sampleRateHz = 10)
            .append(ShortArray(60) { it.toShort() })
        val chunkedAssembler = IncrementalPcmWindowAssembler(sampleRateHz = 10)
        val chunked = buildList {
            addAll(chunkedAssembler.append(ShortArray(17) { it.toShort() }))
            addAll(chunkedAssembler.append(ShortArray(43) { (it + 17).toShort() }))
        }

        assertEquals(once.map { it.startSample to it.endSample }, chunked.map { it.startSample to it.endSample })
        once.zip(chunked).forEach { (left, right) -> assertArrayEquals(left.samples, right.samples) }
    }
}
