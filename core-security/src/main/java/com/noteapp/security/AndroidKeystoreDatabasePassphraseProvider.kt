package com.noteapp.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreDatabasePassphraseProvider(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val secureRandom: SecureRandom = SecureRandom(),
) : DatabasePassphraseProvider {
    private val keyFile = File(
        context.applicationContext.filesDir,
        "security/$KEY_FILE_NAME",
    )

    override fun hasStoredPassphrase(): Boolean = keyFile.isFile

    @Synchronized
    override fun getOrCreatePassphrase(): ByteArray {
        if (keyFile.exists()) {
            val wrappingKey = existingWrappingKey()
            return decrypt(keyFile.readBytes(), wrappingKey)
        }

        val wrappingKey = existingWrappingKeyOrNull() ?: generateWrappingKey()
        val entropy = ByteArray(PASSPHRASE_ENTROPY_BYTES).also(secureRandom::nextBytes)
        val passphrase = entropy.toHexBytes()
        entropy.fill(0)
        val encoded = encrypt(passphrase, wrappingKey)
        writeAtomically(encoded)
        return passphrase
    }

    private fun encrypt(
        passphrase: ByteArray,
        wrappingKey: SecretKey,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        cipher.updateAAD(KEY_AAD)
        return WrappedPassphraseCodec.encode(
            iv = cipher.iv,
            encryptedPassphrase = cipher.doFinal(passphrase),
        )
    }

    private fun decrypt(
        encoded: ByteArray,
        wrappingKey: SecretKey,
    ): ByteArray {
        val wrapped = WrappedPassphraseCodec.decode(encoded)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                wrappingKey,
                GCMParameterSpec(GCM_TAG_BITS, wrapped.iv),
            )
            cipher.updateAAD(KEY_AAD)
            cipher.doFinal(wrapped.encryptedPassphrase).also {
                check(it.size == PASSPHRASE_BYTES) { "INVALID_DATABASE_PASSPHRASE" }
                check(it.all(::isLowercaseHex)) { "INVALID_DATABASE_PASSPHRASE" }
            }
        } catch (failure: AEADBadTagException) {
            throw SecurityException("DATABASE_PASSPHRASE_AUTHENTICATION_FAILED", failure)
        }
    }

    private fun existingWrappingKey(): SecretKey =
        existingWrappingKeyOrNull()
            ?: throw SecurityException("DATABASE_WRAPPING_KEY_MISSING")

    private fun existingWrappingKeyOrNull(): SecretKey? {
        val keyStore = androidKeyStore()
        if (!keyStore.containsAlias(keyAlias)) return null
        return keyStore.getKey(keyAlias, null) as? SecretKey
            ?: throw SecurityException("DATABASE_WRAPPING_KEY_INVALID")
    }

    private fun generateWrappingKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(WRAPPING_KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun writeAtomically(encoded: ByteArray) {
        val directory = requireNotNull(keyFile.parentFile)
        check(directory.exists() || directory.mkdirs()) {
            "DATABASE_KEY_DIRECTORY_CREATE_FAILED"
        }
        val temporary = File(directory, "$KEY_FILE_NAME.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(encoded)
            output.fd.sync()
        }
        temporary.setReadable(false, false)
        temporary.setWritable(false, false)
        temporary.setReadable(true, true)
        temporary.setWritable(true, true)
        try {
            Files.move(
                temporary.toPath(),
                keyFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), keyFile.toPath())
        }
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "noteapp.database.wrapping.v1"
        private const val KEY_FILE_NAME = "database-passphrase.v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val WRAPPING_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val PASSPHRASE_ENTROPY_BYTES = 32
        private const val PASSPHRASE_BYTES = 64
        private val KEY_AAD =
            "note-app/database-passphrase/v1".toByteArray(StandardCharsets.UTF_8)

        private fun ByteArray.toHexBytes(): ByteArray =
            ByteArray(size * 2).also { encoded ->
                forEachIndexed { index, byte ->
                    val unsigned = byte.toInt() and 0xff
                    encoded[index * 2] = HEX_ALPHABET[unsigned ushr 4]
                    encoded[index * 2 + 1] = HEX_ALPHABET[unsigned and 0x0f]
                }
            }

        private fun isLowercaseHex(byte: Byte): Boolean =
            byte in '0'.code.toByte()..'9'.code.toByte() ||
                byte in 'a'.code.toByte()..'f'.code.toByte()

        private val HEX_ALPHABET =
            "0123456789abcdef".toByteArray(StandardCharsets.US_ASCII)
    }
}

internal data class WrappedPassphrase(
    val iv: ByteArray,
    val encryptedPassphrase: ByteArray,
)

internal object WrappedPassphraseCodec {
    private val MAGIC = "NADBKEY1".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION = 1
    private const val GCM_IV_BYTES = 12
    private const val ENCRYPTED_PASSPHRASE_BYTES = 80
    private const val MAX_FILE_BYTES = 256

    fun encode(
        iv: ByteArray,
        encryptedPassphrase: ByteArray,
    ): ByteArray {
        require(iv.size == GCM_IV_BYTES) { "INVALID_DATABASE_KEY_IV" }
        require(encryptedPassphrase.size == ENCRYPTED_PASSPHRASE_BYTES) {
            "INVALID_ENCRYPTED_DATABASE_PASSPHRASE"
        }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(iv.size)
                output.writeInt(encryptedPassphrase.size)
                output.write(iv)
                output.write(encryptedPassphrase)
            }
            bytes.toByteArray()
        }
    }

    fun decode(encoded: ByteArray): WrappedPassphrase {
        require(encoded.size <= MAX_FILE_BYTES) { "DATABASE_KEY_FILE_TOO_LARGE" }
        return DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "INVALID_DATABASE_KEY_MAGIC" }
            require(input.readInt() == VERSION) { "UNSUPPORTED_DATABASE_KEY_VERSION" }
            val ivSize = input.readInt()
            val encryptedSize = input.readInt()
            require(ivSize == GCM_IV_BYTES) { "INVALID_DATABASE_KEY_IV" }
            require(encryptedSize == ENCRYPTED_PASSPHRASE_BYTES) {
                "INVALID_ENCRYPTED_DATABASE_PASSPHRASE"
            }
            val iv = ByteArray(ivSize).also(input::readFully)
            val encrypted = ByteArray(encryptedSize).also(input::readFully)
            require(input.read() == -1) { "DATABASE_KEY_FILE_TRAILING_DATA" }
            WrappedPassphrase(iv, encrypted)
        }
    }
}
