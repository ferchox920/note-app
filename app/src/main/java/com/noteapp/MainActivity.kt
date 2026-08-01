package com.noteapp

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.noteapp.storage.AppPreferencesStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var appPreferencesStore: AppPreferencesStore

    private val gateState = MutableStateFlow(BiometricGateState.LOADING)
    private val biometricMessage = MutableStateFlow<String?>(null)
    private val gateStateMachine = BiometricGateStateMachine()
    private var activePromptPurpose: BiometricPromptPurpose? = null
    private lateinit var biometricPrompt: BiometricPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        biometricPrompt = BiometricPrompt(
            this,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    super.onAuthenticationSucceeded(result)
                    val purpose = activePromptPurpose
                    activePromptPurpose = null
                    biometricMessage.value = null
                    when (purpose) {
                        BiometricPromptPurpose.UNLOCK -> {
                            gateStateMachine.onUnlockSucceeded(isResumed())
                            publishGateState()
                        }
                        BiometricPromptPurpose.ENABLE -> enableBiometricProtection()
                        null -> Unit
                    }
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errorText: CharSequence,
                ) {
                    super.onAuthenticationError(errorCode, errorText)
                    val purpose = activePromptPurpose
                    activePromptPurpose = null
                    biometricMessage.value = when (purpose) {
                        BiometricPromptPurpose.UNLOCK ->
                            "La aplicación sigue bloqueada. Autentícate para continuar."
                        BiometricPromptPurpose.ENABLE ->
                            "No se activó la protección biométrica."
                        null -> null
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    biometricMessage.value = "No se reconoció la biometría. Intenta nuevamente."
                }
            },
        )
        lifecycleScope.launch {
            appPreferencesStore.preferences.collect { preferences ->
                if (preferences.biometricReauthenticationEnabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                val shouldPrompt = gateStateMachine.applyPreference(
                    enabled = preferences.biometricReauthenticationEnabled,
                    isResumed = isResumed(),
                )
                publishGateState()
                if (shouldPrompt) {
                    requestBiometricAuthentication(BiometricPromptPurpose.UNLOCK)
                }
            }
        }
        setContent {
            val currentGateState by gateState.collectAsState()
            val currentBiometricMessage by biometricMessage.collectAsState()
            var contentInitialized by remember { mutableStateOf(false) }
            LaunchedEffect(currentGateState) {
                if (currentGateState == BiometricGateState.UNLOCKED) {
                    contentInitialized = true
                }
            }
            MaterialTheme {
                Surface {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (contentInitialized) {
                            NoteAppNavHost(
                                biometricMessage = currentBiometricMessage,
                                onSetBiometricProtection = { enabled ->
                                    if (enabled) {
                                        requestBiometricAuthentication(BiometricPromptPurpose.ENABLE)
                                    } else {
                                        disableBiometricProtection()
                                    }
                                },
                            )
                        }
                        when (currentGateState) {
                            BiometricGateState.LOADING -> BiometricLoadingScreen()
                            BiometricGateState.LOCKED -> BiometricLockedScreen(
                                message = currentBiometricMessage,
                                onUnlock = {
                                    requestBiometricAuthentication(BiometricPromptPurpose.UNLOCK)
                                },
                            )
                            BiometricGateState.UNLOCKED -> if (!contentInitialized) {
                                BiometricLoadingScreen()
                            } else {
                                Unit
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (gateStateMachine.onResume()) {
            requestBiometricAuthentication(BiometricPromptPurpose.UNLOCK)
        }
    }

    override fun onStop() {
        gateStateMachine.onStop()
        publishGateState()
        super.onStop()
    }

    private fun requestBiometricAuthentication(purpose: BiometricPromptPurpose) {
        if (activePromptPurpose != null) return
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
        val availability = BiometricManager.from(this).canAuthenticate(authenticators)
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            biometricMessage.value = biometricUnavailableMessage(availability)
            return
        }
        activePromptPurpose = purpose
        biometricMessage.value = null
        val title = when (purpose) {
            BiometricPromptPurpose.UNLOCK -> "Desbloquear Note App"
            BiometricPromptPurpose.ENABLE -> "Activar protección biométrica"
        }
        biometricPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Confirma tu identidad para proteger las sesiones locales")
                .setAllowedAuthenticators(authenticators)
                .setNegativeButtonText("Cancelar")
                .build(),
        )
    }

    private fun enableBiometricProtection() {
        lifecycleScope.launch {
            runCatching {
                appPreferencesStore.setBiometricReauthenticationEnabled(true)
            }.onSuccess {
                gateStateMachine.onProtectionEnabled(isResumed())
                publishGateState()
                biometricMessage.value = "Protección biométrica activada."
            }.onFailure {
                biometricMessage.value = "No se pudo guardar la protección biométrica."
            }
        }
    }

    private fun disableBiometricProtection() {
        lifecycleScope.launch {
            runCatching {
                appPreferencesStore.setBiometricReauthenticationEnabled(false)
            }.onSuccess {
                gateStateMachine.onProtectionDisabled()
                publishGateState()
                biometricMessage.value = "Protección biométrica desactivada."
            }.onFailure {
                biometricMessage.value = "No se pudo desactivar la protección biométrica."
            }
        }
    }

    private fun biometricUnavailableMessage(status: Int): String = when (status) {
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
            "No hay biometría fuerte registrada en el dispositivo."
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
            "El dispositivo no dispone de biometría compatible."
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
            "La biometría no está disponible temporalmente."
        else -> "No se puede usar la biometría en este momento."
    }

    private fun isResumed(): Boolean =
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun publishGateState() {
        gateState.value = gateStateMachine.state
    }
}

@androidx.compose.runtime.Composable
private fun BiometricLoadingScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@androidx.compose.runtime.Composable
private fun BiometricLockedScreen(
    message: String?,
    onUnlock: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text("Note App bloqueada", style = MaterialTheme.typography.headlineSmall)
                Text("Tus sesiones permanecen ocultas hasta que confirmes tu identidad.")
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = onUnlock) { Text("Desbloquear") }
            }
        }
    }
}
