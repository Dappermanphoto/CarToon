package com.example.cartoon

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Driving lock. When the OBD adapter reports the car is moving, video (the YouTube tab and video
 * playback) is blocked, like a parked-only app should be.
 *
 * Testing: a hidden switch (tap the version text at the bottom of Settings five times quickly)
 * pretends the car is parked for 30 minutes, so the app can be used while driving the demo car
 * or the real one. It switches itself off, and also when the app is closed.
 *
 * This only controls CarToon's own lock. Android Auto itself may still hide the app on a real
 * head unit when the car is moving.
 */
class ParkedMode(context: Context, private val obd: ObdManager) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("cartoon_parked", Context.MODE_PRIVATE)

    /** The user's setting: block video while moving. */
    var lockEnabled by mutableStateOf(prefs.getBoolean("lock", true))
        private set

    /** True while the OBD speed says the car is moving. */
    var moving by mutableStateOf(false)
        private set

    /** Seconds left in the hidden test mode; 0 when it is off. */
    var testSecondsLeft by mutableIntStateOf(0)
        private set

    private var testUntil = 0L
    private var taps = 0
    private var lastTap = 0L

    val testMode: Boolean get() = testSecondsLeft > 0
    val blocked: Boolean get() = lockEnabled && moving && !testMode

    fun setLock(on: Boolean) {
        lockEnabled = on
        prefs.edit().putBoolean("lock", on).apply()
    }

    /** Called for every tap on the hidden spot. Five quick taps switch test mode on or off. */
    fun secretTap() {
        val now = SystemClock.elapsedRealtime()
        taps = if (now - lastTap < 1500) taps + 1 else 1
        lastTap = now
        if (taps >= 5) {
            taps = 0
            if (testMode) endTest() else startTest()
        }
    }

    fun startTest() {
        testUntil = SystemClock.elapsedRealtime() + TEST_MINUTES * 60_000L
        testSecondsLeft = TEST_MINUTES * 60
        Toast.makeText(app, "Parked-mode test on for $TEST_MINUTES minutes", Toast.LENGTH_SHORT).show()
    }

    fun endTest() {
        testUntil = 0L
        testSecondsLeft = 0
        Toast.makeText(app, "Parked-mode test off", Toast.LENGTH_SHORT).show()
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            var slowSince = 0L
            while (true) {
                delay(500)
                val now = SystemClock.elapsedRealtime()

                testSecondsLeft = if (now < testUntil) ((testUntil - now) / 1000L).toInt() + 1 else 0

                // Speed comes in km/h from the real adapter and from the demo car alike.
                val speed = if (obd.state == ObdManager.State.CONNECTED) obd.values["0D"] else null
                when {
                    speed == null -> {
                        moving = false
                        slowSince = 0L
                    }
                    speed > MOVING_KMH -> {
                        moving = true
                        slowSince = 0L
                    }
                    moving -> {
                        // Stay locked a few seconds after stopping so brief stops don't flicker
                        if (slowSince == 0L) slowSince = now
                        if (now - slowSince > 4000L) {
                            moving = false
                            slowSince = 0L
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val TEST_MINUTES = 30
        private const val MOVING_KMH = 8f
    }
}

/** Shown in place of video while the car is moving. */
@Composable
fun ParkedOnly(modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(ui.pad)
                .clip(RoundedCornerShape(28.dp))
                .background(cs.surface)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Parked only", fontSize = ui.headline, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text(
                "Video is off while the car is moving. It comes back a few seconds after you stop.",
                fontSize = ui.body,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (onClose != null) {
                Spacer(Modifier.height(16.dp))
                Pill("Close video", selected = true, onClick = onClose)
            }
        }
    }
}

/** Small reminder while the hidden test mode is on. Tap it to switch the test off. */
@Composable
fun ParkedTestBadge(parked: ParkedMode, modifier: Modifier = Modifier) {
    if (!parked.testMode) return
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val left = parked.testSecondsLeft
    Text(
        "TEST · parked simulated · %d:%02d".format(left / 60, left % 60),
        fontSize = ui.small,
        fontWeight = FontWeight.Bold,
        color = cs.onPrimary,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(cs.primary.copy(alpha = 0.9f))
            .clickable { parked.endTest() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** Visible setting for the driving lock. The test switch itself stays hidden. */
@Composable
fun ParkedSection(parked: ParkedMode) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    AdvSection("Driving lock") {
        AdvSwitchRow(
            title = "Block video while moving",
            body = "When the OBD adapter (or the demo car) reports driving, the YouTube tab and video playback are turned off until you stop.",
            checked = parked.lockEnabled,
        ) { parked.setLock(it) }
        Spacer(Modifier.height(8.dp))
        Text(
            if (parked.moving) "Right now: moving" else "Right now: stopped, or no OBD speed",
            fontSize = ui.small,
            color = cs.onSurfaceVariant,
        )
    }
}
