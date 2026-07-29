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
}

@Dao
interface ProcessingJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: ProcessingJobEntity)
}

@Dao
interface SessionMetricDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(metrics: List<SessionMetricEntity>)
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
        fun create(context: Context): NoteAppDatabase = Room.databaseBuilder(
            context.applicationContext,
            NoteAppDatabase::class.java,
            "note-app.db",
        ).build()
    }
}
