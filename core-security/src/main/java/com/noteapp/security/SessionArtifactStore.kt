package com.noteapp.security

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface ArtifactAppendSink : Closeable {
    val plaintextSize: Long

    fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)

    fun sync()
}

interface SessionArtifactStore {
    fun isEncrypted(file: File): Boolean

    fun openInput(file: File): InputStream

    fun openAppend(file: File): ArtifactAppendSink

    fun plaintextSize(file: File): Long

    fun writeBytesAtomically(file: File, content: ByteArray)

    fun migrateAll()

    fun delete(file: File): Boolean = !file.exists() || file.delete()

    fun readBytes(file: File): ByteArray = openInput(file).use(InputStream::readBytes)

    fun readText(
        file: File,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = readBytes(file).toString(charset)

    fun writeTextAtomically(
        file: File,
        content: String,
        charset: Charset = StandardCharsets.UTF_8,
    ) = writeBytesAtomically(file, content.toByteArray(charset))

    fun appendBytes(file: File, content: ByteArray) {
        openAppend(file).use { sink ->
            sink.write(content)
            sink.sync()
        }
    }
}

/**
 * Host-test implementation. Production code must use the Android Keystore-backed store.
 */
class PlaintextSessionArtifactStore(
    private val rootDirectory: File? = null,
) : SessionArtifactStore {
    override fun isEncrypted(file: File): Boolean = false

    override fun openInput(file: File): InputStream = file.inputStream().buffered()

    override fun openAppend(file: File): ArtifactAppendSink {
        check(file.parentFile?.let { it.exists() || it.mkdirs() } == true) {
            "ARTIFACT_DIRECTORY_CREATE_FAILED"
        }
        return PlaintextAppendSink(file)
    }

    override fun plaintextSize(file: File): Long = file.length()

    override fun writeBytesAtomically(file: File, content: ByteArray) {
        check(file.parentFile?.let { it.exists() || it.mkdirs() } == true) {
            "ARTIFACT_DIRECTORY_CREATE_FAILED"
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(content)
            output.fd.sync()
        }
        moveReplacing(temporary, file)
    }

    override fun migrateAll() = Unit

    private class PlaintextAppendSink(file: File) : ArtifactAppendSink {
        private val output = FileOutputStream(file, true)
        private var closed = false

        override var plaintextSize: Long = file.length()
            private set

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            check(!closed) { "ARTIFACT_SINK_CLOSED" }
            output.write(buffer, offset, length)
            plaintextSize += length
        }

        override fun sync() {
            check(!closed) { "ARTIFACT_SINK_CLOSED" }
            output.flush()
            output.fd.sync()
        }

        override fun close() {
            if (closed) return
            sync()
            output.close()
            closed = true
        }
    }

    private companion object {
        fun moveReplacing(source: File, target: File) {
            try {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    }
}
