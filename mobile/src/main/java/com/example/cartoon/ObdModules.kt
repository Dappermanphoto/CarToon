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
    val trips = TripLog(context, obd, scheduler)
    val health = HealthBaseline(context)
    val alerts = AlertMonitor(context, obd, voltage, scheduler)
    val odometer = OdometerTracker(context, obd, scheduler)
    val thresholds = ThresholdMonitor(context, obd, ThresholdStore(context), scheduler)
    val fuel = FuelMonitor(context, obd)
    val perf = PerfTimer(obd, scheduler)

    init {
        scheduler.start(scope)
        voltage.start(scope)
        parked.start(scope)
        trips.start(scope)
        alerts.start(scope)
        odometer.start(scope)
        thresholds.start(scope)
        fuel.start(scope)
        perf.start(scope)
    }

    fun stop() {
        scope.cancel()
    }
}
