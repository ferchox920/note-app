package com.noteapp.security

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSessionArtifactStoreTest {
    @Test
    fun plaintextMigrationAndStreamingWritesAreEncryptedAndFailClosed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.filesDir, "recordings")
        root.deleteRecursively()
        deleteArtifactMarker(context)
        deleteArtifactKey()
        val checkpoint = File(root, "session-device/checkpoint.json").apply {
            parentFile?.mkdirs()
            writeText("""{"status":"COMPLETED","transcript":"privado"}""")
        }
        val pcmPlaintext = ByteArray(64_003) { (it % 239).toByte() }
        val pcm = File(root, "session-device/segment-0000.pcm").apply {
            writeBytes(pcmPlaintext)
        }

        try {
            val store = AndroidKeystoreSessionArtifactStore.create(context)
            store.migrateAll()

            assertTrue(store.isEncrypted(checkpoint))
            assertTrue(store.isEncrypted(pcm))
            assertFalse(checkpoint.readText().contains("transcript"))
            assertEquals(
                """{"status":"COMPLETED","transcript":"privado"}""",
                store.readText(checkpoint),
            )
            assertArrayEquals(pcmPlaintext, store.readBytes(pcm))
            assertEquals(0b110_000_000, Os.stat(checkpoint.path).st_mode and 0b111_111_111)
            assertEquals(0b111_000_000, Os.stat(checkpoint.parentFile!!.path).st_mode and 0b111_111_111)

            val appended = File(root, "session-device/segment-0001.pcm")
            store.openAppend(appended).use { sink ->
                sink.write(byteArrayOf(1, 2, 3))
                sink.write(byteArrayOf(4, 5))
            }
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), store.readBytes(appended))

            val streamingPcm = File(root, "session-device/segment-0002.pcm")
            val audioFrame = ByteArray(3_200) { (it % 197).toByte() }
            val started = System.nanoTime()
            store.openAppend(streamingPcm).use { sink ->
                repeat(100) { sink.write(audioFrame) }
                sink.sync()
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            assertEquals(320_000L, store.plaintextSize(streamingPcm))
            assertTrue("Encrypted streaming took ${elapsedMs}ms", elapsedMs < 10_000L)

            deleteArtifactKey()
            assertThrows(SecurityException::class.java) {
                AndroidKeystoreSessionArtifactStore.create(context)
            }
        } finally {
            root.deleteRecursively()
            deleteArtifactMarker(context)
            deleteArtifactKey()
        }
    }

    private fun deleteArtifactKey() {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry("noteapp.session-artifacts.v1")
        }
    }

    private fun deleteArtifactMarker(context: Context) {
        File(context.filesDir, "security/session-artifacts.v1").delete()
    }
}
