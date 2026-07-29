package com.noteapp.security

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedSessionArtifactStore(
    private val rootDirectory: File,
    private val encryptionKey: SecretKey,
    private val secureRandom: SecureRandom = SecureRandom(),
) : SessionArtifactStore {
    override fun isEncrypted(file: File): Boolean {
        if (!file.isFile || file.length() < MAGIC.size) return false
        val candidate = ByteArray(MAGIC.size)
        FileInputStream(file).use { input ->
            if (input.read(candidate) != candidate.size) return false
        }
        return candidate.contentEquals(MAGIC)
    }

    override fun openInput(file: File): InputStream {
        check(file.isFile) { "ARTIFACT_MISSING" }
        check(isEncrypted(file)) { "ARTIFACT_NOT_ENCRYPTED" }
        val input = DataInputStream(FileInputStream(file).buffered())
        return try {
            val header = readAndValidateHeader(input, file)
            EncryptedFrameInputStream(input, header)
        } catch (failure: Throwable) {
            input.close()
            throw failure
        }
    }

    override fun openAppend(file: File): ArtifactAppendSink {
        ensureParent(file)
        if (file.exists() && !isEncrypted(file)) {
            migrateFile(file)
        }
        val scan = if (file.isFile) scan(file, repairTruncatedTail = true) else ScanResult(0, 0)
        val header = headerFor(file)
        val output = FileOutputStream(file, true)
        return try {
            if (file.length() == 0L) {
                output.write(header)
                output.fd.sync()
            }
            EncryptedAppendSink(
                output = output,
                header = header,
                initialSequence = scan.frameCount,
                initialPlaintextSize = scan.plaintextSize,
            )
        } catch (failure: Throwable) {
            output.close()
            throw failure
        }
    }

    override fun plaintextSize(file: File): Long = scan(file).plaintextSize

    override fun recoverAppend(file: File): Boolean =
        scan(file, repairTruncatedTail = true).repairedTruncatedTail

    override fun writeBytesAtomically(file: File, content: ByteArray) {
        ensureParent(file)
        val temporary = File(file.parentFile, "${file.name}$WRITE_TEMP_SUFFIX")
        deleteChecked(temporary)
        writeEncryptedFile(
            logicalFile = file,
            outputFile = temporary,
            input = ByteArrayInputStream(content),
        )
        verifyFully(temporary, logicalFile = file)
        replaceAtomically(temporary, file)
        restrictToOwner(file)
    }

    override fun migrateAll() {
        synchronized(MIGRATION_LOCK) {
            if (!rootDirectory.exists()) {
                check(rootDirectory.mkdirs()) { "ARTIFACT_ROOT_CREATE_FAILED" }
                restrictDirectoryToOwner(rootDirectory)
                return
            }
            recoverMigrationArtifacts()
            recoverLegacyPlaintextTemporaries()
            rootDirectory.walkBottomUp()
                .filter(File::isFile)
                .filterNot { file ->
                    file.name.endsWith(WRITE_TEMP_SUFFIX) ||
                        file.name.endsWith(MIGRATION_TEMP_SUFFIX) ||
                        file.name.endsWith(PLAINTEXT_BACKUP_SUFFIX)
                }
                .forEach(::migrateFile)
            check(rootDirectory.walkTopDown().filter(File::isFile).all(::isEncrypted)) {
                "PLAINTEXT_SESSION_ARTIFACT_REMAINS"
            }
            restrictDirectoryTree()
        }
    }

    private fun migrateFile(file: File) {
        if (isEncrypted(file)) return
        ensureInsideRoot(file)
        val temporary = File(file.path + MIGRATION_TEMP_SUFFIX)
        val backup = File(file.path + PLAINTEXT_BACKUP_SUFFIX)
        recoverSingleMigration(file, temporary, backup)
        if (isEncrypted(file)) return

        deleteChecked(temporary)
        FileInputStream(file).buffered().use { input ->
            writeEncryptedFile(file, temporary, input)
        }
        verifyFully(temporary, logicalFile = file)
        restrictToOwner(temporary)
        try {
            replaceAtomically(temporary, file)
        } catch (_: AtomicMoveNotSupportedException) {
            moveWithoutReplace(file, backup)
            try {
                moveWithoutReplace(temporary, file)
                verifyFully(file)
                deleteChecked(backup)
            } catch (failure: Throwable) {
                try {
                    if (backup.exists()) {
                        deleteChecked(file)
                        moveWithoutReplace(backup, file)
                    }
                } catch (restoreFailure: Throwable) {
                    failure.addSuppressed(restoreFailure)
                }
                throw failure
            }
        }
        verifyFully(file)
        deleteChecked(backup)
        restrictToOwner(file)
    }

    private fun recoverMigrationArtifacts() {
        rootDirectory.walkBottomUp()
            .filter(File::isFile)
            .filter { it.name.endsWith(PLAINTEXT_BACKUP_SUFFIX) }
            .forEach { backup ->
                val target = File(backup.path.removeSuffix(PLAINTEXT_BACKUP_SUFFIX))
                val temporary = File(target.path + MIGRATION_TEMP_SUFFIX)
                recoverSingleMigration(target, temporary, backup)
            }
        rootDirectory.walkBottomUp()
            .filter(File::isFile)
            .filter { it.name.endsWith(MIGRATION_TEMP_SUFFIX) }
            .forEach { temporary ->
                val target = File(temporary.path.removeSuffix(MIGRATION_TEMP_SUFFIX))
                if (target.exists()) {
                    deleteChecked(temporary)
                }
            }
        rootDirectory.walkBottomUp()
            .filter(File::isFile)
            .filter { it.name.endsWith(WRITE_TEMP_SUFFIX) }
            .forEach(::deleteChecked)
    }

    private fun recoverLegacyPlaintextTemporaries() {
        rootDirectory.walkBottomUp()
            .filter(File::isFile)
            .filter { file ->
                file.name.endsWith(LEGACY_TEMP_SUFFIX) &&
                    !file.name.endsWith(WRITE_TEMP_SUFFIX) &&
                    !file.name.endsWith(MIGRATION_TEMP_SUFFIX)
            }
            .forEach { temporary ->
                val target = File(temporary.path.removeSuffix(LEGACY_TEMP_SUFFIX))
                if (target.exists()) {
                    deleteChecked(temporary)
                } else {
                    moveWithoutReplace(temporary, target)
                }
            }
    }

    private fun recoverSingleMigration(
        target: File,
        temporary: File,
        backup: File,
    ) {
        if (!backup.exists()) return
        when {
            target.exists() && isEncrypted(target) -> {
                try {
                    verifyFully(target)
                    deleteChecked(temporary)
                    deleteChecked(backup)
                } catch (failure: SecurityException) {
                    deleteChecked(target)
                    moveWithoutReplace(backup, target)
                    deleteChecked(temporary)
                }
            }
            !target.exists() && temporary.exists() -> {
                verifyFully(temporary, logicalFile = target)
                moveWithoutReplace(temporary, target)
                verifyFully(target)
                deleteChecked(backup)
            }
            !target.exists() -> moveWithoutReplace(backup, target)
            else -> throw SecurityException("AMBIGUOUS_ARTIFACT_MIGRATION_STATE")
        }
    }

    private fun writeEncryptedFile(
        logicalFile: File,
        outputFile: File,
        input: InputStream,
    ) {
        val header = headerFor(logicalFile)
        FileOutputStream(outputFile).use { rawOutput ->
            rawOutput.write(header)
            val output = DataOutputStream(rawOutput.buffered())
            val buffer = ByteArray(MIGRATION_FRAME_BYTES)
            try {
                var sequence = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    writeFrame(output, header, sequence++, buffer, 0, read)
                }
            } finally {
                buffer.fill(0)
            }
            output.flush()
            rawOutput.fd.sync()
        }
    }

    private fun scan(
        file: File,
        repairTruncatedTail: Boolean = false,
    ): ScanResult {
        var size = 0L
        var frames = 0
        var encryptedInput: EncryptedFrameInputStream? = null
        try {
            openInput(file).use { input ->
                val frameInput = input as EncryptedFrameInputStream
                encryptedInput = frameInput
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    size += read
                }
                frames = frameInput.framesRead
            }
        } catch (failure: SecurityException) {
            val recoverableInput = encryptedInput
            if (
                !repairTruncatedTail ||
                failure.message != "ARTIFACT_FRAME_TRUNCATED" ||
                recoverableInput == null
            ) {
                throw failure
            }
            frames = recoverableInput.framesRead
            RandomAccessFile(file, "rw").use { randomAccess ->
                randomAccess.setLength(recoverableInput.ciphertextBytesConsumed)
                randomAccess.fd.sync()
            }
            restrictToOwner(file)
            return ScanResult(size, frames, repairedTruncatedTail = true)
        }
        return ScanResult(size, frames)
    }

    private fun verifyFully(
        file: File,
        logicalFile: File = file,
    ) {
        DataInputStream(FileInputStream(file).buffered()).use { input ->
            val header = readAndValidateHeader(input, logicalFile)
            EncryptedFrameInputStream(input, header).use { decrypted ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (decrypted.read(buffer) >= 0) Unit
            }
        }
    }

    private fun headerFor(file: File): ByteArray {
        val pathHash = MessageDigest.getInstance("SHA-256")
            .digest(relativeId(file).toByteArray(StandardCharsets.UTF_8))
        return ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC)
            .putInt(VERSION)
            .put(pathHash)
            .array()
    }

    private fun readAndValidateHeader(
        input: DataInputStream,
        logicalFile: File,
    ): ByteArray {
        val header = ByteArray(HEADER_BYTES)
        try {
            input.readFully(header)
        } catch (failure: EOFException) {
            throw SecurityException("ARTIFACT_HEADER_TRUNCATED", failure)
        }
        if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw SecurityException("ARTIFACT_MAGIC_INVALID")
        }
        if (ByteBuffer.wrap(header, MAGIC.size, Int.SIZE_BYTES).int != VERSION) {
            throw SecurityException("ARTIFACT_VERSION_UNSUPPORTED")
        }
        if (!header.contentEquals(headerFor(logicalFile))) {
            throw SecurityException("ARTIFACT_PATH_BINDING_FAILED")
        }
        return header
    }

    private fun writeFrame(
        output: DataOutputStream,
        header: ByteArray,
        sequence: Int,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(length in 1..MAX_FRAME_BYTES) { "ARTIFACT_FRAME_SIZE_INVALID" }
        val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(frameAad(header, sequence, length))
        val encrypted = cipher.doFinal(buffer, offset, length)
        output.writeInt(length)
        output.write(iv)
        output.write(encrypted)
    }

    private fun decryptFrame(
        input: DataInputStream,
        header: ByteArray,
        sequence: Int,
    ): ByteArray? {
        val first = input.read()
        if (first < 0) return null
        val lengthBytes = byteArrayOf(
            first.toByte(),
            readRequired(input),
            readRequired(input),
            readRequired(input),
        )
        val length = ByteBuffer.wrap(lengthBytes).int
        if (length !in 1..MAX_FRAME_BYTES) {
            throw SecurityException("ARTIFACT_FRAME_SIZE_INVALID")
        }
        val iv = ByteArray(GCM_IV_BYTES)
        val encrypted = ByteArray(length + GCM_TAG_BYTES)
        try {
            input.readFully(iv)
            input.readFully(encrypted)
        } catch (failure: EOFException) {
            throw SecurityException("ARTIFACT_FRAME_TRUNCATED", failure)
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(frameAad(header, sequence, length))
            cipher.doFinal(encrypted)
        } catch (failure: AEADBadTagException) {
            throw SecurityException("ARTIFACT_AUTHENTICATION_FAILED", failure)
        }
    }

    private fun frameAad(
        header: ByteArray,
        sequence: Int,
        length: Int,
    ): ByteArray = ByteBuffer.allocate(header.size + Int.SIZE_BYTES * 2)
        .put(header)
        .putInt(sequence)
        .putInt(length)
        .array()

    private fun readRequired(input: DataInputStream): Byte {
        val value = input.read()
        if (value < 0) throw SecurityException("ARTIFACT_FRAME_TRUNCATED")
        return value.toByte()
    }

    private fun relativeId(file: File): String {
        ensureInsideRoot(file)
        return rootDirectory.canonicalFile.toPath()
            .relativize(file.canonicalFile.toPath())
            .joinToString("/") { it.toString() }
    }

    private fun ensureInsideRoot(file: File) {
        val root = rootDirectory.canonicalFile.toPath()
        val candidate = file.canonicalFile.toPath()
        require(candidate.startsWith(root) && candidate != root) {
            "ARTIFACT_OUTSIDE_SESSION_ROOT"
        }
    }

    private fun ensureParent(file: File) {
        ensureInsideRoot(file)
        val directory = requireNotNull(file.parentFile)
        check(directory.exists() || directory.mkdirs()) {
            "ARTIFACT_DIRECTORY_CREATE_FAILED"
        }
        restrictDirectoryToOwner(directory)
    }

    private fun replaceAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (failure: AtomicMoveNotSupportedException) {
            throw failure
        }
    }

    private fun moveWithoutReplace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun deleteChecked(file: File) {
        if (file.exists()) check(file.delete()) { "ARTIFACT_DELETE_FAILED" }
    }

    private fun restrictDirectoryTree() {
        rootDirectory.walkBottomUp().filter(File::isDirectory).forEach(::restrictDirectoryToOwner)
        rootDirectory.walkBottomUp().filter(File::isFile).forEach(::restrictToOwner)
    }

    private fun restrictDirectoryToOwner(directory: File) {
        directory.setReadable(false, false)
        directory.setWritable(false, false)
        directory.setExecutable(false, false)
        directory.setReadable(true, true)
        directory.setWritable(true, true)
        directory.setExecutable(true, true)
    }

    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private inner class EncryptedAppendSink(
        private val output: FileOutputStream,
        private val header: ByteArray,
        initialSequence: Int,
        initialPlaintextSize: Long,
    ) : ArtifactAppendSink {
        private val dataOutput = DataOutputStream(output.buffered())
        private var sequence = initialSequence
        private var closed = false

        override var plaintextSize: Long = initialPlaintextSize
            private set

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            check(!closed) { "ARTIFACT_SINK_CLOSED" }
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
                "ARTIFACT_WRITE_RANGE_INVALID"
            }
            var cursor = offset
            var remaining = length
            while (remaining > 0) {
                val frameLength = minOf(remaining, MAX_FRAME_BYTES)
                writeFrame(dataOutput, header, sequence++, buffer, cursor, frameLength)
                cursor += frameLength
                remaining -= frameLength
                plaintextSize += frameLength
            }
        }

        override fun sync() {
            check(!closed) { "ARTIFACT_SINK_CLOSED" }
            dataOutput.flush()
            output.fd.sync()
        }

        override fun close() {
            if (closed) return
            sync()
            dataOutput.close()
            closed = true
        }
    }

    private inner class EncryptedFrameInputStream(
        input: DataInputStream,
        private val header: ByteArray,
    ) : FilterInputStream(input) {
        private val dataInput = input
        private var frame = ByteArray(0)
        private var offset = 0
        private var sequence = 0

        val framesRead: Int
            get() = sequence

        var ciphertextBytesConsumed: Long = HEADER_BYTES.toLong()
            private set

        override fun read(): Int {
            if (!ensureFrame()) return -1
            return frame[offset++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, targetOffset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!ensureFrame()) return -1
            val count = minOf(length, frame.size - offset)
            frame.copyInto(buffer, targetOffset, offset, offset + count)
            offset += count
            return count
        }

        override fun skip(count: Long): Long {
            if (count <= 0) return 0
            var remaining = count
            while (remaining > 0 && ensureFrame()) {
                val skipped = minOf(remaining, (frame.size - offset).toLong()).toInt()
                offset += skipped
                remaining -= skipped
            }
            return count - remaining
        }

        override fun close() {
            frame.fill(0)
            super.close()
        }

        private fun ensureFrame(): Boolean {
            if (offset < frame.size) return true
            frame.fill(0)
            frame = decryptFrame(dataInput, header, sequence) ?: return false
            ciphertextBytesConsumed +=
                Int.SIZE_BYTES + GCM_IV_BYTES + frame.size + GCM_TAG_BYTES
            sequence += 1
            offset = 0
            return true
        }
    }

    private data class ScanResult(
        val plaintextSize: Long,
        val frameCount: Int,
        val repairedTruncatedTail: Boolean = false,
    )

    companion object {
        private const val VERSION = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val MAX_FRAME_BYTES = 1024 * 1024
        private const val MIGRATION_FRAME_BYTES = 64 * 1024
        private const val WRITE_TEMP_SUFFIX = ".secure-write.tmp"
        private const val MIGRATION_TEMP_SUFFIX = ".encrypted.tmp"
        private const val PLAINTEXT_BACKUP_SUFFIX = ".plaintext.backup"
        private const val LEGACY_TEMP_SUFFIX = ".tmp"
        private val MAGIC = "NAARTF01".toByteArray(StandardCharsets.US_ASCII)
        private val HEADER_BYTES = MAGIC.size + Int.SIZE_BYTES + 32
        private val MIGRATION_LOCK = Any()
    }
}
