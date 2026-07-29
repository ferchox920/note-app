package com.noteapp.audio

import com.noteapp.domain.SessionStatus
import com.noteapp.asr.IncrementalTranscriptSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingRuntimeState(
    val sessionId: String? = null,
    val status: SessionStatus = SessionStatus.NEW,
    val durationMs: Long = 0,
    val bytesWritten: Long = 0,
    val errorCode: String? = null,
    val speechDetected: Boolean = false,
    val vadSegmentCount: Int = 0,
    val vadErrorCode: String? = null,
    val readErrorCount: Int = 0,
    val discontinuityCount: Int = 0,
    val estimatedMissingFrames: Long = 0,
    val capturePipelineId: String = CapturePipeline.DIRECT_16_KHZ.id,
    val captureSampleRateHz: Int = CapturePipeline.DIRECT_16_KHZ.captureSampleRateHz,
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
)

object RecordingRuntime {
    private val mutableState = MutableStateFlow(RecordingRuntimeState())
    val state: StateFlow<RecordingRuntimeState> = mutableState.asStateFlow()

    internal fun update(state: RecordingRuntimeState) {
        mutableState.value = state
    }
}
