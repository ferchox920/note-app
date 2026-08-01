package com.noteapp

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.noteapp.recording.RecordingRoute

private const val RECORDING_ROUTE = "recording"

@Composable
fun NoteAppNavHost(
    biometricMessage: String?,
    onSetBiometricProtection: (Boolean) -> Unit,
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = RECORDING_ROUTE) {
        composable(RECORDING_ROUTE) {
            RecordingRoute(
                biometricMessage = biometricMessage,
                onSetBiometricProtection = onSetBiometricProtection,
            )
        }
    }
}
