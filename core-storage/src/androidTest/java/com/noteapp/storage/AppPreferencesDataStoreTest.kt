package com.noteapp.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPreferencesDataStoreTest {
    @Test
    fun protoDataStorePersistsTypedTranscriptionPreferences() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val isolatedFilesDirectory = File(
            baseContext.cacheDir,
            "app-preferences-${UUID.randomUUID()}",
        )
        val isolatedContext = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = isolatedFilesDirectory
        }
        val repository = createAppPreferencesRepository(isolatedContext)

        repository.setCapturePipeline("native-48k-to-16k")
        repository.setIncrementalModel("sherpa-es")
        repository.setBenchmarkThreadCount(6)
        repository.setBenchmarkChunkSeconds(20)
        repository.setRetentionDays(365)
        repository.acknowledgeConsentNotice(123_456L)

        assertEquals(
            AppPreferences(
                capturePipelineId = "native-48k-to-16k",
                incrementalModelId = "sherpa-es",
                benchmarkThreadCount = 6,
                benchmarkChunkSeconds = 20,
                retentionDays = 365,
                consentNoticeVersionAcknowledged = 1,
                consentAcknowledgedAtEpochMs = 123_456L,
            ),
            repository.preferences.first(),
        )
        assertTrue(
            File(isolatedFilesDirectory, "datastore/app_preferences.pb").isFile,
        )
    }
}
