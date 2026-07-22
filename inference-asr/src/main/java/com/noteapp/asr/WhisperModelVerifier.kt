package com.noteapp.asr

import java.io.File
import java.security.MessageDigest
import java.util.Locale

sealed interface ModelVerificationResult {
    data object Valid : ModelVerificationResult
    data class InvalidSize(val expected: Long, val actual: Long) : ModelVerificationResult
    data class InvalidChecksum(val expected: String, val actual: String) : ModelVerificationResult
    data object Missing : ModelVerificationResult
}

object WhisperModelVerifier {
    fun verify(file: File, descriptor: WhisperModelDescriptor): ModelVerificationResult {
        if (!file.isFile) return ModelVerificationResult.Missing
        if (file.length() != descriptor.expectedBytes) {
            return ModelVerificationResult.InvalidSize(descriptor.expectedBytes, file.length())
        }
        val actual = sha256(file)
        return if (actual == descriptor.sha256) ModelVerificationResult.Valid
        else ModelVerificationResult.InvalidChecksum(descriptor.sha256, actual)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
        }
    }
}
