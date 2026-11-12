package com.example.cartoon

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Add-on OBD features. Each one lives in its own file and plugs into [ObdManager]
 * from the outside, so the core connection code stays untouched.
 */
class ObdModules(context: Context, val obd: ObdManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val scheduler = PollScheduler(context, obd)
    val voltage = VoltageMonitor(context, obd, scheduler)
    val gaugeStyle = GaugeStyle(context)
    val parked = ParkedMode(context, obd)

    init {
        scheduler.start(scope)
        voltage.start(scope)
        parked.start(scope)
    }

    fun stop() {
        scope.cancel()
    }
}
