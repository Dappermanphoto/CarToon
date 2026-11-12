package com.example.cartoon

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Variable polling rates. The adapter can only answer one sensor at a time, so reading
 * slow-changing sensors (temperatures, fuel level) less often leaves more time for the
 * fast ones (RPM, speed, voltage).
 *
 * Works from the outside: after each round of readings it tells [ObdManager] which
 * sensors to read next.
 */
@Stable
class PollScheduler(context: Context, private val obd: ObdManager) {
    enum class Tier { FAST, MEDIUM, SLOW }

    private val prefs = context.applicationContext.getSharedPreferences("cartoon_polling", Context.MODE_PRIVATE)

    var prioritize by mutableStateOf(prefs.getBoolean("prioritize", true))
        private set
    var mediumEvery by mutableIntStateOf(prefs.getInt("medium_every", 3))
        private set
    var slowEvery by mutableIntStateOf(prefs.getInt("slow_every", 10))
        private set

    private var wanted: Set<String> = emptySet()
    private val always = HashMap<String, Set<String>>()
    private var round = 0

    /** Sensors the screen that's showing right now needs. */
    fun setWanted(pids: Set<String>) {
        wanted = pids
        apply()
    }

    /** Sensors a background feature needs no matter which screen is showing. */
    fun require(owner: String, pids: Set<String>) {
        always[owner] = pids
        apply()
    }

    fun configure(prioritize: Boolean, mediumEvery: Int, slowEvery: Int) {
        this.prioritize = prioritize
        this.mediumEvery = mediumEvery.coerceIn(1, 10)
        this.slowEvery = slowEvery.coerceIn(2, 40)
        prefs.edit()
            .putBoolean("prioritize", this.prioritize)
            .putInt("medium_every", this.mediumEvery)
            .putInt("slow_every", this.slowEvery)
            .apply()
        apply()
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            snapshotFlow { obd.tick }.collect {
                round++
                apply()
            }
        }
        scope.launch {
            snapshotFlow { obd.supported }.collect { apply() }
        }
    }

    private fun apply() {
        val requested = wanted + always.values.flatten()
        val supported = obd.supported
        val all = if (supported.isEmpty()) requested else requested.filter { it in supported }.toSet()
        if (!prioritize || all.isEmpty()) {
            obd.activePids = all
            return
        }
        val next = all.filter { key ->
            // Anything not read yet goes first so screens fill in quickly.
            key !in obd.values || when (tierOf(key)) {
                Tier.FAST -> true
                Tier.MEDIUM -> round % mediumEvery == 0
                Tier.SLOW -> round % slowEvery == 0
            }
        }.toSet()
        obd.activePids = next.ifEmpty { all }
    }

    companion object {
        private val FAST = setOf("0C", "0D", "11", "04", "0B", "0E", "10", "14", "ATRV")
        private val SLOW = setOf("05", "0F", "2F", "33", "46", "5C", "07", "09", "42")

        fun tierOf(key: String): Tier = when (key) {
            in FAST -> Tier.FAST
            in SLOW -> Tier.SLOW
            else -> Tier.MEDIUM
        }
    }
}
