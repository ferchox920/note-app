package com.noteapp.storage

import androidx.room.withTransaction
import com.noteapp.domain.RecordingSession
import com.noteapp.domain.SessionStatus
import com.noteapp.security.SessionArtifactStore
import java.io.File

/**
 * Keeps the crash-safe artifact files authoritative while building a queryable Room index.
 *
 * Re-indexing is intentionally idempotent: each refresh replaces a session row and the
 * corresponding finalized transcript segments in one transaction.
 */
class RoomSessionCheckpointStore(
    private val recordingsDirectory: File,
    private val database: NoteAppDatabase,
    private val artifactStore: SessionArtifactStore,
) : SessionCheckpointStore {
    override suspend fun findRecoverable(): List<RecordingSession> {
        refreshIndex()
        return database.sessionDao()
            .findByStatuses(RECOVERABLE_STATUSES.map(SessionStatus::name))
            .map { entity ->
                entity.toDomain().copy(status = SessionStatus.RECOVERING)
            }
    }

    override suspend fun findCompleted(): List<RecordingSession> {
        refreshIndex()
        return database.sessionDao()
            .findByStatuses(listOf(SessionStatus.COMPLETED.name))
            .map { it.toDomain() }
    }

    suspend fun refreshIndex() {
        val snapshots = FileSessionArtifactReader(recordingsDirectory, artifactStore).readAll()
        snapshots.forEach { snapshot ->
            database.withTransaction {
                database.sessionDao().upsert(snapshot.toEntity())
                database.transcriptSegmentDao().deleteBySession(snapshot.session.id)
                if (snapshot.transcriptSegments.isNotEmpty()) {
                    database.transcriptSegmentDao().upsertAll(
                        snapshot.transcriptSegments.mapIndexed { sequence, segment ->
                            TranscriptSegmentEntity(
                                sessionId = snapshot.session.id,
                                sequence = sequence,
                                startMs = segment.startMs,
                                endMs = segment.endMs,
                                text = segment.text,
                                sourceModel = snapshot.transcriptModelId ?: "unknown",
                            )
                        },
                    )
                }
                database.sessionMetricDao().deleteBySessionAndPhase(
                    snapshot.session.id,
                    INCREMENTAL_SUMMARY_PHASE,
                )
                if (snapshot.transcriptMetrics.isNotEmpty()) {
                    database.sessionMetricDao().insertAll(
                        snapshot.transcriptMetrics.map { metric ->
                            SessionMetricEntity(
                                sessionId = snapshot.session.id,
                                observedAtEpochMs = snapshot.checkpointUpdatedAtEpochMs,
                                metricName = metric.name,
                                value = metric.value,
                                unit = metric.unit,
                                phase = INCREMENTAL_SUMMARY_PHASE,
                                runtime = metric.runtime,
                                delegate = metric.delegate,
                            )
                        },
                    )
                }
            }
        }
    }

    private fun SessionArtifactSnapshot.toEntity(): SessionEntity = SessionEntity(
        id = session.id,
        createdAtEpochMs = directory.lastModified(),
        endedAtEpochMs = if (session.status == SessionStatus.COMPLETED) checkpointUpdatedAtEpochMs else null,
        status = session.status.name,
        durationMs = session.durationMs,
        audioPath = directory.absolutePath,
        errorCode = session.errorCode,
        updatedAtEpochMs = checkpointUpdatedAtEpochMs,
    )

    private fun SessionEntity.toDomain(): RecordingSession = RecordingSession(
        id = id,
        status = enumValueOf(status),
        durationMs = durationMs,
        errorCode = errorCode,
    )

    private companion object {
        const val INCREMENTAL_SUMMARY_PHASE = "incremental_summary"
        val RECOVERABLE_STATUSES = listOf(
            SessionStatus.RECORDING,
            SessionStatus.PAUSED,
            SessionStatus.RECOVERING,
        )
    }
}
