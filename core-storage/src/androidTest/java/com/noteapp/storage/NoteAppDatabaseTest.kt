package com.noteapp.storage

import com.noteapp.security.PlaintextSessionArtifactStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteAppDatabaseTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var database: NoteAppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NoteAppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun segmentsAreOrderedReplacedAndDeletedWithTheirSession() = runBlocking {
        database.sessionDao().upsert(session("session-1"))
        database.transcriptSegmentDao().upsertAll(
            listOf(
                segment(sequence = 1, text = "segunda"),
                segment(sequence = 0, text = "primera"),
            ),
        )
        database.noteDao().upsert(
            NoteEntity(
                id = "note-1",
                sessionId = "session-1",
                templateId = null,
                schemaVersion = 1,
                contentMarkdown = "nota",
                contentJson = null,
                generatedAtEpochMs = 1,
                editedAtEpochMs = null,
                generationModel = null,
            ),
        )
        database.processingJobDao().upsert(
            ProcessingJobEntity(
                id = "job-1",
                sessionId = "session-1",
                jobType = "WHISPER_ASR_POST_PROCESS",
                state = "COMPLETED",
                startedAtEpochMs = 1,
                endedAtEpochMs = 2,
                errorCode = null,
                attempts = 1,
            ),
        )
        database.sessionMetricDao().insertAll(
            listOf(
                SessionMetricEntity(
                    sessionId = "session-1",
                    observedAtEpochMs = 2,
                    metricName = "REAL_TIME_FACTOR",
                    value = 0.1,
                    unit = "ratio",
                    phase = "post_process",
                    runtime = "test",
                    delegate = "cpu",
                ),
            ),
        )

        assertEquals(
            listOf("primera", "segunda"),
            database.transcriptSegmentDao().findBySession("session-1").map { it.text },
        )

        database.transcriptSegmentDao().upsertAll(
            listOf(segment(sequence = 1, text = "segunda corregida")),
        )
        assertEquals(
            "segunda corregida",
            database.transcriptSegmentDao().findBySession("session-1")[1].text,
        )

        database.sessionDao().deleteById("session-1")
        assertNull(database.sessionDao().findById("session-1"))
        assertEquals(
            emptyList<TranscriptSegmentEntity>(),
            database.transcriptSegmentDao().findBySession("session-1"),
        )
        assertEquals(emptyList<NoteEntity>(), database.noteDao().findBySession("session-1"))
        assertEquals(
            emptyList<ProcessingJobEntity>(),
            database.processingJobDao().findBySession("session-1"),
        )
        assertEquals(
            emptyList<SessionMetricEntity>(),
            database.sessionMetricDao().findBySession("session-1"),
        )
    }

    @Test
    fun upsertingSessionDoesNotDeleteRelatedRows() = runBlocking {
        database.sessionDao().upsert(session("session-1"))
        database.transcriptSegmentDao().upsertAll(listOf(segment(0, "texto")))

        database.sessionDao().upsert(session("session-1").copy(durationMs = 10_000))

        assertEquals(10_000L, database.sessionDao().findById("session-1")?.durationMs)
        assertEquals(1, database.transcriptSegmentDao().findBySession("session-1").size)
    }

    @Test
    fun artifactIndexRefreshIsIdempotentAndReplacesFinalSegments() = runBlocking {
        val directory = temporaryFolder.newFolder("artifact-session")
        directory.resolve("checkpoint.json").writeText(
            """{"schemaVersion":1,"sessionId":"artifact-session","status":"COMPLETED","durationMs":5000,"errorCode":null}""",
        )
        directory.resolve("incremental-transcript.json").writeText(
            """{"schemaVersion":5,"modelId":"sherpa-es","segments":[{"startMs":0,"endMs":5000,"text":"original"}]}""",
        )
        val store = RoomSessionCheckpointStore(
            temporaryFolder.root,
            database,
            PlaintextSessionArtifactStore(temporaryFolder.root),
        )

        assertEquals("artifact-session", store.findCompleted().single().id)
        store.refreshIndex()
        assertEquals(
            listOf("original"),
            database.transcriptSegmentDao().findBySession("artifact-session").map { it.text },
        )

        directory.resolve("incremental-transcript.json").writeText(
            """{"schemaVersion":5,"modelId":"sherpa-es","partialCount":9,"lastRealTimeFactor":0.09,"segments":[{"startMs":0,"endMs":2000,"text":"uno"},{"startMs":2000,"endMs":5000,"text":"dos"}]}""",
        )
        store.refreshIndex()
        store.refreshIndex()

        assertEquals(
            listOf("uno", "dos"),
            database.transcriptSegmentDao().findBySession("artifact-session").map { it.text },
        )
        assertEquals(
            listOf("LAST_REAL_TIME_FACTOR", "PARTIAL_COUNT"),
            database.sessionMetricDao()
                .findBySession("artifact-session")
                .map { it.metricName },
        )
    }

    @Test
    fun processingTelemetryCompletesJobAndMetricsAtomically() = runBlocking {
        database.sessionDao().upsert(session("session-1"))
        var now = 100L
        val store = RoomProcessingTelemetryStore(
            database = database,
            nowEpochMs = { now },
            newId = { "job-1" },
        )

        val jobId = store.start("session-1", "SHERPA_STREAMING_REPLAY")
        now = 250L
        store.complete(
            jobId,
            listOf(
                PersistedProcessingMetric(
                    name = "REAL_TIME_FACTOR",
                    value = 0.09,
                    unit = "ratio",
                    phase = "post_process",
                    runtime = "sherpa-onnx",
                    delegate = "cpu",
                ),
                PersistedProcessingMetric(
                    name = "PEAK_PSS_KB",
                    value = 42_000.0,
                    unit = "KiB",
                ),
            ),
        )

        val job = database.processingJobDao().findById(jobId)
        assertEquals(RoomProcessingTelemetryStore.STATE_COMPLETED, job?.state)
        assertEquals(100L, job?.startedAtEpochMs)
        assertEquals(250L, job?.endedAtEpochMs)
        assertNull(job?.errorCode)
        assertEquals(
            listOf("REAL_TIME_FACTOR", "PEAK_PSS_KB"),
            database.sessionMetricDao().findBySession("session-1").map { it.metricName },
        )
    }

    @Test
    fun processingTelemetryRepairsMissingSessionIndexBeforeStarting() = runBlocking {
        var repairCount = 0
        var nextJob = 0
        val store = RoomProcessingTelemetryStore(
            database = database,
            nowEpochMs = { 100L },
            newId = { "job-reindexed-${++nextJob}" },
            ensureSessionIndexed = { sessionId ->
                repairCount += 1
                database.sessionDao().upsert(session(sessionId))
            },
        )

        val jobId = store.start("session-reindexed", "WHISPER_ASR_POST_PROCESS")

        assertEquals(1, repairCount)
        assertEquals("job-reindexed-1", jobId)
        assertEquals(
            "session-reindexed",
            database.processingJobDao().findById(jobId)?.sessionId,
        )
        store.start("session-reindexed", "WHISPER_ASR_POST_PROCESS")
        assertEquals(1, repairCount)
    }

    @Test
    fun invalidMetricRollsBackWithoutFinishingJob() = runBlocking {
        database.sessionDao().upsert(session("session-1"))
        val store = RoomProcessingTelemetryStore(
            database = database,
            nowEpochMs = { 100L },
            newId = { "job-rollback" },
        )
        val jobId = store.start("session-1", "WHISPER_ASR_POST_PROCESS")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.complete(
                    jobId,
                    listOf(PersistedProcessingMetric("invalid metric", Double.NaN)),
                )
            }
        }

        assertEquals(
            RoomProcessingTelemetryStore.STATE_RUNNING,
            database.processingJobDao().findById(jobId)?.state,
        )
        assertEquals(0, database.sessionMetricDao().findBySession("session-1").size)
    }

    @Test
    fun failedJobStoresOnlySanitizedErrorCode() = runBlocking {
        database.sessionDao().upsert(session("session-1"))
        val store = RoomProcessingTelemetryStore(
            database = database,
            nowEpochMs = { 100L },
            newId = { "job-failed" },
        )
        val jobId = store.start("session-1", "WHISPER_ASR_POST_PROCESS")

        store.fail(jobId, "ASR_FAILED")

        val job = database.processingJobDao().findById(jobId)
        assertEquals(RoomProcessingTelemetryStore.STATE_FAILED, job?.state)
        assertEquals("ASR_FAILED", job?.errorCode)
    }

    @Test
    fun interruptedRunningJobsAreRecoveredIdempotently() = runBlocking {
        database.sessionDao().upsert(session("session-1"))
        var now = 100L
        val store = RoomProcessingTelemetryStore(
            database = database,
            nowEpochMs = { now },
            newId = { "job-interrupted" },
        )
        val jobId = store.start("session-1", "SHERPA_STREAMING_REPLAY")

        now = 500L
        assertEquals(1, store.recoverInterrupted())
        assertEquals(0, store.recoverInterrupted())

        val job = database.processingJobDao().findById(jobId)
        assertEquals(RoomProcessingTelemetryStore.STATE_FAILED, job?.state)
        assertEquals(RoomProcessingTelemetryStore.ERROR_PROCESS_INTERRUPTED, job?.errorCode)
        assertEquals(500L, job?.endedAtEpochMs)
    }

    private fun session(id: String) = SessionEntity(
        id = id,
        createdAtEpochMs = 1,
        status = "COMPLETED",
        durationMs = 5_000,
        updatedAtEpochMs = 2,
    )

    private fun segment(sequence: Int, text: String) = TranscriptSegmentEntity(
        sessionId = "session-1",
        sequence = sequence,
        startMs = sequence * 1_000L,
        endMs = (sequence + 1) * 1_000L,
        text = text,
        sourceModel = "sherpa-es",
    )
}
