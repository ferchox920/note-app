package com.noteapp.storage

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noteapp.security.DatabasePassphraseProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedNoteAppDatabaseTest {
    @Test
    fun plaintextRoomDatabaseMigratesWithoutLosingRows() {
        runBlocking {
            val context = isolatedContext()
            val databaseFile = context.getDatabasePath(NoteAppDatabase.DATABASE_NAME)
            val plaintext = createPlaintextDatabase(context)
            plaintext.sessionDao().upsert(session("preserved-session"))
            plaintext.close()
            assertTrue(SqlCipherDatabaseMigration.isPlaintext(databaseFile))

            val encrypted = NoteAppDatabase.create(
                context,
                StaticPassphraseProvider(TEST_PASSPHRASE),
            )
            assertEquals(
                "preserved-session",
                encrypted.sessionDao().findById("preserved-session")?.id,
            )
            encrypted.close()

            assertFalse(SqlCipherDatabaseMigration.isPlaintext(databaseFile))
            assertFalse(File(databaseFile.path + ".plaintext.backup").exists())
            assertFalse(File(databaseFile.path + ".encrypted.tmp").exists())
            assertThrows(SQLiteException::class.java) {
                FrameworkSQLiteDatabase.openDatabase(
                    databaseFile.path,
                    null,
                    FrameworkSQLiteDatabase.OPEN_READONLY,
                ).use {
                    it.rawQuery("SELECT * FROM sessions", null).use { cursor -> cursor.count }
                }
            }
        }
    }

    @Test
    fun interruptedSwapWithOnlyPlaintextBackupRecoversAndMigrates() = runBlocking {
        val context = isolatedContext()
        val databaseFile = context.getDatabasePath(NoteAppDatabase.DATABASE_NAME)
        val plaintext = createPlaintextDatabase(context)
        plaintext.sessionDao().upsert(session("recovered-session"))
        plaintext.close()
        val backup = File(databaseFile.path + ".plaintext.backup")
        assertTrue(databaseFile.renameTo(backup))

        val encrypted = NoteAppDatabase.create(
            context,
            StaticPassphraseProvider(TEST_PASSPHRASE),
        )
        assertEquals(
            "recovered-session",
            encrypted.sessionDao().findById("recovered-session")?.id,
        )
        encrypted.close()

        assertTrue(databaseFile.isFile)
        assertFalse(SqlCipherDatabaseMigration.isPlaintext(databaseFile))
        assertFalse(backup.exists())
    }

    @Test
    fun encryptedDatabaseRejectsWrongOrMissingKeyMaterial() {
        runBlocking {
            val context = isolatedContext()
            val database = NoteAppDatabase.create(
                context,
                StaticPassphraseProvider(TEST_PASSPHRASE),
            )
            database.sessionDao().upsert(session("locked-session"))
            database.close()

            assertThrows(RuntimeException::class.java) {
                NoteAppDatabase.create(
                context,
                StaticPassphraseProvider("09".repeat(32).encodeToByteArray()),
                )
            }
            assertThrows(SecurityException::class.java) {
                NoteAppDatabase.create(
                    context,
                    StaticPassphraseProvider(TEST_PASSPHRASE, stored = false),
                )
            }
        }
    }

    private fun createPlaintextDatabase(context: Context): NoteAppDatabase =
        Room.databaseBuilder(
            context,
            NoteAppDatabase::class.java,
            NoteAppDatabase.DATABASE_NAME,
        ).build()

    private fun isolatedContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "encrypted-room-${UUID.randomUUID()}")
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = File(root, "files")

            override fun getDatabasePath(name: String): File =
                File(root, "databases/$name").also { it.parentFile?.mkdirs() }
        }
    }

    private fun session(id: String) = SessionEntity(
        id = id,
        createdAtEpochMs = 1,
        status = "COMPLETED",
        durationMs = 5_000,
        updatedAtEpochMs = 2,
    )

    private class StaticPassphraseProvider(
        private val passphrase: ByteArray,
        private val stored: Boolean = true,
    ) : DatabasePassphraseProvider {
        override fun hasStoredPassphrase(): Boolean = stored

        override fun getOrCreatePassphrase(): ByteArray = passphrase.copyOf()
    }

    private companion object {
        val TEST_PASSPHRASE = "01".repeat(32).encodeToByteArray()
    }
}
