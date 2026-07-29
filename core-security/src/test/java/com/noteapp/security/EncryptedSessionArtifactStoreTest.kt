package com.noteapp.security

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EncryptedSessionArtifactStoreTest {
    private lateinit var root: File
    private lateinit var store: EncryptedSessionArtifactStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("encrypted-session-artifacts").toFile()
        store = EncryptedSessionArtifactStore(
            rootDirectory = root,
            encryptionKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"),
        )
    }

    @Test
    fun `atomic content and appended frames remain encrypted and readable`() {
        val checkpoint = root.resolve("session-1/checkpoint.json")
        val secret = """{"transcript":"contenido privado"}"""
        store.writeTextAtomically(checkpoint, secret)

        assertTrue(store.isEncrypted(checkpoint))
        assertFalse(checkpoint.readBytes().containsSubsequence(secret.encodeToByteArray()))
        assertEquals(secret, store.readText(checkpoint))

        val pcm = root.resolve("session-1/segment-0000.pcm")
        val first = ByteArray(32_000) { (it % 251).toByte() }
        val second = ByteArray(8_000) { (it % 127).toByte() }
        store.openAppend(pcm).use { sink ->
            sink.write(first)
            sink.write(second)
            sink.sync()
            assertEquals(40_000L, sink.plaintextSize)
        }

        assertArrayEquals(first + second, store.readBytes(pcm))
        assertEquals(40_000L, store.plaintextSize(pcm))
        assertTrue(pcm.length() > 40_000L)
        store.openInput(pcm).use { input ->
            assertEquals(first.size.toLong(), input.skip(first.size.toLong()))
            assertArrayEquals(second, input.readBytes())
        }
    }

    @Test
    fun `authentication and path binding reject tampering or relocation`() {
        val original = root.resolve("session-1/checkpoint.json")
        store.writeTextAtomically(original, "sensitive")
        val tampered = original.readBytes().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        original.writeBytes(tampered)

        assertThrows(SecurityException::class.java) {
            store.readBytes(original)
        }
        assertThrows(SecurityException::class.java) {
            store.recoverAppend(original)
        }

        val source = root.resolve("session-1/vad-segments.json")
        store.writeTextAtomically(source, "timeline")
        val relocated = root.resolve("session-2/vad-segments.json")
        requireNotNull(relocated.parentFile).mkdirs()
        source.copyTo(relocated)
        assertThrows(SecurityException::class.java) {
            store.readBytes(relocated)
        }
    }

    @Test
    fun `migration replaces nested plaintext without changing logical content`() {
        val checkpoint = root.resolve("session-1/checkpoint.json").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("""{"status":"COMPLETED"}""")
        }
        val pcm = root.resolve("session-1/segment-0000.pcm").apply {
            writeBytes(ByteArray(131_077) { (it % 193).toByte() })
        }
        val expectedCheckpoint = checkpoint.readBytes()
        val expectedPcm = pcm.readBytes()

        store.migrateAll()
        store.migrateAll()

        assertTrue(store.isEncrypted(checkpoint))
        assertTrue(store.isEncrypted(pcm))
        assertArrayEquals(expectedCheckpoint, store.readBytes(checkpoint))
        assertArrayEquals(expectedPcm, store.readBytes(pcm))
        assertFalse(
            root.walkTopDown().filter(File::isFile).any { file ->
                file.name.endsWith(".plaintext.backup") ||
                    file.name.endsWith(".encrypted.tmp") ||
                    file.name.endsWith(".secure-write.tmp")
            },
        )
    }

    @Test
    fun `migration recovers backup-only and verified-temporary swap states`() {
        val backupOnlyTarget = root.resolve("session-1/checkpoint.json")
        backupOnlyTarget.parentFile?.mkdirs()
        val backupOnly = File(backupOnlyTarget.path + ".plaintext.backup").apply {
            writeText("backup-only")
        }

        val temporaryTarget = root.resolve("session-2/checkpoint.json")
        store.writeTextAtomically(temporaryTarget, "encrypted-temporary")
        val encryptedTemporary = File(temporaryTarget.path + ".encrypted.tmp")
        temporaryTarget.copyTo(encryptedTemporary)
        assertTrue(temporaryTarget.delete())
        val temporaryBackup = File(temporaryTarget.path + ".plaintext.backup").apply {
            writeText("older-plaintext")
        }
        val promotedLegacyTarget = root.resolve("session-3/checkpoint.json")
        File(promotedLegacyTarget.path + ".tmp").apply {
            parentFile?.mkdirs()
            writeText("promoted-legacy-temporary")
        }
        val retainedLegacyTarget = root.resolve("session-4/checkpoint.json").apply {
            parentFile?.mkdirs()
            writeText("committed-content")
        }
        File(retainedLegacyTarget.path + ".tmp").writeText("uncommitted-content")

        store.migrateAll()

        assertEquals("backup-only", store.readText(backupOnlyTarget))
        assertEquals("encrypted-temporary", store.readText(temporaryTarget))
        assertFalse(backupOnly.exists())
        assertFalse(temporaryBackup.exists())
        assertFalse(encryptedTemporary.exists())
        assertEquals("promoted-legacy-temporary", store.readText(promotedLegacyTarget))
        assertEquals("committed-content", store.readText(retainedLegacyTarget))
        assertFalse(File(promotedLegacyTarget.path + ".tmp").exists())
        assertFalse(File(retainedLegacyTarget.path + ".tmp").exists())
    }

    @Test
    fun `migration restores plaintext backup when promoted ciphertext is corrupt`() {
        val target = root.resolve("session-1/checkpoint.json")
        store.writeTextAtomically(target, "corrupt-ciphertext")
        target.writeBytes(target.readBytes().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        })
        File(target.path + ".plaintext.backup").writeText("recoverable-plaintext")

        store.migrateAll()

        assertTrue(store.isEncrypted(target))
        assertEquals("recoverable-plaintext", store.readText(target))
        assertFalse(File(target.path + ".plaintext.backup").exists())
    }

    @Test
    fun `append recovery discards only an incomplete final frame`() {
        val file = root.resolve("session-1/segment-0000.pcm")
        val retained = ByteArray(3_200) { (it % 193).toByte() }
        val interrupted = ByteArray(3_200) { (it % 157).toByte() }
        store.openAppend(file).use { sink ->
            sink.write(retained)
            sink.write(interrupted)
        }
        RandomAccessFile(file, "rw").use { randomAccess ->
            randomAccess.setLength(randomAccess.length() - 7)
        }

        assertThrows(SecurityException::class.java) {
            store.readBytes(file)
        }
        assertTrue(store.recoverAppend(file))
        assertArrayEquals(retained, store.readBytes(file))
        assertFalse(store.recoverAppend(file))
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean =
        indices.any { start ->
            start + candidate.size <= size &&
                candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
        }
}
