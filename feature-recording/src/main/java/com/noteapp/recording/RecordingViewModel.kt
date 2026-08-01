package com.noteapp.recording

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.noteapp.audio.AudioRecordingController
import com.noteapp.audio.CapturePipeline
import com.noteapp.domain.RecordingIntent
import com.noteapp.domain.SessionStatus
import com.noteapp.domain.RecordingSession
import com.noteapp.storage.SessionCheckpointStore
import com.noteapp.storage.AppPreferences
import com.noteapp.storage.AppPreferencesStore
import com.noteapp.storage.ProcessingTelemetryStore
import com.noteapp.storage.SessionDeletionStore
import com.noteapp.storage.SessionRetentionStore
import com.noteapp.security.SessionArtifactStore
import com.noteapp.asr.AsrLabRunner
import com.noteapp.asr.AsrLabResult
import com.noteapp.asr.AsrLabConfig
import com.noteapp.asr.IncrementalTranscriptDocument
import com.noteapp.asr.IncrementalTranscriptSegment
import com.noteapp.asr.IncrementalTranscriptStore
import com.noteapp.asr.ModelVerificationResult
import com.noteapp.asr.SherpaStreamingLabConfig
import com.noteapp.asr.SherpaStreamingLabResult
import com.noteapp.asr.SherpaStreamingLabRunner
import com.noteapp.asr.SherpaStreamingModelCatalog
import com.noteapp.asr.SherpaStreamingModelVerifier
import com.noteapp.asr.WhisperModelCatalog
import com.noteapp.asr.WhisperModelDescriptor
import com.noteapp.asr.WhisperModelInstaller
import com.noteapp.asr.WhisperModelVerifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.noteapp.vad.VadComparisonResult
import com.noteapp.vad.VadComparisonRunner

@Immutable
data class RecordingUiState(
    val sessionId: String? = null,
    val status: SessionStatus = SessionStatus.NEW,
    val durationMs: Long = 0,
    val bytesWritten: Long = 0,
    val errorCode: String? = null,
    val speechDetected: Boolean = false,
    val vadSegmentCount: Int = 0,
    val vadErrorCode: String? = null,
    val installedModelIds: Set<String> = emptySet(),
    val asrRunning: Boolean = false,
    val asrResult: AsrLabResult? = null,
    val streamingAsrResult: SherpaStreamingLabResult? = null,
    val asrError: String? = null,
    val sessionDeletionRunning: Boolean = false,
    val sessionDeletionError: String? = null,
    val recoverableSessions: List<RecordingSession> = emptyList(),
    val completedSessions: List<RecordingSession> = emptyList(),
    val labSessionId: String? = null,
    val readErrorCount: Int = 0,
    val discontinuityCount: Int = 0,
    val estimatedMissingFrames: Long = 0,
    val capturePipelineId: String = CapturePipeline.DIRECT_16_KHZ.id,
    val captureSampleRateHz: Int = CapturePipeline.DIRECT_16_KHZ.captureSampleRateHz,
    val vadComparisonRunning: Boolean = false,
    val vadComparisonResult: VadComparisonResult? = null,
    val vadComparisonError: String? = null,
    val incrementalAsrEnabled: Boolean = false,
    val incrementalModelId: String? = null,
    val incrementalStableText: String = "",
    val incrementalUnstableText: String = "",
    val incrementalAsrRunning: Boolean = false,
    val incrementalQueueDepth: Int = 0,
    val incrementalDroppedPartialCount: Long = 0,
    val incrementalPartialCount: Int = 0,
    val incrementalStableConflictCount: Int = 0,
    val incrementalSuppressedRepetitionCount: Int = 0,
    val incrementalTimeToFirstTextMs: Long? = null,
    val incrementalLastVisibleLatencyMs: Long? = null,
    val incrementalLastRealTimeFactor: Double? = null,
    val incrementalAsrErrorCode: String? = null,
    val incrementalFinalizedSegments: List<IncrementalTranscriptSegment> = emptyList(),
    val preferencesReady: Boolean = false,
    val preferredCapturePipelineId: String = AppPreferences.DEFAULT_CAPTURE_PIPELINE_ID,
    val selectedIncrementalModelId: String? = null,
    val benchmarkThreadCount: Int = AppPreferences.DEFAULT_BENCHMARK_THREAD_COUNT,
    val benchmarkChunkSeconds: Int = AppPreferences.DEFAULT_BENCHMARK_CHUNK_SECONDS,
    val retentionDays: Int = AppPreferences.RETENTION_FOREVER_DAYS,
    val consentNoticeAcknowledged: Boolean = false,
    val retentionDeletedCount: Int = 0,
)

