package com.incident201.poseguard.scenario

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.viewmodel.GameState
import com.incident201.poseguard.viewmodel.SessionTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val STILL = 0f
private const val SHAKING = 1.5f

/** The phone must lie still for the full stabilization period before the preparation countdown starts. */
@RunWith(AndroidJUnit4::class)
class StabilizationScenarioTest : SessionFixture() {
    override fun timing() = SessionTiming(now::get, stabilizationDurationMs = 1_000, startDelaySeconds = 0)

    @Before fun requireGyroscope() {
        val sensors = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        assumeTrue("Device has no gyroscope", sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null)
    }

    private fun waitForStillness() {
        onMain {
            model.updateSelectedDurationSeconds(30)
            model.startSession()
            detachRealGyroscope()
            assertEquals(GameState.WaitingForStabilization, model.gameState.value)
            frame()
        }
    }

    /** Lets [afterMs] of scenario time pass, with the camera still delivering frames, then delivers one gyroscope sample. */
    private fun sample(rate: Float, afterMs: Long = 0) = onMain {
        if (afterMs > 0) {
            now.addAndGet(afterMs - 1)
            frame(advanceMs = 1)
        }
        gyroscope(rate)
    }

    @Test fun aMovingPhoneNeverLeavesTheWaitingState() {
        waitForStillness()
        listOf(0L, 400L, 400L, 1_000L, 5_000L).forEach { sample(SHAKING, it) }
        Thread.sleep(300)
        assertEquals(GameState.WaitingForStabilization, model.gameState.value)
        assertFalse(cues.any { it.cue == AudioCue.TakePosition })
    }

    @Test fun stillnessForTheWholePeriodStartsTheHold() {
        waitForStillness()
        sample(STILL)
        sample(STILL, 999)
        Thread.sleep(300)
        assertEquals("Not still long enough yet", GameState.WaitingForStabilization, model.gameState.value)
        sample(STILL, 1)
        awaitCondition("hold after stabilization") { model.gameState.value == GameState.HoldingPose }
        assertTrue(cues.any { it.cue == AudioCue.TakePosition })
    }

    @Test fun movementRestartsTheStillnessPeriod() {
        waitForStillness()
        sample(STILL)
        sample(SHAKING, 800)
        sample(STILL, 100)
        sample(STILL, 900)
        Thread.sleep(300)
        assertEquals("Only 900 ms have passed since the phone settled", GameState.WaitingForStabilization, model.gameState.value)
        sample(STILL, 100)
        awaitCondition("hold after the restarted period") { model.gameState.value == GameState.HoldingPose }
    }

    @Test fun stoppingWhileWaitingIgnoresLaterStillness() {
        waitForStillness()
        sample(STILL)
        onMain { model.stopSession() }
        assertEquals(GameState.Idle, model.gameState.value)
        sample(STILL, 5_000)
        Thread.sleep(300)
        assertEquals(GameState.Idle, model.gameState.value)
        assertFalse(cues.any { it.cue == AudioCue.TakePosition })
    }
}

/** The preparation countdown runs in real time so that the user can step into the frame. */
@RunWith(AndroidJUnit4::class)
class CountdownScenarioTest : SessionFixture() {
    override fun timing() = SessionTiming(now::get, stabilizationDurationMs = 0, startDelaySeconds = 2)

    @Test fun countdownTicksDownAnnouncesThePositionAndThenHolds() {
        begin(30)
        awaitCondition("countdown of 2 seconds") { model.startDelayRemainingSeconds.value == 2 }
        assertEquals(GameState.StartingDelay, model.gameState.value)
        awaitCondition("countdown of 1 second", 2_000) { model.startDelayRemainingSeconds.value == 1 }
        awaitCondition("hold after the countdown", 4_000) { model.gameState.value == GameState.HoldingPose }
        assertEquals(0, model.startDelayRemainingSeconds.value)
        assertTrue(cues.any { it.cue == AudioCue.PlaceDeviceStill })
        assertTrue(cues.any { it.cue == AudioCue.TakePosition })
        awaitCondition("hold started cue") { cues.any { it.cue == AudioCue.TimeStartedHoldPosition } }
    }

    @Test fun stoppingDuringTheCountdownCancelsItWithoutLateTransitions() {
        begin(30)
        awaitCondition("countdown") { model.gameState.value == GameState.StartingDelay }
        onMain { model.stopSession() }
        assertEquals(GameState.Idle, model.gameState.value)
        assertEquals(0, model.startDelayRemainingSeconds.value)
        // The countdown job would have finished by now had cancellation not worked.
        Thread.sleep(2_600)
        assertEquals(GameState.Idle, model.gameState.value)
        assertFalse(cues.any { it.cue == AudioCue.TimeStartedHoldPosition })
        assertEquals(0, model.violationCount.value)
    }

    @Test fun noFreshFrameAtTheEndOfTheCountdownFailsInsteadOfStartingTheHold() {
        begin(30)
        awaitCondition("countdown") { model.gameState.value == GameState.StartingDelay }
        // The camera stops delivering: the last analyzed pose is stale when the countdown finishes.
        now.addAndGet(5_000)
        awaitCondition("failure for the missing body", 5_000) { model.gameState.value == GameState.Failed }
        assertEquals(0, model.violationCount.value)
        assertFalse(cues.any { it.cue == AudioCue.TimeStartedHoldPosition })
    }
}
