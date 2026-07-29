package com.noteapp.security

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreDatabasePassphraseProviderTest {
    @Test
    fun passphraseIsWrappedAuthenticatedAndStable() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val isolatedFiles = File(baseContext.cacheDir, "security-${UUID.randomUUID()}")
        val isolatedContext = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = isolatedFiles
        }
        val alias = "noteapp.test.${UUID.randomUUID()}"

        try {
            val provider = AndroidKeystoreDatabasePassphraseProvider(
                context = isolatedContext,
                keyAlias = alias,
            )
            assertFalse(provider.hasStoredPassphrase())

            val first = provider.getOrCreatePassphrase()
            val second = provider.getOrCreatePassphrase()
            val wrappedFile = File(
                isolatedFiles,
                "security/database-passphrase.v1",
            )

            assertEquals(64, first.size)
            assertArrayEquals(first, second)
            assertFalse(wrappedFile.readBytes().containsSubsequence(first))

            val tampered = wrappedFile.readBytes().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            wrappedFile.writeBytes(tampered)
            assertThrows(SecurityException::class.java) {
                provider.getOrCreatePassphrase()
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean =
        indices.any { start ->
            start + candidate.size <= size &&
                candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
        }
}
