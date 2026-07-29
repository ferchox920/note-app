package com.noteapp.recording

import com.noteapp.asr.AsrLabResult
import com.noteapp.asr.SherpaStreamingLabResult
import com.noteapp.storage.PersistedProcessingMetric

internal const val WHISPER_ASR_JOB = "WHISPER_ASR_POST_PROCESS"
internal const val SHERPA_REPLAY_JOB = "SHERPA_STREAMING_REPLAY"

internal fun AsrLabResult.toProcessingMetrics(): List<PersistedProcessingMetric> =
    buildList {
        addMetric("AUDIO_DURATION_MS", audioDurationMs, "ms", "whisper.cpp")
        addMetric("INFERENCE_DURATION_MS", inferenceDurationMs, "ms", "whisper.cpp")
        timeToFirstTextMs?.let {
            addMetric("TIME_TO_FIRST_TEXT_MS", it, "ms", "whisper.cpp")
        }
        addMetric("REAL_TIME_FACTOR", realTimeFactor, "ratio", "whisper.cpp")
        addMetric("PEAK_PSS_KB", peakPssKb, "KiB", "whisper.cpp")
        addMetric("MAXIMUM_THERMAL_STATUS", maximumThermalStatus, "status", "whisper.cpp")
        maximumBatteryTemperatureC?.let {
            addMetric("MAXIMUM_BATTERY_TEMPERATURE_C", it, "celsius", "whisper.cpp")
        }
        addMetric("CHUNK_COUNT", chunkCount, "count", "whisper.cpp")
    }

internal fun SherpaStreamingLabResult.toProcessingMetrics(): List<PersistedProcessingMetric> =
    buildList {
        addMetric("AUDIO_DURATION_MS", audioDurationMs, "ms", "sherpa-onnx")
        addMetric("INFERENCE_DURATION_MS", inferenceDurationMs, "ms", "sherpa-onnx")
        timeToFirstTextMs?.let {
            addMetric("TIME_TO_FIRST_TEXT_MS", it, "ms", "sherpa-onnx")
        }
        firstTextAudioMs?.let {
            addMetric("FIRST_TEXT_AUDIO_MS", it, "ms", "sherpa-onnx")
        }
        addMetric("REAL_TIME_FACTOR", realTimeFactor, "ratio", "sherpa-onnx")
        addMetric("PEAK_PSS_KB", peakPssKb, "KiB", "sherpa-onnx")
        addMetric("MAXIMUM_THERMAL_STATUS", maximumThermalStatus, "status", "sherpa-onnx")
        maximumBatteryTemperatureC?.let {
            addMetric("MAXIMUM_BATTERY_TEMPERATURE_C", it, "celsius", "sherpa-onnx")
        }
        addMetric("PARTIAL_UPDATE_COUNT", partialUpdateCount, "count", "sherpa-onnx")
        addMetric("ENDPOINT_COUNT", endpointCount, "count", "sherpa-onnx")
        addMetric("DECODE_PASS_COUNT", decodePassCount, "count", "sherpa-onnx")
    }

private fun MutableList<PersistedProcessingMetric>.addMetric(
    name: String,
    value: Number,
    unit: String,
    runtime: String,
) {
    addMetric(name, value.toDouble(), unit, runtime)
}

private fun MutableList<PersistedProcessingMetric>.addMetric(
    name: String,
    value: Double,
    unit: String,
    runtime: String,
) {
    add(
        PersistedProcessingMetric(
            name = name,
            value = value,
            unit = unit,
            phase = "post_process",
            runtime = runtime,
            delegate = "cpu",
        ),
    )
}
