package com.noteapp.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val store = RoomSessionCheckpointStore(temporaryFolder.root, database)

        assertEquals("artifact-session", store.findCompleted().single().id)
        store.refreshIndex()
        assertEquals(
            listOf("original"),
            database.transcriptSegmentDao().findBySession("artifact-session").map { it.text },
        )

        directory.resolve("incremental-transcript.json").writeText(
            """{"schemaVersion":5,"modelId":"sherpa-es","segments":[{"startMs":0,"endMs":2000,"text":"uno"},{"startMs":2000,"endMs":5000,"text":"dos"}]}""",
        )
        store.refreshIndex()

        assertEquals(
            listOf("uno", "dos"),
            database.transcriptSegmentDao().findBySession("artifact-session").map { it.text },
        )
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
