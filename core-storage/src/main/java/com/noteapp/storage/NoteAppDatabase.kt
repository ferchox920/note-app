package com.noteapp.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Room
import androidx.room.Upsert
import com.noteapp.security.DatabasePassphraseProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String? = null,
    val createdAtEpochMs: Long,
    val startedAtEpochMs: Long? = null,
    val endedAtEpochMs: Long? = null,
    val status: String,
    val durationMs: Long,
    val deviceModel: String? = null,
    val appVersion: String? = null,
    val audioPath: String? = null,
    val noteTemplateId: String? = null,
    val errorCode: String? = null,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "transcript_segments",
    primaryKeys = ["sessionId", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class TranscriptSegmentEntity(
    val sessionId: String,
    val sequence: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val stableText: String = text,
    val confidence: Double? = null,
    val speakerLabel: String? = null,
    val isFinal: Boolean = true,
    val sourceModel: String,
)

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val templateId: String?,
    val schemaVersion: Int,
    val contentMarkdown: String,
    val contentJson: String?,
    val generatedAtEpochMs: Long,
    val editedAtEpochMs: Long?,
    val generationModel: String?,
)

@Entity(
    tableName = "processing_jobs",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("state")],
)
data class ProcessingJobEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val jobType: String,
    val state: String,
    val startedAtEpochMs: Long?,
    val endedAtEpochMs: Long?,
    val errorCode: String?,
    val attempts: Int,
)

@Entity(
    tableName = "session_metrics",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index(value = ["sessionId", "observedAtEpochMs"])],
)
data class SessionMetricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val observedAtEpochMs: Long,
    val metricName: String,
    val value: Double,
    val unit: String?,
    val phase: String?,
    val runtime: String?,
    val delegate: String?,
)

@Dao
interface SessionDao {
    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE status IN (:statuses) ORDER BY updatedAtEpochMs DESC")
    suspend fun findByStatuses(statuses: List<String>): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun findById(id: String): SessionEntity?

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface TranscriptSegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(segments: List<TranscriptSegmentEntity>)

    @Query("SELECT * FROM transcript_segments WHERE sessionId = :sessionId ORDER BY sequence")
    suspend fun findBySession(sessionId: String): List<TranscriptSegmentEntity>

    @Query("DELETE FROM transcript_segments WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}

@Dao
interface NoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE sessionId = :sessionId ORDER BY generatedAtEpochMs DESC")
    suspend fun findBySession(sessionId: String): List<NoteEntity>

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ProcessingJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: ProcessingJobEntity)

    @Query("SELECT * FROM processing_jobs WHERE id = :id")
    suspend fun findById(id: String): ProcessingJobEntity?

    @Query(
        """
        SELECT * FROM processing_jobs
        WHERE sessionId = :sessionId
        ORDER BY startedAtEpochMs DESC
        """,
    )
    suspend fun findBySession(sessionId: String): List<ProcessingJobEntity>

    @Query(
        """
        UPDATE processing_jobs
        SET state = :state, endedAtEpochMs = :endedAtEpochMs, errorCode = :errorCode
        WHERE id = :id
        """,
    )
    suspend fun finish(
        id: String,
        state: String,
        endedAtEpochMs: Long,
        errorCode: String?,
    ): Int

    @Query(
        """
        UPDATE processing_jobs
        SET state = :failedState, endedAtEpochMs = :endedAtEpochMs, errorCode = :errorCode
        WHERE state = :runningState
        """,
    )
    suspend fun failAllRunning(
        runningState: String,
        failedState: String,
        endedAtEpochMs: Long,
        errorCode: String,
    ): Int
}

@Dao
interface SessionMetricDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(metrics: List<SessionMetricEntity>)

    @Query(
        """
        SELECT * FROM session_metrics
        WHERE sessionId = :sessionId
        ORDER BY observedAtEpochMs, id
        """,
    )
    suspend fun findBySession(sessionId: String): List<SessionMetricEntity>

    @Query("DELETE FROM session_metrics WHERE sessionId = :sessionId AND phase = :phase")
    suspend fun deleteBySessionAndPhase(sessionId: String, phase: String)
}

@Database(
    entities = [
        SessionEntity::class,
        TranscriptSegmentEntity::class,
        NoteEntity::class,
        ProcessingJobEntity::class,
        SessionMetricEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NoteAppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun transcriptSegmentDao(): TranscriptSegmentDao
    abstract fun noteDao(): NoteDao
    abstract fun processingJobDao(): ProcessingJobDao
    abstract fun sessionMetricDao(): SessionMetricDao

    companion object {
        const val DATABASE_NAME = "note-app.db"

        fun create(
            context: Context,
            passphraseProvider: DatabasePassphraseProvider,
        ): NoteAppDatabase {
            System.loadLibrary("sqlcipher")
            val applicationContext = context.applicationContext
            val databaseFile = applicationContext.getDatabasePath(DATABASE_NAME)
            if (
                databaseFile.exists() &&
                !SqlCipherDatabaseMigration.isPlaintext(databaseFile) &&
                !passphraseProvider.hasStoredPassphrase()
            ) {
                throw SecurityException("ENCRYPTED_DATABASE_KEY_MATERIAL_MISSING")
            }
            val passphrase = passphraseProvider.getOrCreatePassphrase()
            return try {
                SqlCipherDatabaseMigration.prepare(databaseFile, passphrase)
                Room.databaseBuilder(
                    applicationContext,
                    NoteAppDatabase::class.java,
                    DATABASE_NAME,
                )
                    .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
                    .build()
            } finally {
                passphrase.fill(0)
            }
        }
    }
}
