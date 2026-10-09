package com.incident201.poseguard.scenario

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.incident201.poseguard.intiface.IntifaceBackgroundMode
import com.incident201.poseguard.intiface.IntifaceOperation
import com.incident201.poseguard.intiface.IntifaceVibrationSettings
import com.incident201.poseguard.intiface.IntifaceViolationMode
import com.incident201.poseguard.intiface.IntifaceVibrationPattern
import com.incident201.poseguard.viewmodel.FaceCheckMode
import com.incident201.poseguard.viewmodel.GameState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntifaceSessionScenarioTest : SessionFixture() {
    @Before fun connectSessionDevice() {
        IntifaceLab.requireEnabled()
        IntifaceLab.reset()
        onMain {
            model.updateIntifaceConnectionEnabled(true)
            model.searchIntifaceDevices(IntifaceLab.url)
        }
        awaitCondition("Intiface scan", 15_000) {
            model.intifaceState.value.operation == IntifaceOperation.Idle && model.intifaceState.value.devices.isNotEmpty()
        }
        onMain {
            model.selectIntifaceDevice(model.intifaceState.value.devices.single { it.name.contains(" A ") })
            model.updateIntifaceBackgroundVibration(IntifaceVibrationSettings(strength = .2))
            model.updateIntifaceBackgroundMode(IntifaceBackgroundMode.Vibration)
            model.updateIntifaceViolationVibration(IntifaceVibrationSettings(strength = .7, durationSeconds = .1))
            model.updateIntifaceViolationMode(IntifaceViolationMode.Vibration)
        }
        awaitCondition("device selected") { model.intifaceState.value.selectedDevice?.name?.contains(" A ") == true }
        Thread.sleep(100)
        IntifaceLab.reset()
    }

    @Test fun violationOverridesBackgroundThenRestoresItAndStopZerosOutput() {
        start()
        IntifaceLab.awaitValue(20)
        disappearUntilViolation()
        IntifaceLab.awaitValue(70)
        awaitCondition("background restored after violation") {
            val commands = IntifaceLab.commands()
            val violation = commands.indexOfLast { it.value == 70 }
            violation >= 0 && commands.drop(violation + 1).any { it.value == 20 }
        }
        onMain { model.stopSession() }
        assertOutputStops()
        assertEquals(GameState.Idle, model.gameState.value)
    }

    @Test fun successStopsBackgroundOutput() {
        start(1)
        IntifaceLab.awaitValue(20)
        frame(advanceMs = 1_100)
        awaitCondition("success") { model.gameState.value == GameState.Success }
        assertOutputStops()
    }

    @Test fun cameraLossStopsSignalsWithoutAddingViolations() {
        start()
        IntifaceLab.awaitValue(20)
        onMain { model.onCameraUnavailable() }
        assertEquals(GameState.Failed, model.gameState.value)
        assertEquals(0, model.violationCount.value)
        assertOutputStops()
    }

    @Test fun switchingDuringSessionStopsOldDeviceAndMovesBackgroundToNewOne() {
        start()
        IntifaceLab.awaitValue(20)
        IntifaceLab.reset()
        onMain { model.selectIntifaceDevice(model.intifaceState.value.devices.single { it.name.contains(" B ") }) }
        IntifaceLab.awaitValue(20, "B")
        IntifaceLab.awaitValue(0, "A")
        disappearUntilViolation()
        IntifaceLab.awaitValue(70, "B")
        assertFalse(IntifaceLab.commands().any { it.device == "A" && it.value > 0 })
        onMain { model.stopSession() }
        assertOutputStops("B")
    }

    @Test fun disablingConnectionDuringViolationCancelsLateSignals() {
        onMain { model.updateIntifaceViolationVibration(IntifaceVibrationSettings(strength = .7, durationSeconds = 1.0)) }
        start()
        IntifaceLab.awaitValue(20)
        disappearUntilViolation()
        IntifaceLab.awaitValue(70)
        onMain { model.updateIntifaceConnectionEnabled(false) }
        assertOutputStops()
        Thread.sleep(1_100)
        assertFalse(model.intifaceState.value.isConnected)
        assertEquals(GameState.HoldingPose, model.gameState.value)
        assertTrue(IntifaceLab.commands().filter { it.device == "A" }.takeLast(2).all { it.value == 0 })
    }

    @Test fun pauseViolationZerosBackgroundAndThenRestoresIt() {
        onMain {
            model.updateIntifaceViolationMode(IntifaceViolationMode.Pause)
            model.updateIntifaceViolationPauseSeconds(.3)
        }
        start()
        IntifaceLab.awaitValue(20)
        IntifaceLab.reset()
        disappearUntilViolation()
        IntifaceLab.awaitValue(0)
        IntifaceLab.awaitValue(20)
        val commands = IntifaceLab.commands().filter { it.device == "A" && it.motor == 0 }
        assertEquals(0, commands.first().value)
        assertEquals(20, commands.last().value)
        assertFalse(commands.any { it.value == 70 })
    }

    @Test fun pulsedBackgroundAndViolationProduceOnOffEdgesAndCancelOnStop() {
        onMain {
            model.updateIntifaceBackgroundVibration(IntifaceVibrationSettings(strength = .2, pattern = IntifaceVibrationPattern.Pulse, pulseLengthSeconds = .1, pulsePauseSeconds = .1))
            model.updateIntifaceViolationVibration(IntifaceVibrationSettings(strength = .7, pattern = IntifaceVibrationPattern.Pulse, durationSeconds = .6, pulseLengthSeconds = .1, pulsePauseSeconds = .1))
        }
        start()
        IntifaceLab.awaitValue(20)
        IntifaceLab.awaitValue(0)
        IntifaceLab.reset()
        disappearUntilViolation()
        awaitCondition("multiple violation pulses") {
            IntifaceLab.commands().count { it.device == "A" && it.motor == 0 && it.value == 70 } >= 2
        }
        onMain { model.stopSession() }
        assertOutputStops()
    }

    @Test fun terminalViolationFinishesItsEffectWithoutRestartingBackground() {
        onMain { model.updateMaxViolations(1) }
        start()
        IntifaceLab.awaitValue(20)
        IntifaceLab.reset()
        disappearUntilViolation()
        assertEquals(GameState.Failed, model.gameState.value)
        IntifaceLab.awaitValue(70)
        assertOutputStops()
        assertFalse(IntifaceLab.commands().any { it.value == 20 })
    }

    @Test fun faceViolationDrivesConfiguredEffect() {
        onMain { model.updateFaceCheckMode(FaceCheckMode.FaceToCamera) }
        start()
        IntifaceLab.awaitValue(20)
        repeat(8) { if (model.violationCount.value == 0) frame() }
        assertTrue(model.ruleViolationCounts.value.face > 0)
        IntifaceLab.awaitValue(70)
    }

    @Test fun movementViolationDrivesConfiguredEffect() {
        start()
        IntifaceLab.awaitValue(20)
        for (step in 1..35) {
            if (model.violationCount.value > 0) break
            frame(translated((step * .012f).coerceAtMost(.25f)), 150)
        }
        val counts = model.ruleViolationCounts.value
        assertTrue(counts.drift + counts.motion > 0)
        IntifaceLab.awaitValue(70)
    }

    @Test fun detectorOutageStopsBackgroundWithoutPunitiveSignal() {
        onMain { model.updateFaceCheckMode(FaceCheckMode.FaceAwayFromCamera) }
        start()
        IntifaceLab.awaitValue(20)
        IntifaceLab.reset()
        frame(width = 64, height = 64)
        frame(advanceMs = 5_100, width = 64, height = 64)
        assertEquals(GameState.Failed, model.gameState.value)
        assertTrue(model.sessionSummary.value!!.stoppedByTechnicalError)
        assertEquals(0, model.violationCount.value)
        assertOutputStops()
        assertFalse(IntifaceLab.commands().any { it.value == 70 })
    }

    private fun assertOutputStops(device: String = "A") {
        awaitCondition("both motors stopped") {
            val commands = IntifaceLab.commands().filter { it.device == device }
            val motors = if (device == "A") setOf(0, 1) else setOf(0)
            commands.size >= motors.size && commands.takeLast(motors.size).map { it.motor }.toSet() == motors && commands.takeLast(motors.size).all { it.value == 0 }
        }
        val count = IntifaceLab.commands().size
        Thread.sleep(350)
        assertTrue("Late callbacks must not restart vibration", IntifaceLab.commands().drop(count).all { it.value == 0 })
    }
}
