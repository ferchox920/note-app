package com.noteapp.asr

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DevicePerformanceSnapshot(
    val peakPssKb: Int,
    val maximumThermalStatus: Int,
    val maximumBatteryTemperatureC: Double?,
)

internal class DevicePerformanceSampler(private val context: Context) {
    private var peakPssKb = 0
    private var maximumThermalStatus = 0
    private var maximumBatteryTemperatureC: Double? = null

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            sample()
            delay(250)
        }
    }

    fun finish(job: Job): DevicePerformanceSnapshot {
        job.cancel()
        sample()
        return DevicePerformanceSnapshot(
            peakPssKb = peakPssKb,
            maximumThermalStatus = maximumThermalStatus,
            maximumBatteryTemperatureC = maximumBatteryTemperatureC,
        )
    }

    private fun sample() {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        peakPssKb = maxOf(peakPssKb, memoryInfo.totalPss)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(PowerManager::class.java)
            maximumThermalStatus = maxOf(maximumThermalStatus, powerManager.currentThermalStatus)
        }
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenthsCelsius = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenthsCelsius != null && tenthsCelsius != Int.MIN_VALUE) {
            maximumBatteryTemperatureC = maxOf(
                maximumBatteryTemperatureC ?: Double.NEGATIVE_INFINITY,
                tenthsCelsius / 10.0,
            )
        }
    }
}
