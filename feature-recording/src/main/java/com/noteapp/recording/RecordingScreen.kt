package com.noteapp.recording

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.noteapp.domain.RecordingIntent
import com.noteapp.domain.SessionStatus
import com.noteapp.asr.WhisperModelCatalog
import com.noteapp.asr.WhisperModelDescriptor
import com.noteapp.audio.CapturePipeline
import java.util.Locale

@Composable
fun RecordingRoute(viewModel: RecordingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    var pendingModel by remember { mutableStateOf<WhisperModelDescriptor?>(null) }
    var pendingCapturePipeline by remember { mutableStateOf(CapturePipeline.DIRECT_16_KHZ) }
    var selectedIncrementalModelId by remember { mutableStateOf<String?>(null) }
    var pendingIncrementalModelId by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            permissionDenied = false
            viewModel.startRecording(pendingCapturePipeline, pendingIncrementalModelId)
        } else {
            permissionDenied = true
        }
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val descriptor = pendingModel
        if (uri != null && descriptor != null) viewModel.importModel(uri, descriptor)
        pendingModel = null
    }
    RecordingScreen(
        state = state,
        permissionDenied = permissionDenied,
        onIntent = { intent ->
            viewModel.onIntent(intent)
        },
        onStart = { pipeline, incrementalModelId ->
            pendingCapturePipeline = pipeline
            pendingIncrementalModelId = incrementalModelId
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                val permissions = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                permissionLauncher.launch(permissions.toTypedArray())
            } else {
                viewModel.startRecording(pipeline, incrementalModelId)
            }
        },
        onImportModel = { descriptor ->
            pendingModel = descriptor
            modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
        },
        onTranscribe = viewModel::transcribe,
        onRecoverSession = viewModel::recoverSession,
        onCompareVad = viewModel::compareVad,
        selectedIncrementalModelId = selectedIncrementalModelId,
        onSelectIncrementalModel = { selectedIncrementalModelId = it },
    )
}

