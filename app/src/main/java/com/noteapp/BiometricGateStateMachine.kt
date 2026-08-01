package com.noteapp

internal enum class BiometricGateState { LOADING, LOCKED, UNLOCKED }

internal enum class BiometricPromptPurpose { UNLOCK, ENABLE }

/** Pure fail-closed state machine for hiding sensitive content around app lifecycle changes. */
internal class BiometricGateStateMachine {
    var state: BiometricGateState = BiometricGateState.LOADING
        private set

    var enabled: Boolean = false
        private set

    fun applyPreference(enabled: Boolean, isResumed: Boolean): Boolean {
        val wasLoading = state == BiometricGateState.LOADING
        this.enabled = enabled
        when {
            !enabled -> state = BiometricGateState.UNLOCKED
            wasLoading -> state = BiometricGateState.LOCKED
        }
        return enabled && state == BiometricGateState.LOCKED && isResumed
    }

    fun onResume(): Boolean = enabled && state == BiometricGateState.LOCKED

    fun onStop() {
        if (enabled) state = BiometricGateState.LOCKED
    }

    fun onUnlockSucceeded(isResumed: Boolean) {
        state = if (enabled && isResumed) {
            BiometricGateState.UNLOCKED
        } else {
            BiometricGateState.LOCKED
        }
    }

    fun onProtectionEnabled(isResumed: Boolean) {
        enabled = true
        state = if (isResumed) BiometricGateState.UNLOCKED else BiometricGateState.LOCKED
    }

    fun onProtectionDisabled() {
        enabled = false
        state = BiometricGateState.UNLOCKED
    }
}
