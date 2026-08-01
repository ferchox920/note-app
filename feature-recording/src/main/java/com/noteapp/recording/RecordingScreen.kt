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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.noteapp.domain.RecordingIntent
import com.noteapp.domain.SessionStatus
import com.noteapp.asr.WhisperModelCatalog
import com.noteapp.asr.WhisperModelDescriptor
import com.noteapp.asr.SherpaStreamingModelCatalog
import com.noteapp.audio.CapturePipeline
import java.util.Locale

@Composable
fun RecordingRoute(
    biometricMessage: String?,
    onSetBiometricProtection: (Boolean) -> Unit,
    viewModel: RecordingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    var pendingModel by remember { mutableStateOf<WhisperModelDescriptor?>(null) }
    var pendingCapturePipeline by remember { mutableStateOf(CapturePipeline.DIRECT_16_KHZ) }
    var pendingIncrementalModelId by remember { mutableStateOf<String?>(null) }
    var pendingConsentAcknowledgement by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            permissionDenied = false
            if (pendingConsentAcknowledgement) {
                viewModel.acknowledgeConsentAndStart(
                    pendingCapturePipeline,
                    pendingIncrementalModelId,
                )
            } else {
                viewModel.startRecording(pendingCapturePipeline, pendingIncrementalModelId)
            }
        } else {
            permissionDenied = true
        }
        pendingConsentAcknowledgement = false
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
            pendingConsentAcknowledgement = false
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
        onConsentAndStart = { pipeline, incrementalModelId ->
            pendingCapturePipeline = pipeline
            pendingIncrementalModelId = incrementalModelId
            pendingConsentAcknowledgement = true
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                val permissions = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                permissionLauncher.launch(permissions.toTypedArray())
            } else {
                pendingConsentAcknowledgement = false
                viewModel.acknowledgeConsentAndStart(pipeline, incrementalModelId)
            }
        },
        onImportModel = { descriptor ->
            pendingModel = descriptor
            modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
        },
        onTranscribe = { descriptor ->
            viewModel.transcribe(descriptor)
        },
        onTranscribeStreaming = {
            viewModel.transcribeStreaming()
        },
        onSelectLabSession = viewModel::selectLabSession,
        onDeleteCompletedSession = viewModel::deleteCompletedSession,
        onRecoverSession = viewModel::recoverSession,
        onCompareVad = viewModel::compareVad,
        selectedIncrementalModelId = state.selectedIncrementalModelId,
        onSelectIncrementalModel = viewModel::selectIncrementalModel,
        benchmarkThreadCount = state.benchmarkThreadCount,
        onSelectBenchmarkThreadCount = viewModel::selectBenchmarkThreadCount,
        benchmarkChunkSeconds = state.benchmarkChunkSeconds,
        onSelectBenchmarkChunkSeconds = viewModel::selectBenchmarkChunkSeconds,
        onSetRetentionDays = viewModel::setRetentionDays,
        biometricMessage = biometricMessage,
        onSetBiometricProtection = onSetBiometricProtection,
    )
}