@HiltViewModel
class RecordingViewModel @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val controller: AudioRecordingController,
    private val checkpointStore: SessionCheckpointStore,
    private val sessionArtifactStore: SessionArtifactStore,
    private val appPreferencesStore: AppPreferencesStore,
    private val processingTelemetryStore: ProcessingTelemetryStore,
    private val sessionDeletionStore: SessionDeletionStore,
    private val sessionRetentionStore: SessionRetentionStore,
    private val asrLabRunner: AsrLabRunner,
    private val sherpaStreamingLabRunner: SherpaStreamingLabRunner,
    private val vadComparisonRunner: VadComparisonRunner,
) : ViewModel() {
    private val asrState = MutableStateFlow(AsrUiState())
    private val modelsDirectory = File(applicationContext.filesDir, "models")
    private val incrementalTranscriptStore = IncrementalTranscriptStore(sessionArtifactStore)
    private val preferencesState: StateFlow<AppPreferences?> = appPreferencesStore.preferences
        .map<AppPreferences, AppPreferences?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<RecordingUiState> = combine(
        controller.state,
        asrState,
        preferencesState,
    ) { runtime, asr, storedPreferences ->
            val preferences = storedPreferences ?: AppPreferences()
            val storedTranscript = asr.selectedIncrementalTranscript.takeIf {
                runtime.status == SessionStatus.NEW &&
                    asr.selectedLabSessionId != null
            }
            val storedState = storedTranscript?.state
            val selectedSession = asr.completedSessions.firstOrNull {
                it.id == asr.selectedLabSessionId
            }
            RecordingUiState(
                sessionId = runtime.sessionId,
                status = runtime.status,
                durationMs = selectedSession
                    ?.durationMs
                    ?.takeIf { runtime.status == SessionStatus.NEW }
                    ?: runtime.durationMs,
                bytesWritten = runtime.bytesWritten,
                errorCode = runtime.errorCode,
                speechDetected = runtime.speechDetected,
                vadSegmentCount = runtime.vadSegmentCount,
                vadErrorCode = runtime.vadErrorCode,
                installedModelIds = asr.installedModelIds,
                asrRunning = asr.running,
                asrResult = asr.result,
                streamingAsrResult = asr.streamingResult,
                asrError = asr.error,
                sessionDeletionRunning = asr.sessionDeletionRunning,
                sessionDeletionError = asr.sessionDeletionError,
                recoverableSessions = asr.recoverableSessions,
                completedSessions = asr.completedSessions,
                labSessionId = runtime.sessionId.takeIf { runtime.status == SessionStatus.COMPLETED }
                    ?: asr.selectedLabSessionId
                    ?: asr.completedSessions.firstOrNull()?.id,
                readErrorCount = runtime.readErrorCount,
                discontinuityCount = runtime.discontinuityCount,
                estimatedMissingFrames = runtime.estimatedMissingFrames,
                capturePipelineId = storedTranscript?.capturePipelineId
                    ?: runtime.capturePipelineId,
                captureSampleRateHz = runtime.captureSampleRateHz,
                vadComparisonRunning = asr.vadComparisonRunning,
                vadComparisonResult = asr.vadComparisonResult,
                vadComparisonError = asr.vadComparisonError,
                incrementalAsrEnabled = storedState?.enabled
                    ?: runtime.incrementalAsrEnabled,
                incrementalModelId = storedTranscript?.modelId
                    ?: runtime.incrementalModelId,
                incrementalStableText = storedState?.stableText
                    ?: runtime.incrementalStableText,
                incrementalUnstableText = storedState?.unstableText
                    ?: runtime.incrementalUnstableText,
                incrementalAsrRunning = storedState?.running
                    ?: runtime.incrementalAsrRunning,
                incrementalQueueDepth = storedState?.queueDepth
                    ?: runtime.incrementalQueueDepth,
                incrementalDroppedPartialCount = storedState?.droppedPartialCount
                    ?: runtime.incrementalDroppedPartialCount,
                incrementalPartialCount = storedState?.partialCount
                    ?: runtime.incrementalPartialCount,
                incrementalStableConflictCount = storedState?.stableConflictCount
                    ?: runtime.incrementalStableConflictCount,
                incrementalSuppressedRepetitionCount = storedState?.suppressedRepetitionCount
                    ?: runtime.incrementalSuppressedRepetitionCount,
                incrementalTimeToFirstTextMs = storedState?.timeToFirstTextMs
                    ?: runtime.incrementalTimeToFirstTextMs,
                incrementalLastVisibleLatencyMs = storedState?.lastVisibleLatencyMs
                    ?: runtime.incrementalLastVisibleLatencyMs,
                incrementalLastRealTimeFactor = storedState?.lastRealTimeFactor
                    ?: runtime.incrementalLastRealTimeFactor,
                incrementalAsrErrorCode = storedState?.errorCode
                    ?: asr.selectedIncrementalError
                    ?: runtime.incrementalAsrErrorCode,
                incrementalFinalizedSegments = storedState?.finalizedSegments
                    ?: runtime.incrementalFinalizedSegments,
                preferencesReady = storedPreferences != null,
                preferredCapturePipelineId = preferences.capturePipelineId,
                selectedIncrementalModelId = preferences.incrementalModelId,
                benchmarkThreadCount = preferences.benchmarkThreadCount,
                benchmarkChunkSeconds = preferences.benchmarkChunkSeconds,
                retentionDays = preferences.retentionDays,
                consentNoticeAcknowledged =
                    preferences.consentNoticeVersionAcknowledged ==
                    AppPreferences.CURRENT_CONSENT_NOTICE_VERSION,
                retentionDeletedCount = asr.retentionDeletedCount,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingUiState())

    init {
        refreshInstalledModels()
        initializePersistedState()
    }

    fun onIntent(intent: RecordingIntent) {
        when (intent) {
            RecordingIntent.Start -> controller.start()
            RecordingIntent.Pause -> controller.pause()
            RecordingIntent.Resume -> controller.resume()
            RecordingIntent.Complete -> controller.complete()
            RecordingIntent.Abort -> controller.abort()
            RecordingIntent.Recover, is RecordingIntent.Fail -> Unit
        }
    }

    fun startRecording(pipeline: CapturePipeline, incrementalModelId: String? = null) {
        viewModelScope.launch {
            appPreferencesStore.setCapturePipeline(pipeline.id)
        }
        controller.start(pipeline, incrementalModelId)
    }

    fun acknowledgeConsentAndStart(
        pipeline: CapturePipeline,
        incrementalModelId: String? = null,
    ) {
        viewModelScope.launch {
            runCatching {
                appPreferencesStore.acknowledgeConsentNotice(System.currentTimeMillis())
                appPreferencesStore.setCapturePipeline(pipeline.id)
            }.onSuccess {
                controller.start(pipeline, incrementalModelId)
            }.onFailure { failure ->
                asrState.value = asrState.value.copy(
                    error = failure.safeErrorCode("CONSENT_ACKNOWLEDGEMENT_SAVE_FAILED"),
                )
            }
        }
    }

    fun selectIncrementalModel(modelId: String?) {
        viewModelScope.launch {
            appPreferencesStore.setIncrementalModel(modelId)
        }
    }

    fun selectBenchmarkThreadCount(count: Int) {
        viewModelScope.launch {
            appPreferencesStore.setBenchmarkThreadCount(count)
        }
    }

    fun selectBenchmarkChunkSeconds(seconds: Int) {
        viewModelScope.launch {
            appPreferencesStore.setBenchmarkChunkSeconds(seconds)
        }
    }

    fun setRetentionDays(days: Int) {
        require(days in AppPreferences.SUPPORTED_RETENTION_DAYS) { "INVALID_RETENTION_DAYS" }
        val initial = asrState.value
        check(!initial.running) { "ASR_RUNNING_RETENTION_CHANGE_REFUSED" }
        check(!initial.sessionDeletionRunning) { "SESSION_DELETE_ALREADY_RUNNING" }
        asrState.value = initial.copy(
            sessionDeletionRunning = true,
            sessionDeletionError = null,
            retentionDeletedCount = 0,
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appPreferencesStore.setRetentionDays(days)
                val result = sessionRetentionStore.apply(days)
                result to checkpointStore.findCompleted()
            }.onSuccess { (result, sessions) ->
                updateCompletedSessionsAfterDeletion(
                    sessions = sessions,
                    deletedCount = result.deletedSessionIds.size,
                )
            }.onFailure { failure ->
                asrState.value = asrState.value.copy(
                    sessionDeletionRunning = false,
                    sessionDeletionError = failure.safeErrorCode("RETENTION_APPLY_FAILED"),
                )
            }
        }
    }

    fun importModel(uri: Uri, descriptor: WhisperModelDescriptor) {
        viewModelScope.launch {
            asrState.value = asrState.value.copy(running = true, error = null)
            runCatching {
                val input = requireNotNull(applicationContext.contentResolver.openInputStream(uri))
                WhisperModelInstaller.install(input, modelsDirectory, descriptor)
            }.onSuccess {
                refreshInstalledModels()
                asrState.value = asrState.value.copy(running = false, error = null)
            }.onFailure { failure ->
                asrState.value = asrState.value.copy(
                    running = false,
                    error = failure.message ?: "MODEL_IMPORT_FAILED",
                )
            }
        }
    }

    fun transcribe(
        descriptor: WhisperModelDescriptor,
    ) {
        val sessionId = uiState.value.labSessionId ?: return
        val preferences = preferencesState.value ?: return
        val config = AsrLabConfig(
            threadCount = preferences.benchmarkThreadCount,
            maxChunkMs = preferences.benchmarkChunkSeconds * 1_000L,
        )
        viewModelScope.launch {
            asrState.value = asrState.value.copy(
                running = true,
                result = null,
                streamingResult = null,
                error = null,
            )
            val jobId = runCatching {
                processingTelemetryStore.start(sessionId, WHISPER_ASR_JOB)
            }.getOrElse { failure ->
                asrState.value = asrState.value.copy(
                    running = false,
                    error = failure.message ?: "ASR_JOB_START_FAILED",
                )
                return@launch
            }
            try {
                val result = asrLabRunner.transcribeSession(
                    sessionDirectory = File(applicationContext.filesDir, "recordings/$sessionId"),
                    modelFile = File(modelsDirectory, descriptor.fileName),
                    descriptor = descriptor,
                    config = config,
                )
                processingTelemetryStore.complete(jobId, result.toProcessingMetrics())
                asrState.value = asrState.value.copy(running = false, result = result)
            } catch (cancellation: CancellationException) {
                markProcessingJobFailed(jobId, "ASR_CANCELLED")
                throw cancellation
            } catch (failure: Throwable) {
                markProcessingJobFailed(jobId, failure.safeErrorCode("ASR_FAILED"))
                asrState.value = asrState.value.copy(
                    running = false,
                    error = failure.message ?: "ASR_FAILED",
                )
            }
        }
    }

    fun transcribeStreaming() {
        val sessionId = uiState.value.labSessionId ?: return
        val preferences = preferencesState.value ?: return
        val config = SherpaStreamingLabConfig(
            threadCount = preferences.benchmarkThreadCount,
        )
        val descriptor = SherpaStreamingModelCatalog.spanishKroko
        viewModelScope.launch {
            asrState.value = asrState.value.copy(
                running = true,
                result = null,
                streamingResult = null,
                error = null,
            )
            val jobId = runCatching {
                processingTelemetryStore.start(sessionId, SHERPA_REPLAY_JOB)
            }.getOrElse { failure ->
                asrState.value = asrState.value.copy(
                    running = false,
                    error = failure.message ?: "ASR_JOB_START_FAILED",
                )
                return@launch
            }
            try {
                val result = sherpaStreamingLabRunner.transcribeSession(
                    sessionDirectory = File(applicationContext.filesDir, "recordings/$sessionId"),
                    modelDirectory = File(modelsDirectory, descriptor.directoryName),
                    descriptor = descriptor,
                    config = config,
                )
                processingTelemetryStore.complete(jobId, result.toProcessingMetrics())
                asrState.value = asrState.value.copy(
                    running = false,
                    streamingResult = result,
                )
            } catch (cancellation: CancellationException) {
                markProcessingJobFailed(jobId, "ASR_CANCELLED")
                throw cancellation
            } catch (failure: Throwable) {
                markProcessingJobFailed(jobId, failure.safeErrorCode("STREAMING_ASR_FAILED"))
                asrState.value = asrState.value.copy(
                    running = false,
                    error = failure.message ?: "STREAMING_ASR_FAILED",
                )
            }
        }
    }

    private suspend fun markProcessingJobFailed(jobId: String, errorCode: String) {
        withContext(NonCancellable) {
            runCatching {
                processingTelemetryStore.fail(jobId, errorCode)
            }
        }
    }

    private fun Throwable.safeErrorCode(fallback: String): String =
        message?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{2,79}")) } ?: fallback

    fun recoverSession(sessionId: String) {
        controller.recover(sessionId)
        asrState.value = asrState.value.copy(
            recoverableSessions = asrState.value.recoverableSessions.filterNot { it.id == sessionId },
        )
    }

    fun selectLabSession(sessionId: String) {
        check(asrState.value.completedSessions.any { it.id == sessionId }) {
            "LAB_SESSION_NOT_FOUND"
        }
        asrState.value = asrState.value.copy(
            selectedLabSessionId = sessionId,
            selectedIncrementalTranscript = null,
            selectedIncrementalError = null,
            result = null,
            error = null,
        )
        loadIncrementalTranscript(sessionId)
    }

    fun deleteCompletedSession(sessionId: String) {
        val initial = asrState.value
        check(!initial.running) { "ASR_RUNNING_DELETE_REFUSED" }
        check(!initial.sessionDeletionRunning) { "SESSION_DELETE_ALREADY_RUNNING" }
        check(initial.completedSessions.any { session -> session.id == sessionId }) {
            "COMPLETED_SESSION_NOT_FOUND"
        }
        asrState.value = initial.copy(
            sessionDeletionRunning = true,
            sessionDeletionError = null,
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                sessionDeletionStore.delete(sessionId)
            }.onSuccess {
                val current = asrState.value
                val remaining = current.completedSessions.filterNot { session ->
                    session.id == sessionId
                }
                val deletedSelected = current.selectedLabSessionId == sessionId
                val nextSelected = if (deletedSelected) remaining.firstOrNull()?.id
                else current.selectedLabSessionId
                asrState.value = current.copy(
                    completedSessions = remaining,
                    selectedLabSessionId = nextSelected,
                    selectedIncrementalTranscript = if (deletedSelected) null
                    else current.selectedIncrementalTranscript,
                    selectedIncrementalError = if (deletedSelected) null
                    else current.selectedIncrementalError,
                    result = if (deletedSelected) null else current.result,
                    streamingResult = if (deletedSelected) null else current.streamingResult,
                    error = if (deletedSelected) null else current.error,
                    sessionDeletionRunning = false,
                    sessionDeletionError = null,
                )
                if (deletedSelected) nextSelected?.let(::loadIncrementalTranscript)
            }.onFailure { failure ->
                asrState.value = asrState.value.copy(
                    sessionDeletionRunning = false,
                    sessionDeletionError = failure.safeErrorCode("SESSION_DELETE_FAILED"),
                )
            }
        }
    }

    private fun updateCompletedSessionsAfterDeletion(
        sessions: List<RecordingSession>,
        deletedCount: Int,
    ) {
        val current = asrState.value
        val nextSelected = current.selectedLabSessionId
            ?.takeIf { selected -> sessions.any { session -> session.id == selected } }
            ?: sessions.firstOrNull()?.id
        val selectionChanged = nextSelected != current.selectedLabSessionId
        asrState.value = current.copy(
            completedSessions = sessions,
            selectedLabSessionId = nextSelected,
            selectedIncrementalTranscript = if (selectionChanged) null
            else current.selectedIncrementalTranscript,
            selectedIncrementalError = if (selectionChanged) null
            else current.selectedIncrementalError,
            result = if (selectionChanged) null else current.result,
            streamingResult = if (selectionChanged) null else current.streamingResult,
            error = if (selectionChanged) null else current.error,
            sessionDeletionRunning = false,
            sessionDeletionError = null,
            retentionDeletedCount = deletedCount,
        )
        if (selectionChanged) nextSelected?.let(::loadIncrementalTranscript)
    }

    private fun loadIncrementalTranscript(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = runCatching {
                incrementalTranscriptStore.readDocument(
                    File(applicationContext.filesDir, "recordings/$sessionId"),
                )
            }
            if (asrState.value.selectedLabSessionId != sessionId) return@launch
            asrState.value = asrState.value.copy(
                selectedIncrementalTranscript = loaded.getOrNull(),
                selectedIncrementalError = loaded.exceptionOrNull()?.let {
                    "INCREMENTAL_TRANSCRIPT_READ_FAILED"
                },
            )
        }
    }

    fun compareVad() {
        val sessionId = uiState.value.labSessionId ?: return
        viewModelScope.launch {
            asrState.value = asrState.value.copy(
                vadComparisonRunning = true,
                vadComparisonResult = null,
                vadComparisonError = null,
            )
            runCatching {
                vadComparisonRunner.compare(File(applicationContext.filesDir, "recordings/$sessionId"))
            }.onSuccess { result ->
                asrState.value = asrState.value.copy(
                    vadComparisonRunning = false,
                    vadComparisonResult = result,
                )
            }.onFailure { failure ->
                asrState.value = asrState.value.copy(
                    vadComparisonRunning = false,
                    vadComparisonError = failure.message ?: "VAD_COMPARISON_FAILED",
                )
            }
        }
    }

    private fun refreshInstalledModels() {
        val installed = WhisperModelCatalog.evaluationModels
            .filter { descriptor ->
                WhisperModelVerifier.verify(
                    File(modelsDirectory, descriptor.fileName),
                    descriptor,
                ) == ModelVerificationResult.Valid
            }
            .mapTo(mutableSetOf()) { it.id }
        SherpaStreamingModelCatalog.evaluationModels.forEach { descriptor ->
            val modelDirectory = File(modelsDirectory, descriptor.directoryName)
            if (SherpaStreamingModelVerifier.verify(modelDirectory, descriptor) == null) {
                installed += descriptor.id
            }
        }
        asrState.value = asrState.value.copy(installedModelIds = installed)
    }

    private fun initializePersistedState() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                sessionDeletionStore.recoverInterrupted()
                sessionArtifactStore.migrateAll()
                processingTelemetryStore.recoverInterrupted()
                val recoverable = checkpointStore.findRecoverable()
                val indexedSessions = checkpointStore.findCompleted()
                val preferences = appPreferencesStore.preferences.first()
                val retentionAttempt = runCatching {
                    sessionRetentionStore.apply(preferences.retentionDays)
                }
                val sessions = if (retentionAttempt.getOrNull()?.deletedSessionIds?.isNotEmpty() == true) {
                    checkpointStore.findCompleted()
                } else {
                    indexedSessions
                }
                PersistedStateInitialization(
                    recoverableSessions = recoverable,
                    completedSessions = sessions,
                    retentionDeletedCount = retentionAttempt.getOrNull()?.deletedSessionIds?.size ?: 0,
                    retentionError = retentionAttempt.exceptionOrNull()
                        ?.safeErrorCode("RETENTION_APPLY_FAILED"),
                )
            }.onSuccess { initialization ->
                val recoverable = initialization.recoverableSessions
                val sessions = initialization.completedSessions
                val selectedSessionId = asrState.value.selectedLabSessionId
                    ?.takeIf { selected -> sessions.any { it.id == selected } }
                    ?: sessions.firstOrNull()?.id
                asrState.value = asrState.value.copy(
                    recoverableSessions = recoverable,
                    completedSessions = sessions,
                    selectedLabSessionId = selectedSessionId,
                    selectedIncrementalTranscript = null,
                    selectedIncrementalError = null,
                    sessionDeletionError = initialization.retentionError,
                    retentionDeletedCount = initialization.retentionDeletedCount,
                )
                selectedSessionId?.let(::loadIncrementalTranscript)
            }.onFailure { failure ->
                asrState.value = asrState.value.copy(
                    error = failure.message ?: "ARTIFACT_STORAGE_INITIALIZATION_FAILED",
                )
            }
        }
    }

    private data class PersistedStateInitialization(
        val recoverableSessions: List<RecordingSession>,
        val completedSessions: List<RecordingSession>,
        val retentionDeletedCount: Int,
        val retentionError: String?,
    )

    private data class AsrUiState(
        val installedModelIds: Set<String> = emptySet(),
        val running: Boolean = false,
        val result: AsrLabResult? = null,
        val streamingResult: SherpaStreamingLabResult? = null,
        val error: String? = null,
        val sessionDeletionRunning: Boolean = false,
        val sessionDeletionError: String? = null,
        val retentionDeletedCount: Int = 0,
        val recoverableSessions: List<RecordingSession> = emptyList(),
        val completedSessions: List<RecordingSession> = emptyList(),
        val selectedLabSessionId: String? = null,
        val selectedIncrementalTranscript: IncrementalTranscriptDocument? = null,
        val selectedIncrementalError: String? = null,
        val vadComparisonRunning: Boolean = false,
        val vadComparisonResult: VadComparisonResult? = null,
        val vadComparisonError: String? = null,
    )
}
