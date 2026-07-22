package com.noteapp.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16DecoderTest {
    @Test
    fun `decodes little endian signed pcm16`() {
        val pcm = byteArrayOf(
            0x00, 0x80.toByte(),
            0x00, 0x00,
            0xff.toByte(), 0x7f,
        )

        val decoded = Pcm16Decoder.littleEndianToFloat(pcm)

        assertArrayEquals(floatArrayOf(-1f, 0f, 32767f / 32768f), decoded, 0.00001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects incomplete pcm sample`() {
        Pcm16Decoder.littleEndianToFloat(byteArrayOf(1))
    }

    @Test
    fun `real time factor compares inference and audio durations`() {
        assertEquals(0.5, WhisperEngine.realTimeFactor(2_500, 5_000), 0.0)
    }
}