@Composable
fun RecordingScreen(
    state: RecordingUiState,
    permissionDenied: Boolean,
    onIntent: (RecordingIntent) -> Unit,
    onStart: (CapturePipeline, String?) -> Unit,
    onConsentAndStart: (CapturePipeline, String?) -> Unit,
    onImportModel: (WhisperModelDescriptor) -> Unit,
    onTranscribe: (WhisperModelDescriptor) -> Unit,
    onTranscribeStreaming: () -> Unit,
    onSelectLabSession: (String) -> Unit,
    onDeleteCompletedSession: (String) -> Unit,
    onRecoverSession: (String) -> Unit,
    onCompareVad: () -> Unit,
    selectedIncrementalModelId: String?,
    onSelectIncrementalModel: (String?) -> Unit,
    benchmarkThreadCount: Int,
    onSelectBenchmarkThreadCount: (Int) -> Unit,
    benchmarkChunkSeconds: Int,
    onSelectBenchmarkChunkSeconds: (Int) -> Unit,
    onSetRetentionDays: (Int) -> Unit,
    biometricMessage: String?,
    onSetBiometricProtection: (Boolean) -> Unit,
) {
    var confirmFinish by remember { mutableStateOf(false) }
    var pendingSessionDeletion by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingConsentStart by remember {
        mutableStateOf<Pair<CapturePipeline, String?>?>(null)
    }
    var retentionMenuExpanded by remember { mutableStateOf(false) }
    var pendingRetentionDays by remember { mutableStateOf<Int?>(null) }
    var confirmDisableBiometric by remember { mutableStateOf(false) }
    if (confirmFinish && state.status != SessionStatus.RECORDING && state.status != SessionStatus.PAUSED) {
        confirmFinish = false
    }
    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("¿Finalizar grabación?") },
            text = { Text("La sesión se cerrará y ya no continuará capturando audio.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmFinish = false
                    onIntent(RecordingIntent.Complete)
                }) {
                    Text("Finalizar")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFinish = false }) {
                    Text("Continuar grabando")
                }
            },
        )
    }
    pendingConsentStart?.let { (pipeline, incrementalModelId) ->
        AlertDialog(
            onDismissRequest = { pendingConsentStart = null },
            title = { Text("Autorización para grabar") },
            text = {
                Text(
                    "Confirma que informaste a las personas participantes y que tienes " +
                        "autorización para grabar. El audio y la transcripción se guardan " +
                        "localmente y puedes configurar cuánto tiempo conservarlos.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.asrRunning && !state.sessionDeletionRunning,
                    onClick = {
                        pendingConsentStart = null
                        onConsentAndStart(pipeline, incrementalModelId)
                    },
                ) {
                    Text("Confirmo y comenzar")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConsentStart = null }) {
                    Text("Cancelar")
                }
            },
        )
    }
    pendingRetentionDays?.let { days ->
        AlertDialog(
            onDismissRequest = { pendingRetentionDays = null },
            title = { Text("¿Activar retención de $days días?") },
            text = {
                Text(
                    "Las sesiones finalizadas con más de $days días se eliminarán " +
                        "automáticamente, junto con audio, transcripción, notas, trabajos " +
                        "y métricas. Esta acción no se puede deshacer.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.asrRunning && !state.sessionDeletionRunning,
                    onClick = {
                        pendingRetentionDays = null
                        onSetRetentionDays(days)
                    },
                ) {
                    Text("Activar y aplicar")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRetentionDays = null }) {
                    Text("Cancelar")
                }
            },
        )
    }
    if (confirmDisableBiometric) {
        AlertDialog(
            onDismissRequest = { confirmDisableBiometric = false },
            title = { Text("¿Desactivar protección biométrica?") },
            text = {
                Text(
                    "La app dejará de pedir reautenticación al volver desde segundo plano. " +
                        "El cifrado local de sesiones seguirá activo.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisableBiometric = false
                        onSetBiometricProtection(false)
                    },
                ) {
                    Text("Desactivar")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisableBiometric = false }) {
                    Text("Cancelar")
                }
            },
        )
    }
    val deletionSession = pendingSessionDeletion?.let { sessionId ->
        state.completedSessions.firstOrNull { session -> session.id == sessionId }
    }
    deletionSession?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingSessionDeletion = null },
            title = { Text("¿Eliminar esta sesión?") },
            text = {
                Text(
                    "Se eliminarán permanentemente el audio, la transcripción, " +
                        "las notas, los trabajos y las métricas de ${session.id.take(8)}.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.sessionDeletionRunning,
                    onClick = {
                        pendingSessionDeletion = null
                        onDeleteCompletedSession(session.id)
                    },
                ) {
                    Text("Eliminar definitivamente")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSessionDeletion = null }) {
                    Text("Cancelar")
                }
            },
        )
    }
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
        if (state.status == SessionStatus.NEW && state.completedSessions.isNotEmpty()) {
            CompletedSessionSelector(
                state = state,
                onSelectLabSession = onSelectLabSession,
                onRequestDelete = { sessionId -> pendingSessionDeletion = sessionId },
            )
        }
        if (state.status == SessionStatus.NEW) {
            Text("Retención local", modifier = Modifier.padding(top = 16.dp))
            TextButton(
                enabled = state.preferencesReady && !state.asrRunning &&
                    !state.sessionDeletionRunning,
                onClick = { retentionMenuExpanded = true },
            ) {
                Text(retentionPolicyLabel(state.retentionDays))
            }
            DropdownMenu(
                expanded = retentionMenuExpanded,
                onDismissRequest = { retentionMenuExpanded = false },
            ) {
                listOf(0, 30, 90, 365).forEach { days ->
                    DropdownMenuItem(
                        text = { Text(retentionPolicyLabel(days)) },
                        onClick = {
                            retentionMenuExpanded = false
                            if (days == 0) onSetRetentionDays(days)
                            else pendingRetentionDays = days
                        },
                    )
                }
            }
            if (state.retentionDeletedCount > 0) {
                Text(
                    "Retención aplicada: ${state.retentionDeletedCount} sesiones eliminadas.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.sessionDeletionError?.let { errorCode ->
                Text(
                    "No se pudo aplicar el borrado o la retención: $errorCode",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text("Protección de acceso", modifier = Modifier.padding(top = 16.dp))
            Text(
                if (state.biometricReauthenticationEnabled) {
                    "La app se bloquea al quedar en segundo plano."
                } else {
                    "Opcional: exige biometría fuerte para volver a ver tus sesiones."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                enabled = state.preferencesReady && !state.asrRunning &&
                    !state.sessionDeletionRunning,
                onClick = {
                    if (state.biometricReauthenticationEnabled) {
                        confirmDisableBiometric = true
                    } else {
                        onSetBiometricProtection(true)
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    if (state.biometricReauthenticationEnabled) {
                        "Desactivar protección biométrica"
                    } else {
                        "Activar protección biométrica"
                    },
                )
            }
            biometricMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall)
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
            Text(
                "Pipeline recordado: ${state.preferredCapturePipelineId}",
                style = MaterialTheme.typography.bodySmall,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Button(
                    enabled = state.preferencesReady,
                    onClick = { onSelectIncrementalModel(null) },
                ) {
                    Text("Sin ASR en vivo")
                }
                WhisperModelCatalog.evaluationModels
                    .filter { it.id in state.installedModelIds }
                    .forEach { descriptor ->
                        Button(
                            enabled = state.preferencesReady,
                            onClick = { onSelectIncrementalModel(descriptor.id) },
                        ) {
                            Text(descriptor.fileName.substringAfter("ggml-").substringBefore("-"))
                        }
                    }
                SherpaStreamingModelCatalog.evaluationModels
                    .filter { it.id in state.installedModelIds }
                    .forEach { descriptor ->
                        Button(
                            enabled = state.preferencesReady,
                            onClick = { onSelectIncrementalModel(descriptor.id) },
                        ) {
                            Text("Streaming ES (experimental)")
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
                    Button(
                        enabled = state.preferencesReady && !state.asrRunning &&
                            !state.sessionDeletionRunning,
                        onClick = {
                            val request = CapturePipeline.DIRECT_16_KHZ to selectedIncrementalModelId
                            if (state.consentNoticeAcknowledged) {
                                onStart(request.first, request.second)
                            } else {
                                pendingConsentStart = request
                            }
                        },
                    ) {
                        Text("Iniciar 16 kHz")
                    }
                    Button(
                        enabled = state.preferencesReady && !state.asrRunning &&
                            !state.sessionDeletionRunning,
                        onClick = {
                            val request = CapturePipeline.NATIVE_48_KHZ_TO_16_KHZ to
                                selectedIncrementalModelId
                            if (state.consentNoticeAcknowledged) {
                                onStart(request.first, request.second)
                            } else {
                                pendingConsentStart = request
                            }
                        },
                    ) {
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
                Button(onClick = { confirmFinish = true }) {
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
                Text(
                    recoverableSessionMessage(session.errorCode),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { onRecoverSession(session.id) },
                    modifier = Modifier.padding(top = 4.dp),
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
            IncrementalTranscriptPanel(state)
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
            if (state.incrementalSuppressedRepetitionCount > 0) {
                Text(
                    "Hipótesis repetitivas suprimidas: " +
                        state.incrementalSuppressedRepetitionCount,
                    color = MaterialTheme.colorScheme.error,
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
        Text(
            "Benchmark: $benchmarkThreadCount hilos · chunks ${benchmarkChunkSeconds}s",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            listOf(2, 4, 6, 8).forEach { threads ->
                Button(
                    enabled = state.preferencesReady && !state.asrRunning,
                    onClick = { onSelectBenchmarkThreadCount(threads) },
                ) {
                    Text(if (threads == benchmarkThreadCount) "[$threads] h" else "$threads h")
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            listOf(10, 20, 30).forEach { seconds ->
                Button(
                    enabled = state.preferencesReady && !state.asrRunning,
                    onClick = { onSelectBenchmarkChunkSeconds(seconds) },
                ) {
                    Text(if (seconds == benchmarkChunkSeconds) "[$seconds] s" else "$seconds s")
                }
            }
        }
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
                    enabled = state.preferencesReady &&
                        installed &&
                        state.labSessionId != null &&
                        !state.asrRunning,
                    onClick = { onTranscribe(descriptor) },
                ) {
                    Text("Transcribir ${descriptor.fileName.substringAfter("ggml-").substringBefore("-")}")
                }
            }
        }
        val streamingDescriptor = SherpaStreamingModelCatalog.spanishKroko
        val streamingInstalled = streamingDescriptor.id in state.installedModelIds
        Button(
            enabled = state.preferencesReady &&
                streamingInstalled &&
                state.labSessionId != null &&
                !state.asrRunning,
            onClick = onTranscribeStreaming,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Transcribir streaming ES")
        }
        if (!streamingInstalled) {
            Text(
                "Modelo streaming experimental no instalado",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.asrRunning) Text("Procesando ASR en el dispositivo...")
        state.asrResult?.let { result ->
            Text(
                "${result.modelId} / ${result.capturePipelineId} / " +
                    "${result.benchmarkConfigId}: ${result.chunkCount} chunks, " +
                    "RTF ${String.format(Locale.ROOT, "%.2f", result.realTimeFactor)}, " +
                    "PSS pico ${result.peakPssKb / 1024} MiB",
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(result.nativeSystemInfo, style = MaterialTheme.typography.bodySmall)
            Text(result.transcript.ifBlank { "(sin texto)" })
        }
        state.streamingAsrResult?.let { result ->
            Text(
                "${result.modelId} / ${result.capturePipelineId} / " +
                    "${result.benchmarkConfigId}: RTF " +
                    String.format(Locale.ROOT, "%.3f", result.realTimeFactor) +
                    ", primer texto de audio ${result.firstTextAudioMs ?: -1} ms, " +
                    "PSS pico ${result.peakPssKb / 1024} MiB",
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

@Composable
private fun CompletedSessionSelector(
    state: RecordingUiState,
    onSelectLabSession: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.completedSessions.firstOrNull { it.id == state.labSessionId }
        ?: state.completedSessions.first()
    Text(
        "Sesión completada",
        modifier = Modifier.padding(top = 12.dp),
        style = MaterialTheme.typography.titleSmall,
    )
    TextButton(
        enabled = !state.asrRunning,
        onClick = { expanded = true },
        modifier = Modifier.semantics {
            contentDescription = "Elegir sesión completada"
        },
    ) {
        Text("${selected.id.take(8)} (${formatDuration(selected.durationMs)})")
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        state.completedSessions.forEach { session ->
            DropdownMenuItem(
                text = {
                    Text("${session.id.take(8)} (${formatDuration(session.durationMs)})")
                },
                onClick = {
                    expanded = false
                    onSelectLabSession(session.id)
                },
            )
        }
    }
    OutlinedButton(
        enabled = !state.asrRunning && !state.sessionDeletionRunning,
        onClick = { onRequestDelete(selected.id) },
        modifier = Modifier.semantics {
            contentDescription = "Eliminar sesión seleccionada"
        },
    ) {
        Text(if (state.sessionDeletionRunning) "Eliminando…" else "Eliminar sesión")
    }
}

@Composable
private fun IncrementalTranscriptPanel(state: RecordingUiState) {
    val presentation = remember(
        state.incrementalStableText,
        state.incrementalUnstableText,
        state.incrementalFinalizedSegments,
    ) {
        incrementalTranscriptPresentation(
            stableText = state.incrementalStableText,
            unstableText = state.incrementalUnstableText,
            finalizedSegments = state.incrementalFinalizedSegments,
        )
    }
    val segments = presentation.finalizedSegments
    val transcriptKey = state.sessionId ?: state.labSessionId
    var selectedIndex by rememberSaveable(transcriptKey) { mutableIntStateOf(-1) }
    var followLatest by rememberSaveable(transcriptKey) { mutableStateOf(true) }
    var timestampMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(transcriptKey, segments.size) {
        selectedIndex = when {
            segments.isEmpty() -> -1
            selectedIndex !in segments.indices || followLatest -> segments.lastIndex
            else -> selectedIndex
        }
    }

    Text(
        "Texto finalizado",
        modifier = Modifier.padding(top = 12.dp),
        style = MaterialTheme.typography.titleSmall,
    )
    if (segments.isEmpty()) {
        Text(
            "Aún no hay segmentos cerrados.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        val safeIndex = selectedIndex.coerceIn(0, segments.lastIndex)
        val selected = segments[safeIndex]
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            TextButton(
                onClick = { timestampMenuExpanded = true },
                modifier = Modifier.semantics {
                    contentDescription = "Elegir segmento por timestamp"
                },
            ) {
                Text(transcriptSegmentRange(selected))
            }
            DropdownMenu(
                expanded = timestampMenuExpanded,
                onDismissRequest = { timestampMenuExpanded = false },
            ) {
                segments.forEachIndexed { index, segment ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${index + 1}. ${transcriptSegmentRange(segment)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        onClick = {
                            selectedIndex = index
                            followLatest = index == segments.lastIndex
                            timestampMenuExpanded = false
                        },
                    )
                }
            }
            Text(
                "Segmento ${safeIndex + 1} de ${segments.size}",
                style = MaterialTheme.typography.labelSmall,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                OutlinedButton(
                    enabled = safeIndex > 0,
                    onClick = {
                        selectedIndex = safeIndex - 1
                        followLatest = false
                    },
                ) {
                    Text("Anterior")
                }
                OutlinedButton(
                    enabled = safeIndex < segments.lastIndex,
                    onClick = {
                        selectedIndex = safeIndex + 1
                        followLatest = safeIndex + 1 == segments.lastIndex
                    },
                ) {
                    Text("Siguiente")
                }
            }
        }
        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            SelectionContainer {
                Text(
                    selected.text,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (presentation.hasProvisionalText) {
        Text(
            "Texto provisional · puede cambiar",
            modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.titleSmall,
        )
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            SelectionContainer {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (presentation.provisionalStableText.isNotBlank()) {
                        Text(presentation.provisionalStableText)
                    }
                    if (presentation.provisionalUnstableText.isNotBlank()) {
                        Text(
                            presentation.provisionalUnstableText,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

private fun retentionPolicyLabel(days: Int): String = when (days) {
    0 -> "Conservar siempre"
    else -> "Conservar $days días"
}
