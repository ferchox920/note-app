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
import com.noteapp.asr.AsrLabRunner
import com.noteapp.asr.AsrLabResult
import com.noteapp.asr.IncrementalTranscriptSegment
import com.noteapp.asr.ModelVerificationResult
import com.noteapp.asr.WhisperModelCatalog
import com.noteapp.asr.WhisperModelDescriptor
import com.noteapp.asr.WhisperModelInstaller
import com.noteapp.asr.WhisperModelVerifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    val asrError: String? = null,
    val recoverableSessions: List<RecordingSession> = emptyList(),
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
    val incrementalTimeToFirstTextMs: Long? = null,
    val incrementalLastVisibleLatencyMs: Long? = null,
    val incrementalLastRealTimeFactor: Double? = null,
    val incrementalAsrErrorCode: String? = null,
    val incrementalFinalizedSegments: List<IncrementalTranscriptSegment> = emptyList(),
)

@HiltViewModel
class RecordingViewModel @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val controller: AudioRecordingController,
    private val checkpointStore: SessionCheckpointStore,
    private val asrLabRunner: AsrLabRunner,
    private val vadComparisonRunner: VadComparisonRunner,
) : ViewModel() {
    private val asrState = MutableStateFlow(AsrUiState())
    private val modelsDirectory = File(applicationContext.filesDir, "models")

    val uiState: StateFlow<RecordingUiState> = combine(controller.state, asrState) { runtime, asr ->
            RecordingUiState(
                sessionId = runtime.sessionId,
                status = runtime.status,
                durationMs = runtime.durationMs,
                bytesWritten = runtime.bytesWritten,
                errorCode = runtime.errorCode,
                speechDetected = runtime.speechDetected,
                vadSegmentCount = runtime.vadSegmentCount,
                vadErrorCode = runtime.vadErrorCode,
                installedModelIds = asr.installedModelIds,
                asrRunning = asr.running,
                asrResult = asr.result,
                asrError = asr.error,
                recoverableSessions = asr.recoverableSessions,
                labSessionId = runtime.sessionId.takeIf { runtime.status == SessionStatus.COMPLETED }
                    ?: asr.completedSessions.firstOrNull()?.id,
                readErrorCount = runtime.readErrorCount,
                discontinuityCount = runtime.discontinuityCount,
                estimatedMissingFrames = runtime.estimatedMissingFrames,
                capturePipelineId = runtime.capturePipelineId,
                captureSampleRateHz = runtime.captureSampleRateHz,
                vadComparisonRunning = asr.vadComparisonRunning,
                vadComparisonResult = asr.vadComparisonResult,
                vadComparisonError = asr.vadComparisonError,
                incrementalAsrEnabled = runtime.incrementalAsrEnabled,
                incrementalModelId = runtime.incrementalModelId,
                incrementalStableText = runtime.incrementalStableText,
                incrementalUnstableText = runtime.incrementalUnstableText,
                incrementalAsrRunning = runtime.incrementalAsrRunning,
                incrementalQueueDepth = runtime.incrementalQueueDepth,
                incrementalDroppedPartialCount = runtime.incrementalDroppedPartialCount,
                incrementalPartialCount = runtime.incrementalPartialCount,
                incrementalStableConflictCount = runtime.incrementalStableConflictCount,
                incrementalTimeToFirstTextMs = runtime.incrementalTimeToFirstTextMs,
                incrementalLastVisibleLatencyMs = runtime.incrementalLastVisibleLatencyMs,
                incrementalLastRealTimeFactor = runtime.incrementalLastRealTimeFactor,
                incrementalAsrErrorCode = runtime.incrementalAsrErrorCode,
                incrementalFinalizedSegments = runtime.incrementalFinalizedSegments,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingUiState())

    init {
        refreshInstalledModels()
        refreshRecoverableSessions()
        refreshCompletedSessions()
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
        controller.start(pipeline, incrementalModelId)
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

    fun transcribe(descriptor: WhisperModelDescriptor) {
        val sessionId = uiState.value.labSessionId ?: return
        viewModelScope.launch {
            asrState.value = asrState.value.copy(running = true, result = null, error = null)
            runCatching {
                asrLabRunner.transcribeSession(
                    sessionDirectory = File(applicationContext.filesDir, "recordings/$sessionId"),
                    modelFile = File(modelsDirectory, descriptor.fileName),
                    descriptor = descriptor,
                )
            }.onSuccess { result ->
                asrState.value = asrState.value.copy(running = false, result = result)
            }.onFailure { failure ->
                asrState.value = asrState.value.copy(
                    running = false,
                    error = failure.message ?: "ASR_FAILED",
                )
            }
        }
    }

    fun recoverSession(sessionId: String) {
        controller.recover(sessionId)
        asrState.value = asrState.value.copy(
            recoverableSessions = asrState.value.recoverableSessions.filterNot { it.id == sessionId },
        )
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
        asrState.value = asrState.value.copy(installedModelIds = installed)
    }

    private fun refreshRecoverableSessions() {
        viewModelScope.launch {
            val sessions = checkpointStore.findRecoverable()
            asrState.value = asrState.value.copy(recoverableSessions = sessions)
        }
    }

    private fun refreshCompletedSessions() {
        viewModelScope.launch {
            val sessions = checkpointStore.findCompleted()
            asrState.value = asrState.value.copy(completedSessions = sessions)
        }
    }

    private data class AsrUiState(
        val installedModelIds: Set<String> = emptySet(),
        val running: Boolean = false,
        val result: AsrLabResult? = null,
        val error: String? = null,
        val recoverableSessions: List<RecordingSession> = emptyList(),
        val completedSessions: List<RecordingSession> = emptyList(),
        val vadComparisonRunning: Boolean = false,
        val vadComparisonResult: VadComparisonResult? = null,
        val vadComparisonError: String? = null,
    )
}
