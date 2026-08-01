package com.noteapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricGateStateMachineTest {
    @Test
    fun `initial preference loads fail closed and requests prompt only when resumed`() {
        val machine = BiometricGateStateMachine()

        assertEquals(BiometricGateState.LOADING, machine.state)
        assertFalse(machine.applyPreference(enabled = true, isResumed = false))
        assertEquals(BiometricGateState.LOCKED, machine.state)
        assertTrue(machine.onResume())
    }

    @Test
    fun `disabled preference reveals content without requesting authentication`() {
        val machine = BiometricGateStateMachine()

        assertFalse(machine.applyPreference(enabled = false, isResumed = true))
        assertEquals(BiometricGateState.UNLOCKED, machine.state)
    }

    @Test
    fun `background transition locks enabled protection`() {
        val machine = enabledMachine()
        machine.onUnlockSucceeded(isResumed = true)
        assertEquals(BiometricGateState.UNLOCKED, machine.state)

        machine.onStop()

        assertEquals(BiometricGateState.LOCKED, machine.state)
        assertTrue(machine.onResume())
    }

    @Test
    fun `authentication completed outside resumed state remains locked`() {
        val machine = enabledMachine()

        machine.onUnlockSucceeded(isResumed = false)

        assertEquals(BiometricGateState.LOCKED, machine.state)
    }

    @Test
    fun `enabling outside resumed state and disabling are deterministic`() {
        val machine = BiometricGateStateMachine()
        machine.applyPreference(enabled = false, isResumed = true)

        machine.onProtectionEnabled(isResumed = false)
        assertTrue(machine.enabled)
        assertEquals(BiometricGateState.LOCKED, machine.state)

        machine.onProtectionDisabled()
        assertFalse(machine.enabled)
        assertEquals(BiometricGateState.UNLOCKED, machine.state)
    }

    private fun enabledMachine() = BiometricGateStateMachine().also { machine ->
        machine.applyPreference(enabled = true, isResumed = false)
    }
}
