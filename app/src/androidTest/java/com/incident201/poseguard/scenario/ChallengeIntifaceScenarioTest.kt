package com.incident201.poseguard.scenario

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.challenge.*
import com.incident201.poseguard.intiface.*
import com.incident201.poseguard.viewmodel.GameState
import com.incident201.poseguard.viewmodel.GameViewModel
import com.incident201.poseguard.viewmodel.RuleViolationCounts
import com.incident201.poseguard.viewmodel.SessionTiming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** No server required: disconnects exercise the real session state machine in both flavors. */
@RunWith(AndroidJUnit4::class)
class ChallengeIntifaceScenarioTest : SessionFixture() {
    private val controller = SessionIntifaceController()
    private var task: AuthoredChallenge? = null

    internal override fun createModel() = GameViewModel(app, timing(), controller)
    internal override fun timing() = SessionTiming(now::get, 0, 1)

    private fun acceptTask(required: Boolean = true) {
        onMain { model.updateIntifaceConnectionEnabled(true) }
        task = model.challengeRepository.createChallenge("Intiface", "", "Test",
            ChallengeRules(timer = TimerSpec(exactSeconds = 1), intifaceRequired = required), ChallengeVisibility(), 0)
        assertTrue(runBlocking { model.acceptChallenge(task!!.spec.id) })
    }

    @After fun removeTask() {
        task?.let { authored ->
            model.challengeReport.value?.let { report ->
                awaitCondition("stored report") { model.challengeRepository.outgoingReports().any { it.report.reportId == report.reportId } }
                model.challengeRepository.deleteOutgoing(report.reportId)
            }
            model.challengeRepository.deleteAuthored(authored.spec.id)
        }
    }

    private fun assertTechnicalStop() {
        awaitCondition("technical Intiface stop") { model.gameState.value == GameState.Failed }
        val report = model.challengeReport.value!!
        assertEquals(SessionEndCause.IntifaceUnavailable, report.endCause)
        assertFalse(report.intifaceConnected)
        assertEquals(RuleViolationCounts(), report.counts)
        assertTrue(report.events.isEmpty())
        assertTrue(model.sessionSummary.value!!.stoppedByTechnicalError)
        assertFalse(cues.any { it.cue == AudioCue.DefeatTryAgain || it.cue == AudioCue.TimeIsUp })
        assertTrue(controller.strengths.all { it == 0.0 })
    }

    @Test fun disconnectDuringPreparationStopsWithoutAReferenceOrPenalty() {
        acceptTask()
        onMain {
            model.startSession()
            assertTrue(model.gameState.value in setOf(GameState.WaitingForStabilization, GameState.StartingDelay))
            controller.disconnect()
        }
        assertTechnicalStop()
        assertNull(model.challengeReport.value!!.trace)
    }

    @Test fun disconnectDuringCountdownCannotStartTheTimer() {
        acceptTask()
        begin()
        awaitCondition("StartingDelay") { model.gameState.value == GameState.StartingDelay }
        onMain { controller.disconnect() }
        assertTechnicalStop()
        assertNull(model.challengeReport.value!!.trace)
    }

    @Test fun losingTheSelectedDeviceAtTheDeadlineCannotAwardSuccess() {
        acceptTask()
        start()
        repeat(5) { frame() }
        onMain { controller.state.value = controller.state.value.copy(selectedDevice = null) }
        frame(advanceMs = 1_100)
        assertTechnicalStop()
        assertNotNull(model.challengeReport.value!!.trace)
    }

    @Test fun disablingARequiredConnectionStopsTheAttempt() {
        acceptTask()
        start()
        onMain { model.updateIntifaceConnectionEnabled(false) }
        assertTechnicalStop()
    }

    @Test fun optionalIntifaceDisconnectionStillAllowsCompletion() {
        acceptTask(required = false)
        start()
        onMain { controller.disconnect() }
        frame(advanceMs = 1_100)
        awaitCondition("Success") { model.gameState.value == GameState.Success }
        assertEquals(SessionEndCause.Completed, model.challengeReport.value!!.endCause)
    }
}

private class SessionIntifaceController : IntifaceController {
    override val state = MutableStateFlow(IntifaceUiState(isSupported = true, isConnected = true,
        selectedDevice = IntifaceDeviceInfo(1, "Test", "Test", 1)))
    val strengths = CopyOnWriteArrayList<Double>()
    override suspend fun searchDevices(url: String) = Unit
    override suspend fun connectToRememberedDevice(url: String, rememberedDevice: IntifaceRememberedDevice) = Unit
    override suspend fun testVibration() = Unit
    override suspend fun setVibrationStrength(strength: Double) { strengths += strength }
    override suspend fun stopVibration() { strengths += 0.0 }
    override suspend fun selectDevice(device: IntifaceDeviceInfo) { state.value = state.value.copy(selectedDevice = device) }
    override fun disconnect() { state.value = state.value.copy(isConnected = false, selectedDevice = null) }
    override suspend fun resetConnection() = disconnect()
    override fun clearTransientMessages() = Unit
}
