package com.noteapp.storage

import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object SqlCipherDatabaseMigration {
    private val SQLITE_HEADER = "SQLite format 3\u0000"
        .toByteArray(StandardCharsets.US_ASCII)
    private const val ENCRYPTED_TEMP_SUFFIX = ".encrypted.tmp"
    private const val PLAINTEXT_BACKUP_SUFFIX = ".plaintext.backup"

    fun prepare(
        databaseFile: File,
        passphrase: ByteArray,
    ) {
        require(passphrase.isValidPassphrase()) { "DATABASE_PASSPHRASE_INVALID" }
        val encryptedTemporary = File(databaseFile.path + ENCRYPTED_TEMP_SUFFIX)
        val plaintextBackup = File(databaseFile.path + PLAINTEXT_BACKUP_SUFFIX)

        recoverInterruptedSwap(
            databaseFile = databaseFile,
            encryptedTemporary = encryptedTemporary,
            plaintextBackup = plaintextBackup,
            passphrase = passphrase,
        )
        if (!databaseFile.exists()) return

        if (isPlaintext(databaseFile)) {
            migratePlaintext(
                databaseFile = databaseFile,
                encryptedTemporary = encryptedTemporary,
                plaintextBackup = plaintextBackup,
                passphrase = passphrase,
            )
        } else {
            verifyEncrypted(databaseFile, passphrase)
            removePlaintextBackup(plaintextBackup)
        }
    }

    fun isPlaintext(databaseFile: File): Boolean {
        if (!databaseFile.isFile || databaseFile.length() < SQLITE_HEADER.size) {
            return false
        }
        val header = ByteArray(SQLITE_HEADER.size)
        FileInputStream(databaseFile).use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) return false
                offset += read
            }
        }
        return header.contentEquals(SQLITE_HEADER)
    }

    private fun recoverInterruptedSwap(
        databaseFile: File,
        encryptedTemporary: File,
        plaintextBackup: File,
        passphrase: ByteArray,
    ) {
        if (!plaintextBackup.exists()) {
            if (encryptedTemporary.exists() && databaseFile.exists()) {
                deleteDatabaseFiles(encryptedTemporary)
            }
            return
        }

        if (databaseFile.exists()) {
            check(!isPlaintext(databaseFile)) {
                "AMBIGUOUS_DATABASE_MIGRATION_STATE"
            }
            verifyEncrypted(databaseFile, passphrase)
            deleteDatabaseFiles(encryptedTemporary)
            removePlaintextBackup(plaintextBackup)
            return
        }

        if (encryptedTemporary.exists()) {
            verifyEncrypted(encryptedTemporary, passphrase)
            moveAtomically(encryptedTemporary, databaseFile)
            verifyEncrypted(databaseFile, passphrase)
            removePlaintextBackup(plaintextBackup)
            return
        }

        moveAtomically(plaintextBackup, databaseFile)
    }

    private fun migratePlaintext(
        databaseFile: File,
        encryptedTemporary: File,
        plaintextBackup: File,
        passphrase: ByteArray,
    ) {
        deleteDatabaseFiles(encryptedTemporary)
        check(!plaintextBackup.exists()) { "PLAINTEXT_BACKUP_ALREADY_EXISTS" }
        check(databaseFile.parentFile?.let { it.exists() || it.mkdirs() } == true) {
            "DATABASE_DIRECTORY_CREATE_FAILED"
        }

        val sourceVersion = SQLiteDatabase.openDatabase(
            databaseFile.path,
            ByteArray(0),
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
        ).use { plaintext ->
            checkpointWal(plaintext)
            plaintext.version
        }

        SQLiteDatabase.openOrCreateDatabase(
            encryptedTemporary,
            passphrase,
            null,
            null,
        ).use { encrypted ->
            encrypted.execSQL(
                "ATTACH DATABASE ${databaseFile.path.sqlStringLiteral()} " +
                    "AS plaintext KEY ''",
            )
            try {
                encrypted.query("SELECT sqlcipher_export('main', 'plaintext')").use { cursor ->
                    check(cursor.moveToFirst()) { "SQLCIPHER_EXPORT_NO_RESULT" }
                }
                encrypted.version = sourceVersion
                checkIntegrity(encrypted)
            } finally {
                encrypted.execSQL("DETACH DATABASE plaintext")
            }
        }

        deleteSidecars(databaseFile)
        restrictToOwner(encryptedTemporary)
        verifyEncrypted(encryptedTemporary, passphrase, sourceVersion)

        moveAtomically(databaseFile, plaintextBackup)
        try {
            moveAtomically(encryptedTemporary, databaseFile)
            verifyEncrypted(databaseFile, passphrase, sourceVersion)
            removePlaintextBackup(plaintextBackup)
        } catch (failure: Throwable) {
            if (!databaseFile.exists() && plaintextBackup.exists()) {
                moveAtomically(plaintextBackup, databaseFile)
            }
            throw failure
        } finally {
            deleteDatabaseFiles(encryptedTemporary)
        }
    }

    private fun checkpointWal(database: SQLiteDatabase) {
        plaintextCheckpoint(database)
        database.query("PRAGMA journal_mode = DELETE").use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0).equals("delete", true)) {
                "DATABASE_JOURNAL_MODE_CHANGE_FAILED"
            }
        }
    }

    private fun plaintextCheckpoint(database: SQLiteDatabase) {
        database.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
            check(cursor.moveToFirst()) { "DATABASE_WAL_CHECKPOINT_NO_RESULT" }
            check(cursor.getInt(0) == 0) { "DATABASE_WAL_CHECKPOINT_BUSY" }
        }
    }

    private fun verifyEncrypted(
        databaseFile: File,
        passphrase: ByteArray,
        expectedVersion: Int? = null,
    ) {
        check(databaseFile.isFile) { "ENCRYPTED_DATABASE_MISSING" }
        check(!isPlaintext(databaseFile)) { "DATABASE_REMAINS_PLAINTEXT" }
        SQLiteDatabase.openDatabase(
            databaseFile.path,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
        ).use { encrypted ->
            checkIntegrity(encrypted)
            expectedVersion?.let {
                check(encrypted.version == it) { "DATABASE_VERSION_MISMATCH" }
            }
        }
    }

    private fun checkIntegrity(
        database: SQLiteDatabase,
        schema: String = "main",
    ) {
        database.query("PRAGMA $schema.integrity_check").use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0).equals("ok", true)) {
                "DATABASE_INTEGRITY_CHECK_FAILED"
            }
        }
    }

    private fun removePlaintextBackup(plaintextBackup: File) {
        if (!plaintextBackup.exists()) return
        check(plaintextBackup.delete()) { "PLAINTEXT_BACKUP_DELETE_FAILED" }
        deleteSidecars(plaintextBackup)
    }

    private fun deleteDatabaseFiles(databaseFile: File) {
        if (databaseFile.exists()) {
            check(databaseFile.delete()) { "DATABASE_TEMP_DELETE_FAILED" }
        }
        deleteSidecars(databaseFile)
    }

    private fun deleteSidecars(databaseFile: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val sidecar = File(databaseFile.path + suffix)
            if (sidecar.exists()) {
                check(sidecar.delete()) { "DATABASE_SIDECAR_DELETE_FAILED" }
            }
        }
    }

    private fun moveAtomically(
        source: File,
        target: File,
    ) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        check(file.setReadable(true, true)) { "DATABASE_PERMISSION_UPDATE_FAILED" }
        check(file.setWritable(true, true)) { "DATABASE_PERMISSION_UPDATE_FAILED" }
    }

    private fun String.sqlStringLiteral(): String = "'${replace("'", "''")}'"

    private fun ByteArray.isValidPassphrase(): Boolean =
        size == 64 && all { byte ->
            byte in '0'.code.toByte()..'9'.code.toByte() ||
                byte in 'a'.code.toByte()..'f'.code.toByte()
        }

}
