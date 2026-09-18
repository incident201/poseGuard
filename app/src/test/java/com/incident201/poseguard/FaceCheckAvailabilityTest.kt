package com.incident201.poseguard

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.incident201.poseguard.audio.AudioCueEvent
import com.incident201.poseguard.intiface.*
import com.incident201.poseguard.tracker.FaceDetectionStatus
import com.incident201.poseguard.tracker.PoseLandmarks
import com.incident201.poseguard.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceCheckAvailabilityTest {
    private lateinit var model: GameViewModel
    private val store = ViewModelStore()
    private val audio = mutableListOf<AudioCueEvent>()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("game_settings", 0).edit().clear().commit()
        model = GameViewModel(app)
        store.put("test", model)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            model.audioCueEvents.collect { audio.add(it) }
        }
        hold()
    }

    @After fun tearDown() {
        scope.cancel()
        store.clear()
    }

    private fun field(name: String) =
        GameViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(name: String) = field(name).get(model) as MutableStateFlow<T>

    private fun call(name: String): Any? = GameViewModel::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(model)

    private fun observe(status: FaceDetectionStatus): Boolean =
        GameViewModel::class.java.getDeclaredMethod("updateFaceCheckAvailability", FaceDetectionStatus::class.java)
            .apply { isAccessible = true }.invoke(model, status) as Boolean

    private fun hold(mode: FaceCheckMode = FaceCheckMode.FaceToCamera, seconds: Int = 60) {
        model.updateFaceCheckMode(mode)
        state<GameState>("_gameState").value = GameState.HoldingPose
        (field("sessionClock").get(model) as SessionClock).apply {
            reset(seconds)
            start(SystemClock.elapsedRealtime())
        }
        state<Int>("_timerSeconds").value = seconds
    }

    private fun advance(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))

    @Test fun shortOutageDoesNotStopOrPenalize() {
        assertFalse(observe(FaceDetectionStatus.Error))
        advance(4_999)
        assertFalse(observe(FaceDetectionStatus.Error))
        assertEquals(GameState.HoldingPose, model.gameState.value)
        assertEquals(0, model.violationCount.value)
        assertEquals(60, model.timerSeconds.value)
        assertTrue(audio.isEmpty())
    }

    @Test fun continuousErrorStopsAtFiveSecondsWithoutAddingPenaltyOrErasingPreviousViolations() {
        field("currentViolationCount").setInt(model, 2)
        state<Int>("_violationCount").value = 2
        state<RuleViolationCounts>("_ruleViolationCounts").value = RuleViolationCounts(drift = 1, face = 1)
        observe(FaceDetectionStatus.Error)
        advance(5_000)
        assertTrue(observe(FaceDetectionStatus.Error))
        assertEquals(GameState.Failed, model.gameState.value)
        val summary = model.sessionSummary.value!!
        assertTrue(summary.stoppedByTechnicalError)
        assertEquals(RuleViolationCounts(drift = 1, face = 1), summary.violationCounts)
        assertEquals(2, model.violationCount.value)
        assertEquals(60, model.timerSeconds.value)
        assertEquals("Session stopped", model.statusMessage.value)
        assertTrue(summary.defeatReason.contains("Face detection is unavailable"))
        assertTrue(audio.isEmpty())
    }

    @Test fun mixedUnavailableStatusesShareOneTimeout() {
        observe(FaceDetectionStatus.Error)
        advance(2_500)
        assertFalse(observe(FaceDetectionStatus.NotProcessed))
        advance(2_500)
        assertTrue(observe(FaceDetectionStatus.Error))
    }

    @Test fun successfulDetectionWithOrWithoutAFaceResetsTheTimeout() {
        for (status in listOf(FaceDetectionStatus.FaceVisible, FaceDetectionStatus.FaceNotVisible)) {
            model.stopSession()
            hold()
            observe(FaceDetectionStatus.Error)
            advance(4_000)
            assertFalse(observe(status))
            advance(2_000)
            assertFalse(observe(FaceDetectionStatus.Error))
            advance(4_999)
            assertFalse(observe(FaceDetectionStatus.Error))
            assertEquals(GameState.HoldingPose, model.gameState.value)
        }
    }

    @Test fun disabledFaceRuleDoesNotStartATimeout() {
        model.updateFaceCheckMode(FaceCheckMode.Disabled)
        observe(FaceDetectionStatus.Error)
        advance(20_000)
        assertFalse(observe(FaceDetectionStatus.NotProcessed))
        assertEquals(GameState.HoldingPose, model.gameState.value)
    }

    @Test fun disablingAndReenablingTheRuleClearsAPreviousOutage() {
        observe(FaceDetectionStatus.Error)
        advance(4_000)
        model.updateFaceCheckMode(FaceCheckMode.Disabled)
        advance(2_000)
        model.updateFaceCheckMode(FaceCheckMode.FaceAwayFromCamera)
        assertFalse(observe(FaceDetectionStatus.Error))
        advance(4_999)
        assertFalse(observe(FaceDetectionStatus.Error))
    }

    @Test fun notVisibleRemainsARegularFaceRuleResultNotATechnicalError() {
        val process = GameViewModel::class.java.getDeclaredMethod(
            "processFaceRule", FaceDetectionStatus::class.java, PoseLandmarks::class.java
        ).apply { isAccessible = true }
        repeat(5) {
            assertFalse(observe(FaceDetectionStatus.FaceNotVisible))
            process.invoke(model, FaceDetectionStatus.FaceNotVisible, PoseLandmarks())
        }
        assertEquals(1, model.ruleViolationCounts.value.face)
        assertEquals(1, model.violationCount.value)
        assertEquals(GameState.HoldingPose, model.gameState.value)
        assertNull(model.sessionSummary.value)
    }

    @Test fun stopAndNewSessionDoNotInheritThePreviousTimeout() {
        observe(FaceDetectionStatus.Error)
        advance(4_000)
        model.stopSession()
        hold()
        observe(FaceDetectionStatus.Error)
        advance(4_999)
        assertFalse(observe(FaceDetectionStatus.Error))
    }

    @Test fun timerCannotAwardSuccessDuringOutageButCanAfterRecovery() {
        hold(seconds = 1)
        observe(FaceDetectionStatus.Error)
        advance(1_000)
        assertEquals(false, call("tryReserveSessionSuccess"))
        assertEquals(0, model.timerSeconds.value)
        assertFalse(observe(FaceDetectionStatus.FaceVisible))
        assertEquals(true, call("tryReserveSessionSuccess"))
        call("completeSessionSuccess")
        assertEquals(GameState.Success, model.gameState.value)
    }

    @Test fun timerWatchdogStopsWithoutWaitingForAnotherDetectorCallback() {
        observe(FaceDetectionStatus.Error)
        advance(5_000)
        call("checkSessionProgress")
        assertTrue(model.sessionSummary.value!!.stoppedByTechnicalError)
    }

    @Test fun preparationCountdownDoesNotTreatTakingPositionAsADetectorOutage() {
        state<GameState>("_gameState").value = GameState.StartingDelay
        observe(FaceDetectionStatus.NotProcessed)
        advance(10_000)
        assertFalse(observe(FaceDetectionStatus.NotProcessed))
        assertEquals(GameState.StartingDelay, model.gameState.value)
        hold()
        assertFalse(observe(FaceDetectionStatus.Error))
        advance(4_999)
        assertFalse(observe(FaceDetectionStatus.Error))
    }

    @Test fun recoveryArrivingAfterTheDeadlineDoesNotHideAProlongedOutage() {
        observe(FaceDetectionStatus.Error)
        advance(5_000)
        assertTrue(observe(FaceDetectionStatus.FaceVisible))
        assertTrue(model.sessionSummary.value!!.stoppedByTechnicalError)
    }

    @Test fun technicalStopStopsIntifaceWithoutSendingPunitiveVibration() {
        val fake = FakeIntifaceController()
        field("intifaceController").set(model, fake)
        state<GameSettings>("_gameSettings").value = model.gameSettings.value.copy(
            intifaceConnectionEnabled = true,
            intifaceViolationMode = IntifaceViolationMode.Vibration
        )
        observe(FaceDetectionStatus.Error)
        advance(5_000)
        observe(FaceDetectionStatus.Error)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fake.stopCount)
        assertTrue(fake.strengths.isEmpty())
        assertTrue(audio.isEmpty())
    }

    private class FakeIntifaceController : IntifaceController {
        override val state = MutableStateFlow(IntifaceUiState(
            isSupported = true, isConnected = true,
            selectedDevice = IntifaceDeviceInfo(1, "test", "test", 1)
        ))
        var stopCount = 0
        val strengths = mutableListOf<Double>()
        override suspend fun searchDevices(url: String) = Unit
        override suspend fun connectToRememberedDevice(url: String, rememberedDevice: IntifaceRememberedDevice) = Unit
        override suspend fun testVibration() = Unit
        override suspend fun setVibrationStrength(strength: Double) { strengths.add(strength) }
        override suspend fun stopVibration() { stopCount++ }
        override fun selectDevice(device: IntifaceDeviceInfo) = Unit
        override fun disconnect() = Unit
        override suspend fun resetConnection() = Unit
        override fun clearTransientMessages() = Unit
    }
}
