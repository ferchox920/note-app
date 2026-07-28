package com.noteapp.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrChunkAssemblerTest {
    @Test
    fun `merges nearby speech for offline refinement`() {
        val result = AsrChunkAssembler.assemble(
            listOf(interval(0, 3_000), interval(3_500, 7_500), interval(10_000, 11_000)),
        )

        assertEquals(1, result.size)
        assertEquals(0, result[0].startMs)
        assertEquals(11_000, result[0].endMs)
    }

    @Test
    fun `splits long speech with bounded overlap`() {
        val result = AsrChunkAssembler.assemble(listOf(interval(0, 65_000)))

        assertEquals(3, result.size)
        assertTrue(result.all { it.endMs - it.startMs <= 30_000 })
        assertEquals(29_500, result[1].startMs)
        assertEquals(59_000, result[2].startMs)
    }

    @Test
    fun `does not bridge long silence in offline refinement`() {
        val result = AsrChunkAssembler.assemble(
            listOf(interval(0, 5_000), interval(8_001, 10_000)),
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `benchmark configuration controls chunk duration`() {
        val config = AsrLabConfig(threadCount = 6, maxChunkMs = 10_000)
        val result = AsrChunkAssembler.assemble(listOf(interval(0, 25_000)), config)

        assertEquals("t6-c10s", config.id)
        assertEquals(3, result.size)
        assertTrue(result.all { it.endMs - it.startMs <= 10_000 })
        assertEquals(9_500, result[1].startMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid benchmark configuration`() {
        AsrLabConfig(threadCount = 0)
    }

    private fun interval(startMs: Long, endMs: Long) = SpeechInterval(
        startMs,
        endMs,
        startMs * 32,
        endMs * 32,
    )
}
