package com.noteapp.asr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WhisperModelVerifierTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("whisper-model-test").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `validates exact size and sha256`() {
        val file = File(directory, "model.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val descriptor = WhisperModelDescriptor(
            id = "test",
            fileName = file.name,
            quantization = "test",
            expectedBytes = 4,
            sha256 = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
        )

        assertEquals(ModelVerificationResult.Valid, WhisperModelVerifier.verify(file, descriptor))
    }
}

