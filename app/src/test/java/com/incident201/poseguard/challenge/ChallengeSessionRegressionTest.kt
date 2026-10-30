package com.incident201.poseguard.challenge

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.incident201.poseguard.intiface.*
import com.incident201.poseguard.tracker.*
import com.incident201.poseguard.viewmodel.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChallengeSessionRegressionTest {
    private fun pose() = PoseLandmarks.fromAllLandmarks(List(33) { i ->
        Point3D(if (i % 2 == 0) .6f else .4f, .1f + i * .02f, 0f, 1f, 1f)
    }).quantizedForTrace()

    private fun spec(rules: ChallengeRules) = ChallengeSpec(Blob(ByteArray(16)), "Review", "", "", 0,
        rules, ChallengeVisibility(), 0, Blob(ByteArray(32)), Blob(ByteArray(0)))

    private fun field(name: String) = GameViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun call(model: GameViewModel, name: String) = GameViewModel::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(model)
    @Suppress("UNCHECKED_CAST")
    private fun <T> state(model: GameViewModel, name: String, value: T) {
        (field(name).get(model) as MutableStateFlow<T>).value = value
    }

    private fun holding(model: GameViewModel, spec: ChallengeSpec, initialFace: FaceDetectionStatus): ChallengeSessionRecorder {
        state(model, "_activeChallenge", ActiveChallenge(spec))
        state(model, "_gameState", GameState.HoldingPose)
        field("sessionInitialTimerSeconds").set(model, 60)
        (field("sessionClock").get(model) as SessionClock).apply { reset(60); start(10000) }
        return ChallengeSessionRecorder(spec, 1, "review", 9000, System.currentTimeMillis()).also {
            it.startTrace(10000, pose(), 10000, initialFace)
            field("challengeRecorder").set(model, it)
        }
    }

    @Test fun rejectedRetryPreservesFinalResultAndReportsThatNoSessionStarted() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("game_settings", 0).edit().clear().commit()
        app.getSharedPreferences("challenge_state", 0).edit().clear().commit()
        val fake = FakeController()
        val model = GameViewModel(app, SessionTiming({ 10000L }), fake)
        val store = ViewModelStore().apply { put("review", model) }
        try {
            val limited = spec(ChallengeRules()).copy(attemptsAllowed = 1)
            assertEquals(1, model.challengeRepository.beginAttempt(limited))
            state(model, "_activeChallenge", ActiveChallenge(limited))
            state(model, "_gameState", GameState.Success)
            val summary = SessionSummary(GameState.Success, 60, 60, RuleViolationCounts(), GameSettings())
            state(model, "_sessionSummary", summary)
            assertFalse(model.startSession())
            assertSame(summary, model.sessionSummary.value)
            assertEquals(GameState.Success, model.gameState.value)
            assertEquals(ChallengeMessage.NoAttemptsLeft, model.challengeMessage.value)

            state(model, "_activeChallenge", ActiveChallenge(spec(ChallengeRules(intifaceRequired = true))))
            assertFalse(model.startSession())
            assertSame(summary, model.sessionSummary.value)
            assertEquals(ChallengeMessage.IntifaceRequired, model.challengeMessage.value)

            // An accepted start is distinguishable, allowing the UI to discard the previous media.
            model.leaveChallenge()
            assertTrue(model.startSession())
            assertEquals(GameState.WaitingForStabilization, model.gameState.value)
            assertNull(model.sessionSummary.value)
            assertFalse(model.startSession())
            model.stopSession()
        } finally { store.clear() }
    }

    @Test fun simultaneousIntifaceLossAndFaceTimeoutMustVerifyTheLiveResult() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("game_settings", 0).edit().clear().commit()
        val now = AtomicLong(10000)
        val fake = FakeController()
        val model = GameViewModel(app, SessionTiming(now::get, 0, 0), fake)
        val store = ViewModelStore().apply { put("review", model) }
        try {
            model.updateIntifaceConnectionEnabled(true)
            val s = spec(ChallengeRules(timer = TimerSpec(exactSeconds = 60),
                faceCheckMode = FaceCheckMode.FaceToCamera, intifaceRequired = true,
                intifaceBackgroundMode = IntifaceBackgroundMode.Vibration))
            val recorder = holding(model, s, FaceDetectionStatus.Error)
            field("faceCheckUnavailableSinceMs").set(model, 10000L)
            // A regular stream, with the face detector unavailable for just under 5s.
            for (t in 10050L..14950L step 50) recorder.frame(t, t, FaceDetectionStatus.Error, pose())
            now.set(15000)
            fake.state.value = IntifaceUiState(isSupported = true)
            call(model, "checkSessionProgress")
            val report = model.challengeReport.value!!
            assertEquals(SessionEndCause.IntifaceUnavailable, report.endCause)
            // Align wall time with the synthetic monotonic clock, irrelevant to this test.
            val aligned = report.copy(endedAtWallMs = report.startedAtWallMs + report.endedAtMonoMs - report.startedAtMonoMs)
            assertEquals(Verdict(VerdictKind.Confirmed), ReplayReportVerifier.verify(aligned, s))
        } finally { store.clear() }
    }

    @Test fun disabledPenaltiesKeepV2EventsButDisplayNoAddedMinutes() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("game_settings", 0).edit().clear().commit()
        val now = AtomicLong(10000)
        val model = GameViewModel(app, SessionTiming(now::get, 0, 0), FakeController())
        val store = ViewModelStore().apply { put("review", model) }
        try {
            val s = spec(ChallengeRules(timer = TimerSpec(exactSeconds = 60),
                faceCheckMode = FaceCheckMode.FaceToCamera, maxViolations = 99, penaltiesEnabled = false))
            val recorder = holding(model, s, FaceDetectionStatus.FaceVisible)
            val process = GameViewModel::class.java.getDeclaredMethod("processFaceRule",
                FaceDetectionStatus::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
            repeat(5) {
                now.addAndGet(50)
                recorder.frame(now.get(), now.get(), FaceDetectionStatus.FaceNotVisible, pose())
                process.invoke(model, FaceDetectionStatus.FaceNotVisible, now.get())
            }
            model.stopSession()
            assertEquals(1, model.challengeReport.value!!.events.size)
            val report = model.challengeReport.value!!
            val decoded = com.incident201.poseguard.challenge.codec.ReportCodec.decode(
                com.incident201.poseguard.challenge.codec.ReportCodec.encode(report))
            assertEquals(1, decoded.events.single().penaltyMinutes) // Existing format v2 semantics.
            assertEquals(0, decoded.events.single().appliedPenaltyMinutes(s.rules))
            assertEquals(1, decoded.events.single().appliedPenaltyMinutes(s.rules.copy(penaltiesEnabled = true)))
            val aligned = decoded.copy(endedAtWallMs = decoded.startedAtWallMs + decoded.endedAtMonoMs - decoded.startedAtMonoMs)
            assertEquals(Verdict(VerdictKind.Confirmed), ReplayReportVerifier.verify(aligned, s))
        } finally { store.clear() }
    }

    private class FakeController : IntifaceController {
        override val state = MutableStateFlow(IntifaceUiState(isSupported = true, isConnected = true,
            selectedDevice = IntifaceDeviceInfo(1, "test", "test", 1)))
        override suspend fun searchDevices(url: String) = Unit
        override suspend fun connectToRememberedDevice(url: String, rememberedDevice: IntifaceRememberedDevice) = Unit
        override suspend fun testVibration() = Unit
        override suspend fun setVibrationStrength(strength: Double) = Unit
        override suspend fun stopVibration() = Unit
        override suspend fun selectDevice(device: IntifaceDeviceInfo) = Unit
        override fun disconnect() = Unit
        override suspend fun resetConnection() = Unit
        override fun clearTransientMessages() = Unit
    }
}
