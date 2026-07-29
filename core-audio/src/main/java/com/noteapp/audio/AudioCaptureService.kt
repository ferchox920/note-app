package com.noteapp.audio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.noteapp.asr.IncrementalAsrCoordinator
import com.noteapp.asr.IncrementalAsrState
import com.noteapp.asr.IncrementalInferenceResult
import com.noteapp.asr.IncrementalTranscriptSanitizer
import com.noteapp.asr.IncrementalPcmTranscriber
import com.noteapp.asr.IncrementalTranscriptStore
import com.noteapp.asr.WhisperEngine
import com.noteapp.asr.WhisperModelCatalog
import com.noteapp.domain.SessionStatus
import com.noteapp.vad.VadSegmenter
import com.noteapp.vad.VadProcessingResult
import com.noteapp.vad.VadSpeechSegment
import com.noteapp.vad.VadTimelineStore
import com.noteapp.vad.WebRtcFrameVoiceDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class AudioCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private val format = PcmFormat(sampleRateHz = SAMPLE_RATE_HZ)

    private var writer: AudioSessionWriter? = null
    private var vadSegmenter: VadSegmenter? = null
    private val vadTimelineStore = VadTimelineStore()
    private val vadSegments = mutableListOf<VadSpeechSegment>()
    private var speechDetected = false
    private var vadErrorCode: String? = null
    private var captureJob: Job? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var stoppingCapture = false
    private var foregroundStarted = false
    private var terminal = false
    private var nextCheckpointAtBytes = CHECKPOINT_INTERVAL_BYTES
    private var captureMetrics = AudioCaptureMetrics()
    private var capturePipeline = CapturePipeline.DIRECT_16_KHZ
    private var incrementalCoordinator: IncrementalAsrCoordinator? = null
    private var incrementalEngine: WhisperEngine? = null
    private var incrementalCollectorJob: Job? = null
    @Volatile private var incrementalState = IncrementalAsrState(enabled = false)
    private var incrementalModelId: String? = null
    private val incrementalTranscriptStore = IncrementalTranscriptStore()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val commandSource = intent.getStringExtra(EXTRA_COMMAND_SOURCE) ?: SOURCE_UNKNOWN
        if (action == ACTION_START || action == ACTION_RECOVER) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                RecordingRuntime.update(
                    RecordingRuntimeState(
                        status = SessionStatus.FAILED,
                        errorCode = ERROR_PERMISSION_DENIED,
                    ),
                )
                stopSelf()
                return START_NOT_STICKY
            }
            startInForeground(getString(R.string.recording_preparing), paused = false)
        }
        serviceScope.launch {
            commandMutex.withLock {
                when (action) {
                    ACTION_START -> startNewSession(
                        CapturePipeline.fromId(intent.getStringExtra(EXTRA_CAPTURE_PIPELINE)),
                        intent.getStringExtra(EXTRA_INCREMENTAL_MODEL_ID),
                        commandSource,
                    )
                    ACTION_RECOVER -> recoverSession(
                        intent.getStringExtra(EXTRA_SESSION_ID),
                        commandSource,
                    )
                    ACTION_PAUSE -> pauseSession(commandSource)
                    ACTION_RESUME -> resumeSession(commandSource)
                    ACTION_COMPLETE -> finishSession(SessionStatus.COMPLETED, commandSource)
                    ACTION_ABORT -> finishSession(SessionStatus.ABORTED, commandSource)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private suspend fun startNewSession(
        requestedPipeline: CapturePipeline,
        requestedIncrementalModelId: String?,
        commandSource: String,
    ) {
        if (writer != null || captureJob?.isActive == true) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(ERROR_PERMISSION_DENIED)
            return
        }
        terminal = false
        captureMetrics = AudioCaptureMetrics()
        capturePipeline = requestedPipeline
        val sessionId = UUID.randomUUID().toString()
        writer = AudioSessionWriter(
            rootDirectory = File(filesDir, RECORDINGS_DIRECTORY),
            sessionId = sessionId,
            format = format,
            capturePipeline = capturePipeline,
            incrementalModelId = requestedIncrementalModelId,
        )
        vadSegments.clear()
        speechDetected = false
        vadErrorCode = null
        try {
            vadSegmenter = VadSegmenter(WebRtcFrameVoiceDetector())
            persistVadTimeline()
        } catch (_: Exception) {
            vadErrorCode = ERROR_VAD_INITIALIZATION_FAILED
        } catch (_: LinkageError) {
            vadErrorCode = ERROR_VAD_INITIALIZATION_FAILED
        }
        setupIncrementalAsr(requestedIncrementalModelId)
        persistCheckpoint(SessionStatus.RECORDING)
        writer?.writeLifecycleEvent(EVENT_STARTED, SessionStatus.RECORDING, commandSource)
        nextCheckpointAtBytes = CHECKPOINT_INTERVAL_BYTES
        publish(SessionStatus.RECORDING)
        startInForeground(getString(R.string.recording_active), paused = false)
        startCapture()
    }

    private suspend fun recoverSession(sessionId: String?, commandSource: String) {
        if (writer != null || captureJob?.isActive == true) return
        if (sessionId.isNullOrBlank()) {
            fail(ERROR_RECOVERY_FAILED)
            return
        }
        terminal = false
        try {
            val recoveredWriter = AudioSessionWriter.recover(
                rootDirectory = File(filesDir, RECORDINGS_DIRECTORY),
                sessionId = sessionId,
                expectedFormat = format,
            )
            writer = recoveredWriter
            recoveredWriter.writeLifecycleEvent(
                EVENT_RECOVERY_STARTED,
                SessionStatus.RECOVERING,
                commandSource,
            )
            captureMetrics = recoveredWriter.checkpointMetrics
            capturePipeline = recoveredWriter.capturePipeline
            val timeline = runCatching {
                vadTimelineStore.read(recoveredWriter.sessionDirectory)
            }.getOrNull()
            val validTimeline = timeline?.takeIf { existing ->
                existing.processedDurationMs >= 0 &&
                    existing.processedDurationMs <= recoveredWriter.totalBytes * 1_000L / format.bytesPerSecond &&
                    existing.processedDurationMs % VAD_FRAME_DURATION_MS == 0L
            }
            vadSegments.clear()
            vadSegments += validTimeline?.segments.orEmpty()
            val initialFrames = (validTimeline?.processedDurationMs ?: 0L) / VAD_FRAME_DURATION_MS
            vadSegmenter = VadSegmenter(
                detector = WebRtcFrameVoiceDetector(),
                initialProcessedFrames = initialFrames,
                initialSequence = (vadSegments.maxOfOrNull { it.sequence } ?: -1) + 1,
                initialLastClosedEndFrame = (vadSegments.maxOfOrNull { it.endMs } ?: 0L) /
                    VAD_FRAME_DURATION_MS,
            )
            replayVadTail(recoveredWriter, initialFrames * VAD_FRAME_BYTES)
            flushVad()
            setupIncrementalAsr(recoveredWriter.incrementalModelId)
            persistCheckpoint(SessionStatus.RECOVERING)
            publish(SessionStatus.RECOVERING)
            startInForeground(getString(R.string.recording_preparing), paused = false)
            persistCheckpoint(SessionStatus.RECORDING)
            recoveredWriter.writeLifecycleEvent(
                EVENT_RECOVERED,
                SessionStatus.RECORDING,
                commandSource,
            )
            nextCheckpointAtBytes = recoveredWriter.totalBytes + CHECKPOINT_INTERVAL_BYTES
            publish(SessionStatus.RECORDING)
            startInForeground(getString(R.string.recording_active), paused = false)
            startCapture()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to recover audio session $sessionId", error)
            fail(ERROR_RECOVERY_FAILED)
        } catch (error: LinkageError) {
            Log.e(TAG, "Unable to recover audio session $sessionId", error)
            fail(ERROR_RECOVERY_FAILED)
        }
    }

    private suspend fun setupIncrementalAsr(modelId: String?) {
        releaseIncrementalAsr(drain = false)
        incrementalModelId = modelId
        if (modelId.isNullOrBlank()) {
            incrementalState = IncrementalAsrState(enabled = false)
            return
        }
        val descriptor = WhisperModelCatalog.evaluationModels.firstOrNull { it.id == modelId }
        if (descriptor == null) {
            incrementalState = IncrementalAsrState(
                enabled = false,
                errorCode = ERROR_INCREMENTAL_MODEL_UNKNOWN,
            )
            return
        }
        val engine = runCatching {
            WhisperEngine.create(
                modelFile = File(filesDir, "models/${descriptor.fileName}"),
                descriptor = descriptor,
            )
        }.getOrElse {
            incrementalState = IncrementalAsrState(
                enabled = false,
                errorCode = ERROR_INCREMENTAL_MODEL_UNAVAILABLE,
            )
            return
        }
        incrementalEngine = engine
        val restoredIncrementalState = writer?.sessionDirectory?.let { sessionDirectory ->
            runCatching { incrementalTranscriptStore.read(sessionDirectory) }.getOrNull()
        }
        val coordinator = IncrementalAsrCoordinator(
            scope = serviceScope,
            transcriber = IncrementalPcmTranscriber { pcm16, offsetMs ->
                val result = engine.transcribePcm16(
                    pcm = pcm16,
                    offsetMs = offsetMs,
                    language = "es",
                    lowLatency = true,
                )
                val sanitized = IncrementalTranscriptSanitizer.inspect(
                    result.segments.joinToString(" ") { it.text.trim() }.trim(),
                )
                IncrementalInferenceResult(
                    text = sanitized.text,
                    inferenceDurationMs = result.inferenceDurationMs,
                    realTimeFactor = result.realTimeFactor,
                    suppressedRepetition = sanitized.suppressedRepetition,
                    nativeTimings = result.nativeTimings,
                )
            },
            initialState = restoredIncrementalState ?: IncrementalAsrState(),
        )
        incrementalCoordinator = coordinator
        if (vadSegmenter == null) coordinator.reportError(ERROR_INCREMENTAL_VAD_REQUIRED)
        incrementalState = coordinator.state.value
        incrementalCollectorJob = serviceScope.launch {
            var lastPersistedSegmentCount = -1
            coordinator.state.collect { state ->
                incrementalState = state
                if (state.finalizedSegments.size != lastPersistedSegmentCount) {
                    persistIncrementalTranscript()
                    lastPersistedSegmentCount = state.finalizedSegments.size
                }
                val status = RecordingRuntime.state.value.status
                if (status != SessionStatus.NEW) publish(status)
            }
        }
    }

    private suspend fun releaseIncrementalAsr(drain: Boolean) {
        incrementalCoordinator?.shutdown(drain)
        incrementalState = incrementalCoordinator?.state?.value ?: incrementalState
        incrementalCollectorJob?.cancelAndJoin()
        incrementalCollectorJob = null
        persistIncrementalTranscript()
        incrementalCoordinator = null
        incrementalEngine?.release()
        incrementalEngine = null
    }

    private fun persistIncrementalTranscript() {
        val activeWriter = writer ?: return
        val modelId = incrementalModelId ?: return
        runCatching {
            incrementalTranscriptStore.write(
                sessionDirectory = activeWriter.sessionDirectory,
                modelId = modelId,
                capturePipelineId = capturePipeline.id,
                state = incrementalState,
            )
        }
    }

    private fun replayVadTail(recoveredWriter: AudioSessionWriter, startByteOffset: Long) {
        var logicalOffset = 0L
        val buffer = ByteArray(READ_BUFFER_BYTES)
        recoveredWriter.completedSegments.forEach { metadata ->
            val segmentStart = logicalOffset
            val segmentEnd = segmentStart + metadata.byteCount
            logicalOffset = segmentEnd
            if (segmentEnd <= startByteOffset) return@forEach
            val file = File(recoveredWriter.sessionDirectory, metadata.fileName)
            file.inputStream().buffered().use { input ->
                var bytesToSkip = maxOf(0L, startByteOffset - segmentStart)
                while (bytesToSkip > 0) {
                    val skipped = input.skip(bytesToSkip)
                    check(skipped > 0) { "Unable to seek recovered PCM" }
                    bytesToSkip -= skipped
                }
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) processVad(buffer, read)
                }
            }
        }
    }

    private suspend fun pauseSession(commandSource: String) {
        if (RecordingRuntime.state.value.status != SessionStatus.RECORDING) return
        stopCapture()
        writer?.closeSegment()
        flushVad()
        persistCheckpoint(SessionStatus.PAUSED)
        writer?.writeLifecycleEvent(EVENT_PAUSED, SessionStatus.PAUSED, commandSource)
        publish(SessionStatus.PAUSED)
        startInForeground(getString(R.string.recording_paused), paused = true)
    }

    @SuppressLint("MissingPermission")
    private fun resumeSession(commandSource: String) {
        if (RecordingRuntime.state.value.status != SessionStatus.PAUSED) return
        persistCheckpoint(SessionStatus.RECORDING)
        writer?.writeLifecycleEvent(EVENT_RESUMED, SessionStatus.RECORDING, commandSource)
        nextCheckpointAtBytes = (writer?.totalBytes ?: 0L) + CHECKPOINT_INTERVAL_BYTES
        publish(SessionStatus.RECORDING)
        startInForeground(getString(R.string.recording_active), paused = false)
        startCapture()
    }

    private suspend fun finishSession(status: SessionStatus, commandSource: String) {
        if (writer == null) return
        stopCapture()
        writer?.closeSegment()
        flushVad()
        releaseIncrementalAsr(drain = status == SessionStatus.COMPLETED)
        persistCheckpoint(status)
        writer?.writeLifecycleEvent(
            if (status == SessionStatus.COMPLETED) EVENT_COMPLETED else EVENT_ABORTED,
            status,
            commandSource,
        )
        releaseVad()
        terminal = true
        publish(status)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun startCapture() {
        val minimum = AudioRecord.getMinBufferSize(
            capturePipeline.captureSampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            failAsync(ERROR_UNSUPPORTED_FORMAT)
            return
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            capturePipeline.captureSampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minimum * 2, minimumBufferBytes(capturePipeline.captureSampleRateHz)),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            failAsync(ERROR_INITIALIZATION_FAILED)
            return
        }
        val sink = writer?.openSegment() ?: run {
            recorder.release()
            failAsync(ERROR_SESSION_NOT_READY)
            return
        }
        audioRecord = recorder
        stoppingCapture = false
        captureJob = serviceScope.launch {
            val buffer = ByteArray(max(minimum, readBufferBytes(capturePipeline.captureSampleRateHz)))
            val resampler = if (capturePipeline == CapturePipeline.NATIVE_48_KHZ_TO_16_KHZ) {
                Pcm16ThreeToOneResampler()
            } else {
                null
            }
            val captureSilenced = AtomicBoolean(false)
            val recordingCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                        val activeConfiguration = configs.firstOrNull {
                            it.clientAudioSessionId == recorder.audioSessionId
                        }
                        if (activeConfiguration?.isClientSilenced == true) {
                            captureSilenced.set(true)
                        }
                    }
                }.also { callback ->
                    recorder.registerAudioRecordingCallback(mainExecutor, callback)
                }
            } else {
                null
            }
            val timestamp = AudioTimestamp()
            var lastTimestampFrame: Long? = null
            var lastTimestampWrittenFrames = 0L
            var capturedInputFrames = 0L
            var stopServiceAfterCapture = false
            try {
                recorder.startRecording()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (captureSilenced.get()) {
                        throw AudioCaptureException(ERROR_AUDIO_CLIENT_SILENCED)
                    }
                    val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    when {
                        read > 0 -> {
                            if (read % PCM16_BYTES_PER_SAMPLE != 0) {
                                captureMetrics = captureMetrics.copy(readErrorCount = captureMetrics.readErrorCount + 1)
                                throw AudioCaptureException(ERROR_READ_ALIGNMENT)
                            }
                            capturedInputFrames += read / PCM16_BYTES_PER_SAMPLE
                            val normalized = resampler?.process(buffer, 0, read)
                            if (normalized == null) {
                                processNormalizedPcm(sink, buffer, read)
                            } else if (normalized.isNotEmpty()) {
                                processNormalizedPcm(sink, normalized, normalized.size)
                            }
                            sampleAudioContinuity(
                                recorder = recorder,
                                timestamp = timestamp,
                                toleranceFrames = buffer.size / PCM16_BYTES_PER_SAMPLE,
                                lastTimestampFrame = lastTimestampFrame,
                                lastTimestampWrittenFrames = lastTimestampWrittenFrames,
                                capturedInputFrames = capturedInputFrames,
                            )?.let { sample ->
                                lastTimestampFrame = sample.framePosition
                                lastTimestampWrittenFrames = sample.writtenFrames
                            }
                            maybeWritePeriodicCheckpoint()
                            publish(SessionStatus.RECORDING)
                        }
                        read == AudioRecord.ERROR_DEAD_OBJECT && !stoppingCapture -> {
                            captureMetrics = captureMetrics.copy(readErrorCount = captureMetrics.readErrorCount + 1)
                            throw AudioCaptureException(ERROR_AUDIO_DEAD_OBJECT)
                        }
                        read < 0 && !stoppingCapture -> {
                            captureMetrics = captureMetrics.copy(readErrorCount = captureMetrics.readErrorCount + 1)
                            throw AudioCaptureException(ERROR_AUDIO_READ_FAILED)
                        }
                        read < 0 -> break
                        else -> delay(NON_BLOCKING_READ_RETRY_MS)
                    }
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (error: AudioCaptureException) {
                finishUnexpectedCapture(error.code)
                stopServiceAfterCapture = true
            } catch (_: SecurityException) {
                finishUnexpectedCapture(ERROR_PERMISSION_DENIED)
                stopServiceAfterCapture = true
            } finally {
                if (recordingCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    recorder.unregisterAudioRecordingCallback(recordingCallback)
                }
                runCatching { recorder.stop() }
                recorder.release()
                if (audioRecord === recorder) audioRecord = null
            }
            if (stopServiceAfterCapture) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun finishUnexpectedCapture(errorCode: String) {
        val recoverable = captureFailureDisposition(errorCode) == CaptureFailureDisposition.RECOVERABLE
        if (recoverable) {
            captureMetrics = captureMetrics.copy(
                discontinuityCount = captureMetrics.discontinuityCount + 1,
            )
        }
        writer?.closeSegment()
        flushVad()
        releaseIncrementalAsr(drain = false)
        val status = if (recoverable) SessionStatus.RECOVERING else SessionStatus.FAILED
        persistCheckpoint(status, errorCode)
        writer?.writeLifecycleEvent(
            if (recoverable) EVENT_INTERRUPTED else EVENT_FAILED,
            status,
            if (recoverable) SOURCE_SYSTEM else SOURCE_RUNTIME,
            errorCode,
        )
        releaseVad()
        terminal = true
        publish(status, errorCode)
    }

    private suspend fun stopCapture() {
        stoppingCapture = true
        // AudioRecord.stop() may block when another thread is inside a blocking read
        // on physical Samsung devices. The capture loop uses non-blocking reads, so
        // cancellation lets its finally block stop and release the recorder from the
        // same coroutine that owns it.
        captureJob?.cancelAndJoin()
        captureJob = null
        audioRecord = null
    }

    private suspend fun fail(errorCode: String) {
        runCatching { audioRecord?.stop() }
        captureJob?.cancel()
        writer?.closeSegment()
        flushVad()
        releaseIncrementalAsr(drain = false)
        persistCheckpoint(SessionStatus.FAILED, errorCode)
        writer?.writeLifecycleEvent(
            EVENT_FAILED,
            SessionStatus.FAILED,
            SOURCE_RUNTIME,
            errorCode,
        )
        releaseVad()
        terminal = true
        publish(SessionStatus.FAILED, errorCode)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failAsync(errorCode: String) {
        serviceScope.launch { commandMutex.withLock { fail(errorCode) } }
    }

    private fun publish(status: SessionStatus, errorCode: String? = null) {
        val activeWriter = writer
        RecordingRuntime.update(
            RecordingRuntimeState(
                sessionId = activeWriter?.sessionId,
                status = status,
                durationMs = activeWriter?.let { it.totalBytes * 1_000L / format.bytesPerSecond } ?: 0,
                bytesWritten = activeWriter?.totalBytes ?: 0,
                errorCode = errorCode,
                speechDetected = speechDetected,
                vadSegmentCount = vadSegments.size,
                vadErrorCode = vadErrorCode,
                readErrorCount = captureMetrics.readErrorCount,
                discontinuityCount = captureMetrics.discontinuityCount,
                estimatedMissingFrames = captureMetrics.estimatedMissingFrames,
                capturePipelineId = capturePipeline.id,
                captureSampleRateHz = capturePipeline.captureSampleRateHz,
                incrementalAsrEnabled = incrementalState.enabled,
                incrementalModelId = incrementalModelId,
                incrementalStableText = incrementalState.stableText,
                incrementalUnstableText = incrementalState.unstableText,
                incrementalAsrRunning = incrementalState.running,
                incrementalQueueDepth = incrementalState.queueDepth,
                incrementalDroppedPartialCount = incrementalState.droppedPartialCount,
                incrementalPartialCount = incrementalState.partialCount,
                incrementalStableConflictCount = incrementalState.stableConflictCount,
                incrementalSuppressedRepetitionCount = incrementalState.suppressedRepetitionCount,
                incrementalTimeToFirstTextMs = incrementalState.timeToFirstTextMs,
                incrementalLastVisibleLatencyMs = incrementalState.lastVisibleLatencyMs,
                incrementalLastRealTimeFactor = incrementalState.lastRealTimeFactor,
                incrementalAsrErrorCode = incrementalState.errorCode,
                incrementalFinalizedSegments = incrementalState.finalizedSegments,
            ),
        )
    }

    private fun processNormalizedPcm(
        sink: AudioSessionWriter.SegmentSink,
        buffer: ByteArray,
        length: Int,
    ) {
        sink.write(buffer, 0, length)
        val vadResult = processVad(buffer, length) ?: return
        incrementalCoordinator?.onPcm16(
            pcm16 = buffer,
            length = length,
            speechActive = vadResult.speechActive,
            endpointDetected = vadResult.closedSegments.isNotEmpty(),
            streamEndMs = vadResult.processedDurationMs,
        )
    }

    private fun processVad(buffer: ByteArray, length: Int): VadProcessingResult? {
        val segmenter = vadSegmenter ?: return null
        try {
            val result = segmenter.process(buffer, 0, length)
            speechDetected = result.speechActive
            if (result.closedSegments.isNotEmpty()) {
                vadSegments += result.closedSegments
                persistVadTimeline()
            }
            return result
        } catch (_: Exception) {
            disableVad(ERROR_VAD_PROCESSING_FAILED)
        } catch (_: LinkageError) {
            disableVad(ERROR_VAD_PROCESSING_FAILED)
        }
        return null
    }

    private fun maybeWritePeriodicCheckpoint() {
        val activeWriter = writer ?: return
        if (activeWriter.totalBytes < nextCheckpointAtBytes) return
        persistCheckpoint(SessionStatus.RECORDING)
        nextCheckpointAtBytes = activeWriter.totalBytes + CHECKPOINT_INTERVAL_BYTES
    }

    private fun persistCheckpoint(status: SessionStatus, errorCode: String? = null) {
        writer?.writeCheckpoint(status, errorCode, captureMetrics)
    }

    private fun sampleAudioContinuity(
        recorder: AudioRecord,
        timestamp: AudioTimestamp,
        toleranceFrames: Int,
        lastTimestampFrame: Long?,
        lastTimestampWrittenFrames: Long,
        capturedInputFrames: Long,
    ): AudioContinuitySample? {
        if (recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) {
            return null
        }
        val framePosition = timestamp.framePosition
        val writtenFrames = capturedInputFrames
        if (lastTimestampFrame != null && framePosition > lastTimestampFrame) {
            val hardwareDelta = framePosition - lastTimestampFrame
            val writtenDelta = writtenFrames - lastTimestampWrittenFrames
            if (hardwareDelta > writtenDelta + toleranceFrames) {
                captureMetrics = captureMetrics.copy(
                    discontinuityCount = captureMetrics.discontinuityCount + 1,
                    estimatedMissingFrames = captureMetrics.estimatedMissingFrames + hardwareDelta - writtenDelta,
                )
            }
        }
        return AudioContinuitySample(framePosition, writtenFrames)
    }

    private fun flushVad() {
        val segmenter = vadSegmenter ?: return
        try {
            val result = segmenter.endCurrentStream()
            speechDetected = false
            if (result.closedSegments.isNotEmpty()) vadSegments += result.closedSegments
            incrementalCoordinator?.endSegment(result.processedDurationMs)
            persistVadTimeline()
        } catch (_: Exception) {
            disableVad(ERROR_VAD_PROCESSING_FAILED)
        } catch (_: LinkageError) {
            disableVad(ERROR_VAD_PROCESSING_FAILED)
        }
    }

    private fun persistVadTimeline() {
        val activeWriter = writer ?: return
        val segmenter = vadSegmenter ?: return
        vadTimelineStore.write(
            sessionDirectory = activeWriter.sessionDirectory,
            sessionId = activeWriter.sessionId,
            segments = vadSegments,
            processedDurationMs = segmenter.processedDurationMs,
            capturePipelineId = capturePipeline.id,
        )
    }

    private fun disableVad(errorCode: String) {
        vadErrorCode = errorCode
        speechDetected = false
        incrementalCoordinator?.reportError(ERROR_INCREMENTAL_VAD_REQUIRED)
        runCatching { vadSegmenter?.close() }
        vadSegmenter = null
    }

    private fun releaseVad() {
        runCatching { vadSegmenter?.close() }
        vadSegmenter = null
        speechDetected = false
    }

    private fun startInForeground(content: String, paused: Boolean) {
        val notification = createNotification(content, paused)
        if (foregroundStarted) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            foregroundStarted = true
        } else {
            startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
        }
    }

    private fun createNotification(content: String, paused: Boolean): Notification {
        val toggleAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val toggleLabel = if (paused) R.string.recording_action_resume else R.string.recording_action_pause
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(com.noteapp.audio.R.drawable.ic_recording_notification)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(toggleLabel),
                    servicePendingIntent(toggleAction, if (paused) 2 else 1),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.recording_action_finish),
                    servicePendingIntent(ACTION_COMPLETE, 3),
                ).build(),
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, AudioCaptureService::class.java)
            .setAction(action)
            .putExtra(EXTRA_COMMAND_SOURCE, SOURCE_NOTIFICATION)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        if (!terminal && writer != null) {
            runBlocking(Dispatchers.IO) {
                stopCapture()
                writer?.closeSegment()
                flushVad()
                releaseIncrementalAsr(drain = false)
                persistCheckpoint(SessionStatus.RECOVERING)
                writer?.writeLifecycleEvent(
                    EVENT_INTERRUPTED,
                    SessionStatus.RECOVERING,
                    SOURCE_SYSTEM,
                )
                releaseVad()
                publish(SessionStatus.RECOVERING)
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private class AudioCaptureException(val code: String) : RuntimeException()
    private data class AudioContinuitySample(val framePosition: Long, val writtenFrames: Long)

    companion object {
        private const val TAG = "AudioCaptureService"
        const val ACTION_START = "com.noteapp.audio.action.START"
        const val ACTION_RECOVER = "com.noteapp.audio.action.RECOVER"
        const val ACTION_PAUSE = "com.noteapp.audio.action.PAUSE"
        const val ACTION_RESUME = "com.noteapp.audio.action.RESUME"
        const val ACTION_COMPLETE = "com.noteapp.audio.action.COMPLETE"
        const val ACTION_ABORT = "com.noteapp.audio.action.ABORT"
        const val EXTRA_SESSION_ID = "com.noteapp.audio.extra.SESSION_ID"
        const val EXTRA_CAPTURE_PIPELINE = "com.noteapp.audio.extra.CAPTURE_PIPELINE"
        const val EXTRA_INCREMENTAL_MODEL_ID = "com.noteapp.audio.extra.INCREMENTAL_MODEL_ID"
        const val EXTRA_COMMAND_SOURCE = "com.noteapp.audio.extra.COMMAND_SOURCE"
        const val SOURCE_UI = "ui"
        const val SOURCE_NOTIFICATION = "notification"
        const val SOURCE_SYSTEM = "system"
        const val SOURCE_RUNTIME = "runtime"
        private const val SOURCE_UNKNOWN = "unknown"

        private const val EVENT_STARTED = "STARTED"
        private const val EVENT_PAUSED = "PAUSED"
        private const val EVENT_RESUMED = "RESUMED"
        private const val EVENT_RECOVERY_STARTED = "RECOVERY_STARTED"
        private const val EVENT_RECOVERED = "RECOVERED"
        private const val EVENT_COMPLETED = "COMPLETED"
        private const val EVENT_ABORTED = "ABORTED"
        private const val EVENT_FAILED = "FAILED"
        private const val EVENT_INTERRUPTED = "INTERRUPTED"

        private const val SAMPLE_RATE_HZ = 16_000
        private const val MINIMUM_BUFFER_BYTES = 6_400
        private const val READ_BUFFER_BYTES = 3_200
        private const val NON_BLOCKING_READ_RETRY_MS = 5L
        private const val VAD_FRAME_BYTES = 640L
        private const val VAD_FRAME_DURATION_MS = 20L
        private const val PCM16_BYTES_PER_SAMPLE = 2
        private const val CHECKPOINT_INTERVAL_BYTES = 320_000L // 10 s at PCM16 mono 16 kHz
        private const val RECORDINGS_DIRECTORY = "recordings"
        private const val NOTIFICATION_CHANNEL_ID = "active_recording"
        private const val NOTIFICATION_ID = 1001
        private const val ERROR_INCREMENTAL_MODEL_UNKNOWN = "INCREMENTAL_ASR_MODEL_UNKNOWN"
        private const val ERROR_INCREMENTAL_MODEL_UNAVAILABLE = "INCREMENTAL_ASR_MODEL_UNAVAILABLE"
        private const val ERROR_INCREMENTAL_VAD_REQUIRED = "INCREMENTAL_ASR_VAD_REQUIRED"

        private const val ERROR_PERMISSION_DENIED = "AUDIO_PERMISSION_DENIED"
        private const val ERROR_UNSUPPORTED_FORMAT = "AUDIO_FORMAT_UNSUPPORTED"
        private const val ERROR_INITIALIZATION_FAILED = "AUDIO_INITIALIZATION_FAILED"
        private const val ERROR_SESSION_NOT_READY = "AUDIO_SESSION_NOT_READY"
        private const val ERROR_VAD_INITIALIZATION_FAILED = "VAD_INITIALIZATION_FAILED"
        private const val ERROR_VAD_PROCESSING_FAILED = "VAD_PROCESSING_FAILED"
        private const val ERROR_RECOVERY_FAILED = "AUDIO_RECOVERY_FAILED"
        private const val ERROR_READ_ALIGNMENT = "AUDIO_READ_ALIGNMENT_FAILED"

        private fun minimumBufferBytes(sampleRateHz: Int): Int =
            MINIMUM_BUFFER_BYTES * sampleRateHz / SAMPLE_RATE_HZ

        private fun readBufferBytes(sampleRateHz: Int): Int =
            READ_BUFFER_BYTES * sampleRateHz / SAMPLE_RATE_HZ
    }
}
