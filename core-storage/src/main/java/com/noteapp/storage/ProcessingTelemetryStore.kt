package com.noteapp.storage

import androidx.room.withTransaction
import java.util.UUID

data class PersistedProcessingMetric(
    val name: String,
    val value: Double,
    val unit: String? = null,
    val phase: String? = null,
    val runtime: String? = null,
    val delegate: String? = null,
)

interface ProcessingTelemetryStore {
    suspend fun recoverInterrupted(): Int

    suspend fun start(sessionId: String, jobType: String): String

    suspend fun complete(jobId: String, metrics: List<PersistedProcessingMetric>)

    suspend fun fail(jobId: String, errorCode: String)
}

class RoomProcessingTelemetryStore(
    private val database: NoteAppDatabase,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ProcessingTelemetryStore {
    override suspend fun recoverInterrupted(): Int =
        database.processingJobDao().failAllRunning(
            runningState = STATE_RUNNING,
            failedState = STATE_FAILED,
            endedAtEpochMs = nowEpochMs(),
            errorCode = ERROR_PROCESS_INTERRUPTED,
        )

    override suspend fun start(sessionId: String, jobType: String): String {
        require(jobType.matches(SAFE_CODE)) { "INVALID_JOB_TYPE" }
        checkNotNull(database.sessionDao().findById(sessionId)) {
            "SESSION_NOT_INDEXED"
        }
        val jobId = newId()
        database.processingJobDao().upsert(
            ProcessingJobEntity(
                id = jobId,
                sessionId = sessionId,
                jobType = jobType,
                state = STATE_RUNNING,
                startedAtEpochMs = nowEpochMs(),
                endedAtEpochMs = null,
                errorCode = null,
                attempts = 1,
            ),
        )
        return jobId
    }

    override suspend fun complete(
        jobId: String,
        metrics: List<PersistedProcessingMetric>,
    ) {
        val endedAt = nowEpochMs()
        database.withTransaction {
            val job = requireRunningJob(jobId)
            if (metrics.isNotEmpty()) {
                database.sessionMetricDao().insertAll(
                    metrics.map { metric ->
                        require(metric.name.matches(SAFE_CODE)) { "INVALID_METRIC_NAME" }
                        require(metric.value.isFinite()) { "INVALID_METRIC_VALUE" }
                        SessionMetricEntity(
                            sessionId = job.sessionId,
                            observedAtEpochMs = endedAt,
                            metricName = metric.name,
                            value = metric.value,
                            unit = metric.unit,
                            phase = metric.phase,
                            runtime = metric.runtime,
                            delegate = metric.delegate,
                        )
                    },
                )
            }
            check(
                database.processingJobDao().finish(
                    id = jobId,
                    state = STATE_COMPLETED,
                    endedAtEpochMs = endedAt,
                    errorCode = null,
                ) == 1,
            ) {
                "JOB_UPDATE_FAILED"
            }
        }
    }

    override suspend fun fail(jobId: String, errorCode: String) {
        require(errorCode.matches(SAFE_CODE)) { "INVALID_ERROR_CODE" }
        database.withTransaction {
            requireRunningJob(jobId)
            check(
                database.processingJobDao().finish(
                    id = jobId,
                    state = STATE_FAILED,
                    endedAtEpochMs = nowEpochMs(),
                    errorCode = errorCode,
                ) == 1,
            ) {
                "JOB_UPDATE_FAILED"
            }
        }
    }

    private suspend fun requireRunningJob(jobId: String): ProcessingJobEntity {
        val job = checkNotNull(database.processingJobDao().findById(jobId)) {
            "JOB_NOT_FOUND"
        }
        check(job.state == STATE_RUNNING) { "JOB_ALREADY_FINISHED" }
        return job
    }

    companion object {
        const val STATE_RUNNING = "RUNNING"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_FAILED = "FAILED"
        const val ERROR_PROCESS_INTERRUPTED = "PROCESS_INTERRUPTED"
        private val SAFE_CODE = Regex("[A-Z][A-Z0-9_]{2,79}")
    }
}
