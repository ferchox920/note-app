package com.noteapp.asr

import kotlinx.collections.immutable.persistentListOf
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class IncrementalTranscriptStoreTest {
    private lateinit var directory: File
    private val store = IncrementalTranscriptStore()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("incremental-transcript-store").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `checkpoint appends metrics and final materializes one self contained artifact`() {
        val firstState = stateWithMetrics(2)

        val firstPersistedCount = store.writeCheckpoint(
            sessionDirectory = directory,
            modelId = "streaming-es",
            capturePipelineId = "direct-16k",
            state = firstState,
            persistedMetricCount = 0,
        )

        assertEquals(2, firstPersistedCount)
        assertEquals(2, journalLines().size)
        assertFalse(snapshot().has("inferenceMetrics"))
        assertEquals(2, store.read(directory)?.inferenceMetrics?.size)

        val continuedState = stateWithMetrics(3)
        val continuedPersistedCount = store.writeCheckpoint(
            sessionDirectory = directory,
            modelId = "streaming-es",
            capturePipelineId = "direct-16k",
            state = continuedState,
            persistedMetricCount = firstPersistedCount,
        )

        assertEquals(3, continuedPersistedCount)
        assertEquals(3, journalLines().size)

        store.writeFinal(
            sessionDirectory = directory,
            modelId = "streaming-es",
            capturePipelineId = "direct-16k",
            state = continuedState,
            persistedMetricCount = continuedPersistedCount,
        )

        val finalSnapshot = snapshot()
        assertEquals(5, finalSnapshot.getInt("schemaVersion"))
        assertEquals(3, finalSnapshot.getJSONArray("inferenceMetrics").length())
        assertFalse(File(directory, IncrementalTranscriptStore.JOURNAL_FILE_NAME).exists())
        assertEquals(3, store.read(directory)?.inferenceMetrics?.size)
    }

    @Test
    fun `recovery discards journal tail not committed by atomic checkpoint`() {
        val state = stateWithMetrics(2)
        store.writeCheckpoint(
            sessionDirectory = directory,
            modelId = "streaming-es",
            capturePipelineId = "direct-16k",
            state = state,
            persistedMetricCount = 0,
        )
        val journal = File(directory, IncrementalTranscriptStore.JOURNAL_FILE_NAME)
        journal.appendText(journalLines().last() + "\n", Charsets.UTF_8)

        val recovered = store.read(directory)

        assertNotNull(recovered)
        assertEquals(2, recovered?.inferenceMetrics?.size)
        assertEquals(2, journalLines().size)
        assertTrue(recovered?.inferenceMetrics is kotlinx.collections.immutable.PersistentList<*>)
        assertEquals(2, store.persistedMetricCount(directory))
    }

    @Test
    fun `failed checkpoint rolls journal back to confirmed suffix`() {
        val firstState = stateWithMetrics(2)
        val persistedMetricCount = store.writeCheckpoint(
            sessionDirectory = directory,
            modelId = "streaming-es",
            capturePipelineId = "direct-16k",
            state = firstState,
            persistedMetricCount = 0,
        )
        val blockedTemporary = File(
            directory,
            "${IncrementalTranscriptStore.FILE_NAME}.tmp",
        )
        assertTrue(blockedTemporary.mkdir())

        assertThrows(Exception::class.java) {
            store.writeCheckpoint(
                sessionDirectory = directory,
                modelId = "streaming-es",
                capturePipelineId = "direct-16k",
                state = stateWithMetrics(3),
                persistedMetricCount = persistedMetricCount,
            )
        }

        assertEquals(2, journalLines().size)
        assertEquals(2, snapshot().getInt("metricCount"))
    }

    @Test
    fun `forty five minute journal remains append only until final materialization`() {
        var metrics = persistentListOf<IncrementalInferenceMetric>()
        var segments = persistentListOf<IncrementalTranscriptSegment>()
        var persistedMetricCount = 0

        repeat(ENDPOINT_COUNT_45_MINUTES) { endpoint ->
            repeat(METRICS_PER_ENDPOINT) { offset ->
                val sequence = endpoint * METRICS_PER_ENDPOINT + offset
                metrics = metrics.add(metric(sequence, final = offset == METRICS_PER_ENDPOINT - 1))
            }
            segments = segments.add(
                IncrementalTranscriptSegment(
                    startMs = endpoint * ENDPOINT_MS,
                    endMs = (endpoint + 1) * ENDPOINT_MS,
                    text = "segmento $endpoint",
                ),
            )
            persistedMetricCount = store.writeCheckpoint(
                sessionDirectory = directory,
                modelId = "streaming-es",
                capturePipelineId = "direct-16k",
                state = IncrementalAsrState(
                    stableText = segments.joinToString(" ") { it.text },
                    finalizedSegments = segments,
                    inferenceMetrics = metrics,
                ),
                persistedMetricCount = persistedMetricCount,
            )
        }

        assertEquals(FRAME_COUNT_45_MINUTES, persistedMetricCount)
        assertEquals(
            FRAME_COUNT_45_MINUTES,
            File(directory, IncrementalTranscriptStore.JOURNAL_FILE_NAME)
                .useLines { it.count() },
        )
        assertFalse(snapshot().has("inferenceMetrics"))

        val finalState = IncrementalAsrState(
            stableText = segments.joinToString(" ") { it.text },
            finalizedSegments = segments,
            inferenceMetrics = metrics,
        )
        store.writeFinal(
            sessionDirectory = directory,
            modelId = "streaming-es",
            capturePipelineId = "direct-16k",
            state = finalState,
            persistedMetricCount = persistedMetricCount,
        )

        assertEquals(
            FRAME_COUNT_45_MINUTES,
            snapshot().getJSONArray("inferenceMetrics").length(),
        )
        assertFalse(File(directory, IncrementalTranscriptStore.JOURNAL_FILE_NAME).exists())
    }

    private fun stateWithMetrics(count: Int): IncrementalAsrState =
        IncrementalAsrState(
            stableText = "texto estable",
            finalizedSegments = listOf(
                IncrementalTranscriptSegment(0, count * 100L, "texto estable"),
            ),
            inferenceMetrics = persistentListOf<IncrementalInferenceMetric>().addAll(
                List(count) { sequence ->
                    metric(sequence, final = sequence == count - 1)
                },
            ),
        )

    private fun metric(sequence: Int, final: Boolean): IncrementalInferenceMetric =
        IncrementalInferenceMetric(
            sequence = sequence,
            windowStartMs = sequence * FRAME_MS,
            windowEndMs = (sequence + 1) * FRAME_MS,
            final = final,
            audioDurationMs = FRAME_MS,
            inferenceDurationMs = 5,
            visibleLatencyMs = 1,
            realTimeFactor = 0.05,
        )

    private fun snapshot(): JSONObject = JSONObject(
        File(directory, IncrementalTranscriptStore.FILE_NAME).readText(Charsets.UTF_8),
    )

    private fun journalLines(): List<String> =
        File(directory, IncrementalTranscriptStore.JOURNAL_FILE_NAME)
            .takeIf(File::isFile)
            ?.readLines(Charsets.UTF_8)
            .orEmpty()

    private companion object {
        const val FRAME_MS = 100L
        const val METRICS_PER_ENDPOINT = 100
        const val ENDPOINT_MS = FRAME_MS * METRICS_PER_ENDPOINT
        const val FRAME_COUNT_45_MINUTES = 45 * 60 * 1_000 / FRAME_MS.toInt()
        const val ENDPOINT_COUNT_45_MINUTES = FRAME_COUNT_45_MINUTES / METRICS_PER_ENDPOINT
    }
}
