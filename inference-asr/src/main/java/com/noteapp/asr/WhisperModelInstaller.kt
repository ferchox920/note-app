package com.noteapp.asr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

object WhisperModelInstaller {
    suspend fun install(
        input: InputStream,
        modelsDirectory: File,
        descriptor: WhisperModelDescriptor,
    ): File = withContext(Dispatchers.IO) {
        modelsDirectory.mkdirs()
        val destination = File(modelsDirectory, descriptor.fileName)
        val temporary = File(modelsDirectory, "${descriptor.fileName}.partial")
        try {
            input.use { source -> temporary.outputStream().buffered().use(source::copyTo) }
            val verification = WhisperModelVerifier.verify(temporary, descriptor)
            require(verification == ModelVerificationResult.Valid) {
                "Selected model does not match ${descriptor.fileName}: $verification"
            }
            if (destination.exists() && !destination.delete()) {
                error("Unable to replace ${descriptor.fileName}")
            }
            check(temporary.renameTo(destination)) { "Unable to install ${descriptor.fileName}" }
            destination
        } finally {
            temporary.delete()
        }
    }
}
