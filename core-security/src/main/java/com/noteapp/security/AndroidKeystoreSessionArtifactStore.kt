package com.noteapp.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object AndroidKeystoreSessionArtifactStore {
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "noteapp.session-artifacts.v1"
    private const val KEY_BITS = 256
    private const val KEY_MARKER_NAME = "session-artifacts.v1"

    @Synchronized
    fun create(context: Context): SessionArtifactStore {
        val applicationContext = context.applicationContext
        val root = File(applicationContext.filesDir, "recordings")
        val marker = File(applicationContext.filesDir, "security/$KEY_MARKER_NAME")
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey == null && (marker.exists() || containsEncryptedArtifact(root))) {
            throw SecurityException("ARTIFACT_ENCRYPTION_KEY_MISSING")
        }
        val key = existingKey ?: generateKey()
        if (!marker.isFile) writeKeyMarker(marker)
        return EncryptedSessionArtifactStore(root, key)
    }

    private fun containsEncryptedArtifact(root: File): Boolean =
        root.takeIf(File::isDirectory)
            ?.walkTopDown()
            ?.filter(File::isFile)
            ?.any { file ->
                runCatching {
                    file.inputStream().use { input ->
                        val magic = ByteArray(8)
                        input.read(magic) == magic.size &&
                            magic.contentEquals("NAARTF01".toByteArray(Charsets.US_ASCII))
                    }
                }.getOrDefault(false)
            } == true

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun writeKeyMarker(marker: File) {
        val directory = requireNotNull(marker.parentFile)
        check(directory.exists() || directory.mkdirs()) {
            "ARTIFACT_KEY_MARKER_DIRECTORY_CREATE_FAILED"
        }
        FileOutputStream(marker).use { output ->
            output.write("NAARTKEY1".toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
        marker.setReadable(false, false)
        marker.setWritable(false, false)
        marker.setReadable(true, true)
        marker.setWritable(true, true)
    }
}
