package com.noteapp.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WrappedPassphraseCodecTest {
    @Test
    fun `codec round trips authenticated payload fields`() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(80) { (it + 12).toByte() }

        val decoded = WrappedPassphraseCodec.decode(
            WrappedPassphraseCodec.encode(iv, ciphertext),
        )

        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ciphertext, decoded.encryptedPassphrase)
    }

    @Test
    fun `codec rejects malformed and trailing data`() {
        assertThrows(IllegalArgumentException::class.java) {
            WrappedPassphraseCodec.decode(ByteArray(72))
        }

        val valid = WrappedPassphraseCodec.encode(ByteArray(12), ByteArray(80))
        assertThrows(IllegalArgumentException::class.java) {
            WrappedPassphraseCodec.decode(valid + 1)
        }
    }
}