@Composable
fun RecordingScreen(
    state: RecordingUiState,
    permissionDenied: Boolean,
    onIntent: (RecordingIntent) -> Unit,
    onStart: (CapturePipeline, String?) -> Unit,
    onImportModel: (WhisperModelDescriptor) -> Unit,
    onTranscribe: (WhisperModelDescriptor) -> Unit,
    onRecoverSession: (String) -> Unit,
    onCompareVad: () -> Unit,
    selectedIncrementalModelId: String?,
    onSelectIncrementalModel: (String?) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Note App", style = MaterialTheme.typography.headlineMedium)
        Text("Estado: ${state.status}", modifier = Modifier.padding(vertical = 16.dp))
        state.sessionId?.let { sessionId ->
            SelectionContainer {
                Text("Sesión: $sessionId", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.sessionId == null && state.labSessionId != null) {
            SelectionContainer {
                Text("Sesión de laboratorio: ${state.labSessionId}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Duración: ${formatDuration(state.durationMs)}")
        Text("PCM escrito: ${state.bytesWritten} bytes")
        Text("Captura: ${state.capturePipelineId} (${state.captureSampleRateHz} Hz)")
        Text(if (state.speechDetected) "Voz detectada" else "Sin voz")
        Text("Segmentos VAD: ${state.vadSegmentCount}")
        Text("Errores de lectura: ${state.readErrorCount}")
        Text(
            "Discontinuidades: ${state.discontinuityCount} " +
                "(${state.estimatedMissingFrames} frames estimados)",
        )
        if (state.status == SessionStatus.NEW) {
            Text("ASR incremental de laboratorio", modifier = Modifier.padding(top = 16.dp))
            Text(
                selectedIncrementalModelId?.let { "Modelo seleccionado: $it" }
                    ?: "Desactivado para la próxima sesión",
                style = MaterialTheme.typography.bodySmall,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Button(onClick = { onSelectIncrementalModel(null) }) {
                    Text("Sin ASR en vivo")
                }
                WhisperModelCatalog.evaluationModels
                    .filter { it.id in state.installedModelIds }
                    .forEach { descriptor ->
                        Button(onClick = { onSelectIncrementalModel(descriptor.id) }) {
                            Text(descriptor.fileName.substringAfter("ggml-").substringBefore("-"))
                        }
                    }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 16.dp),
        ) {
            when (state.status) {
                SessionStatus.NEW -> {
                    Button(onClick = {
                        onStart(CapturePipeline.DIRECT_16_KHZ, selectedIncrementalModelId)
                    }) {
                        Text("Iniciar 16 kHz")
                    }
                    Button(onClick = {
                        onStart(
                            CapturePipeline.NATIVE_48_KHZ_TO_16_KHZ,
                            selectedIncrementalModelId,
                        )
                    }) {
                        Text("Iniciar 48→16 kHz")
                    }
                }
                SessionStatus.RECORDING -> Button(onClick = { onIntent(RecordingIntent.Pause) }) {
                    Text("Pausar")
                }
                SessionStatus.PAUSED -> Button(onClick = { onIntent(RecordingIntent.Resume) }) {
                    Text("Reanudar")
                }
                else -> Unit
            }
            if (state.status == SessionStatus.RECORDING || state.status == SessionStatus.PAUSED) {
                Button(onClick = { onIntent(RecordingIntent.Complete) }) {
                    Text("Finalizar")
                }
            }
        }
        if (permissionDenied) {
            Text(
                "Se necesita permiso de micrófono para grabar.",
                modifier = Modifier.padding(top = 24.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.status == SessionStatus.NEW && state.recoverableSessions.isNotEmpty()) {
            Text("Sesiones interrumpidas", modifier = Modifier.padding(top = 24.dp))
            state.recoverableSessions.forEach { session ->
                Button(
                    onClick = { onRecoverSession(session.id) },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Reanudar ${session.id.take(8)} (${formatDuration(session.durationMs)})")
                }
            }
        }
        state.errorCode?.let { errorCode ->
            Text(
                "Error técnico: $errorCode",
                modifier = Modifier.padding(top = 24.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.vadErrorCode?.let { errorCode ->
            Text(
                "VAD no disponible: $errorCode. La captura PCM continúa.",
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.incrementalModelId != null) {
            Text("Transcripción incremental", modifier = Modifier.padding(top = 20.dp))
            Text(
                "${state.incrementalModelId} · parciales ${state.incrementalPartialCount} · " +
                    "cola ${state.incrementalQueueDepth} · descartados " +
                    state.incrementalDroppedPartialCount,
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.incrementalStableText.isNotBlank()) {
                Text(state.incrementalStableText)
            }
            if (state.incrementalUnstableText.isNotBlank()) {
                Text(
                    state.incrementalUnstableText,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (state.incrementalAsrRunning) {
                Text("Procesando ventana ASR…", style = MaterialTheme.typography.bodySmall)
            }
            state.incrementalTimeToFirstTextMs?.let { value ->
                Text("Primer texto: ${value} ms", style = MaterialTheme.typography.bodySmall)
            }
            val incrementalRtf = state.incrementalLastRealTimeFactor
            state.incrementalLastVisibleLatencyMs?.let { value ->
                Text(
                    "Última latencia visible: ${value} ms" +
                        (incrementalRtf?.let {
                            " · RTF ${String.format(Locale.ROOT, "%.2f", it)}"
                        } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.incrementalStableConflictCount > 0) {
                Text(
                    "Conflictos de estabilización: ${state.incrementalStableConflictCount}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.incrementalFinalizedSegments.forEach { segment ->
                Text(
                    "[${formatDuration(segment.startMs)}–${formatDuration(segment.endMs)}] " +
                        segment.text,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        state.incrementalAsrErrorCode?.let { errorCode ->
            Text(
                "ASR incremental no disponible: $errorCode. La captura continúa.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.labSessionId != null) {
            Button(
                enabled = !state.vadComparisonRunning,
                onClick = onCompareVad,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Comparar WebRTC / Silero")
            }
        }
        if (state.vadComparisonRunning) Text("Comparando detectores VAD offline...")
        state.vadComparisonResult?.engines?.forEach { result ->
            Text(
                "${result.engine} / ${result.capturePipelineId}: ${result.segmentCount} segmentos, " +
                    "cobertura ${String.format(Locale.ROOT, "%.1f", result.speechCoverage * 100)}%, " +
                    "RTF ${String.format(Locale.ROOT, "%.3f", result.realTimeFactor)}",
            )
        }
        state.vadComparisonError?.let { message ->
            Text("Comparación VAD falló: $message", color = MaterialTheme.colorScheme.error)
        }
        Text("Laboratorio ASR", modifier = Modifier.padding(top = 24.dp))
        WhisperModelCatalog.evaluationModels.forEach { descriptor ->
            val installed = descriptor.id in state.installedModelIds
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Button(
                    enabled = !state.asrRunning,
                    onClick = { onImportModel(descriptor) },
                ) {
                    Text(if (installed) "Reimportar ${descriptor.quantization}" else "Importar ${descriptor.fileName.substringAfter("ggml-").substringBefore("-")}")
                }
                Button(
                    enabled = installed && state.labSessionId != null && !state.asrRunning,
                    onClick = { onTranscribe(descriptor) },
                ) {
                    Text("Transcribir ${descriptor.fileName.substringAfter("ggml-").substringBefore("-")}")
                }
            }
        }
        if (state.asrRunning) Text("Procesando ASR en el dispositivo...")
        state.asrResult?.let { result ->
            Text(
                "${result.modelId} / ${result.capturePipelineId}: ${result.chunkCount} chunks, RTF ${String.format(Locale.ROOT, "%.2f", result.realTimeFactor)}, PSS pico ${result.peakPssKb / 1024} MiB",
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(result.transcript.ifBlank { "(sin texto)" })
        }
        state.asrError?.let { message ->
            Text(
                "ASR no disponible: $message",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}
