package com.noteapp.audio

import com.noteapp.domain.SessionStatus
import com.noteapp.security.ArtifactAppendSink
import com.noteapp.security.SessionArtifactStore
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class PcmSegmentMetadata(
    val sequence: Int,
    val fileName: String,
    val startByteOffset: Long,
    val endByteOffset: Long,
    val byteCount: Long,
    val sha256: String,
)

data class AudioCaptureMetrics(
    val readErrorCount: Int = 0,
    val discontinuityCount: Int = 0,
    val estimatedMissingFrames: Long = 0,
)

class AudioSessionWriter(
    rootDirectory: File,
    val sessionId: String,
    val format: PcmFormat,
    val capturePipeline: CapturePipeline = CapturePipeline.DIRECT_16_KHZ,
    val incrementalModelId: String? = null,
    restoredSegments: List<PcmSegmentMetadata> = emptyList(),
    restoredMetrics: AudioCaptureMetrics = AudioCaptureMetrics(),
    private val artifactStore: SessionArtifactStore,
) {
    val sessionDirectory: File = File(rootDirectory, sessionId).apply {
        check(exists() || mkdirs()) { "Unable to create session directory" }
    }

    private val segments = restoredSegments.toMutableList()
    private var activeSink: SegmentSink? = null
    private var nextLifecycleEventSequence = existingLifecycleEventCount()
    var checkpointMetrics: AudioCaptureMetrics = restoredMetrics
        private set

    val completedSegments: List<PcmSegmentMetadata>
        get() = segments.toList()

    val totalBytes: Long
        get() = segments.sumOf(PcmSegmentMetadata::byteCount) + (activeSink?.byteCount ?: 0)

    fun openSegment(): SegmentSink {
        check(activeSink == null) { "A segment is already open" }
        val sequence = segments.size
        val file = File(sessionDirectory, "segment-${sequence.toString().padStart(4, '0')}.pcm")
        return SegmentSink(sequence, file).also { activeSink = it }
    }

    fun closeSegment(): PcmSegmentMetadata? = activeSink?.let { sink ->
        sink.close()
        val startByteOffset = segments.sumOf(PcmSegmentMetadata::byteCount)
        PcmSegmentMetadata(
            sequence = sink.sequence,
            fileName = sink.file.name,
            startByteOffset = startByteOffset,
            endByteOffset = startByteOffset + sink.byteCount,
            byteCount = sink.byteCount,
            sha256 = sink.digestHex,
        ).also {
            segments += it
            activeSink = null
        }
    }

    fun writeCheckpoint(
        status: SessionStatus,
        errorCode: String? = null,
        metrics: AudioCaptureMetrics = checkpointMetrics,
    ) {
        activeSink?.sync()
        checkpointMetrics = metrics
        val durationMs = totalBytes * 1_000L / format.bytesPerSecond
        val segmentJson = segments.joinToString(separator = ",") { segment ->
            """{"sequence":${segment.sequence},"fileName":"${segment.fileName}","startByteOffset":${segment.startByteOffset},"endByteOffset":${segment.endByteOffset},"byteCount":${segment.byteCount},"sha256":"${segment.sha256}"}"""
        }
        val errorJson = errorCode?.let { "\"$it\"" } ?: "null"
        val incrementalModelJson = incrementalModelId?.let { "\"$it\"" } ?: "null"
        val json = """{"schemaVersion":1,"sessionId":"$sessionId","status":"${status.name}","capturePipeline":"${capturePipeline.id}","captureSampleRateHz":${capturePipeline.captureSampleRateHz},"sampleRateHz":${format.sampleRateHz},"channelCount":${format.channelCount},"bitsPerSample":${format.bitsPerSample},"durationMs":$durationMs,"totalBytes":$totalBytes,"incrementalModelId":$incrementalModelJson,"readErrorCount":${metrics.readErrorCount},"discontinuityCount":${metrics.discontinuityCount},"estimatedMissingFrames":${metrics.estimatedMissingFrames},"errorCode":$errorJson,"segments":[$segmentJson]}"""
        val target = File(sessionDirectory, CHECKPOINT_FILE)
        artifactStore.writeTextAtomically(target, json)
    }

    fun writeLifecycleEvent(
        event: String,
        status: SessionStatus,
        source: String,
        errorCode: String? = null,
    ) {
        require(event.matches(Regex("[A-Z_]+"))) { "Invalid lifecycle event" }
        require(source.matches(Regex("[a-z-]+"))) { "Invalid lifecycle event source" }
        val sequence = nextLifecycleEventSequence
        val eventsDirectory = File(sessionDirectory, LIFECYCLE_EVENTS_DIRECTORY).apply {
            check(exists() || mkdirs()) { "Unable to create lifecycle events directory" }
        }
        val fileName = "event-${sequence.toString().padStart(4, '0')}.json"
        val target = File(eventsDirectory, fileName)
        val durationMs = totalBytes * 1_000L / format.bytesPerSecond
        val errorJson = errorCode?.let(::jsonString) ?: "null"
        val json = """{"schemaVersion":1,"sequence":$sequence,"sessionId":${jsonString(sessionId)},"event":${jsonString(event)},"status":"${status.name}","source":${jsonString(source)},"observedAtEpochMs":${System.currentTimeMillis()},"observedAtMonotonicMs":${System.nanoTime() / 1_000_000L},"audioDurationMs":$durationMs,"totalBytes":$totalBytes,"errorCode":$errorJson}"""
        artifactStore.writeTextAtomically(target, json)
        nextLifecycleEventSequence += 1
    }

    private fun existingLifecycleEventCount(): Int {
        val directory = File(sessionDirectory, LIFECYCLE_EVENTS_DIRECTORY)
        if (!directory.isDirectory) return 0
        val files = directory.listFiles { file ->
            file.isFile && file.name.matches(Regex("event-\\d{4}\\.json"))
        }.orEmpty().sortedBy { it.name }
        files.forEachIndexed { index, file ->
            require(file.name == "event-${index.toString().padStart(4, '0')}.json") {
                "Non-contiguous lifecycle event sequence"
            }
        }
        return files.size
    }

    inner class SegmentSink internal constructor(
        internal val sequence: Int,
        internal val file: File,
    ) : Closeable {
        private val output: ArtifactAppendSink = artifactStore.openAppend(file)
        private val digest = MessageDigest.getInstance("SHA-256")
        private var closed = false

        var byteCount: Long = 0
            private set

        internal val digestHex: String
            get() = digest.digest().joinToString("") { byte ->
                String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
            }

        fun write(buffer: ByteArray, offset: Int, length: Int) {
            check(!closed) { "Segment is closed" }
            output.write(buffer, offset, length)
            digest.update(buffer, offset, length)
            byteCount += length
        }

        internal fun sync() {
            check(!closed) { "Segment is closed" }
            output.sync()
        }

        override fun close() {
            if (closed) return
            sync()
            output.close()
            closed = true
        }
    }

    companion object {
        const val CHECKPOINT_FILE = "checkpoint.json"
        const val LIFECYCLE_EVENTS_DIRECTORY = "lifecycle-events"

        fun recover(
            rootDirectory: File,
            sessionId: String,
            expectedFormat: PcmFormat,
            artifactStore: SessionArtifactStore,
        ): AudioSessionWriter {
            val sessionDirectory = File(rootDirectory, sessionId)
            val checkpoint = File(sessionDirectory, CHECKPOINT_FILE)
            require(checkpoint.isFile) { "Recovery checkpoint is missing" }
            val json = artifactStore.readText(checkpoint)
            require(stringField(json, "sessionId") == sessionId) { "Session ID mismatch" }
            require(longField(json, "sampleRateHz") == expectedFormat.sampleRateHz.toLong()) {
                "Sample rate mismatch"
            }
            require(longField(json, "channelCount") == expectedFormat.channelCount.toLong()) {
                "Channel count mismatch"
            }
            require(longField(json, "bitsPerSample") == expectedFormat.bitsPerSample.toLong()) {
                "Bit depth mismatch"
            }

            val restored = segmentObjects(json).map { segmentJson ->
                PcmSegmentMetadata(
                    sequence = longField(segmentJson, "sequence").toInt(),
                    fileName = requireNotNull(stringField(segmentJson, "fileName")),
                    startByteOffset = longField(segmentJson, "startByteOffset"),
                    endByteOffset = longField(segmentJson, "endByteOffset"),
                    byteCount = longField(segmentJson, "byteCount"),
                    sha256 = requireNotNull(stringField(segmentJson, "sha256")),
                )
            }.toMutableList()
            validateAndAppendOrphans(sessionDirectory, restored, artifactStore)
            val metrics = AudioCaptureMetrics(
                readErrorCount = optionalLongField(json, "readErrorCount").toInt(),
                discontinuityCount = optionalLongField(json, "discontinuityCount").toInt(),
                estimatedMissingFrames = optionalLongField(json, "estimatedMissingFrames"),
            )
            val capturePipeline = CapturePipeline.fromId(stringField(json, "capturePipeline"))
            require(capturePipeline.outputSampleRateHz == expectedFormat.sampleRateHz) {
                "Recovered capture pipeline output format mismatch"
            }
            return AudioSessionWriter(
                rootDirectory = rootDirectory,
                sessionId = sessionId,
                format = expectedFormat,
                capturePipeline = capturePipeline,
                incrementalModelId = stringField(json, "incrementalModelId"),
                restoredSegments = restored,
                restoredMetrics = metrics,
                artifactStore = artifactStore,
            )
        }

        private fun validateAndAppendOrphans(
            sessionDirectory: File,
            restored: MutableList<PcmSegmentMetadata>,
            artifactStore: SessionArtifactStore,
        ) {
            var expectedOffset = 0L
            restored.sortedBy { it.sequence }.forEachIndexed { index, metadata ->
                require(metadata.sequence == index) { "Non-contiguous segment sequence" }
                require(metadata.startByteOffset == expectedOffset) { "Invalid segment offset" }
                require(metadata.endByteOffset == expectedOffset + metadata.byteCount) {
                    "Invalid segment length metadata"
                }
                val file = File(sessionDirectory, metadata.fileName)
                require(file.isFile && artifactStore.plaintextSize(file) == metadata.byteCount) {
                    "PCM segment size mismatch"
                }
                require(sha256(file, artifactStore) == metadata.sha256) {
                    "PCM segment checksum mismatch"
                }
                expectedOffset = metadata.endByteOffset
            }

            val allPcm = sessionDirectory.listFiles { file ->
                file.isFile && file.name.matches(Regex("segment-\\d{4}\\.pcm"))
            }.orEmpty().sortedBy { it.name }
            allPcm.drop(restored.size).forEachIndexed { orphanIndex, file ->
                val expectedSequence = restored.size
                require(file.name == "segment-${expectedSequence.toString().padStart(4, '0')}.pcm") {
                    "Unexpected PCM segment during recovery"
                }
                val byteCount = artifactStore.plaintextSize(file)
                require(byteCount > 0 && byteCount % 2L == 0L) {
                    "Incomplete PCM segment during recovery"
                }
                restored += PcmSegmentMetadata(
                    sequence = expectedSequence,
                    fileName = file.name,
                    startByteOffset = expectedOffset,
                    endByteOffset = expectedOffset + byteCount,
                    byteCount = byteCount,
                    sha256 = sha256(file, artifactStore),
                )
                expectedOffset += byteCount
            }
        }

        private fun stringField(json: String, name: String): String? =
            Regex("\\\"${Regex.escape(name)}\\\":\\\"([^\\\"]*)\\\"").find(json)?.groupValues?.get(1)

        private fun longField(json: String, name: String): Long =
            requireNotNull(Regex("\\\"${Regex.escape(name)}\\\":(-?\\d+)").find(json)) {
                "Missing numeric field $name"
            }.groupValues[1].toLong()

        private fun optionalLongField(json: String, name: String): Long =
            Regex("\\\"${Regex.escape(name)}\\\":(-?\\d+)").find(json)?.groupValues?.get(1)?.toLong() ?: 0L

        private fun segmentObjects(json: String): List<String> {
            // Avoid host-JVM/Android-ICU regex differences while reading the
            // controlled checkpoint format written above.
            val prefix = "\"segments\":["
            val bodyStart = json.indexOf(prefix).takeIf { it >= 0 }?.plus(prefix.length)
                ?: error("Missing segments array")
            val bodyEnd = json.indexOf(']', bodyStart).takeIf { it >= bodyStart }
                ?: error("Unterminated segments array")
            val body = json.substring(bodyStart, bodyEnd)
            if (body.isBlank()) return emptyList()
            val objects = mutableListOf<String>()
            var objectStart = -1
            var depth = 0
            body.forEachIndexed { index, character ->
                when (character) {
                    '{' -> {
                        if (depth == 0) objectStart = index
                        depth += 1
                    }
                    '}' -> {
                        require(depth > 0) { "Unexpected segment object terminator" }
                        depth -= 1
                        if (depth == 0) {
                            objects += body.substring(objectStart, index + 1)
                            objectStart = -1
                        }
                    }
                }
            }
            require(depth == 0 && objectStart == -1) { "Unterminated segment object" }
            return objects
        }

        private fun sha256(
            file: File,
            artifactStore: SessionArtifactStore,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            artifactStore.openInput(file).buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte ->
                String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
            }
        }

        private fun jsonString(value: String): String = buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }
    }
}
