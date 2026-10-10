package com.incident201.poseguard.scenario

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.res.Configuration
import com.incident201.poseguard.R
import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.tracker.PoseLandmarks
import com.incident201.poseguard.viewmodel.GameState
import com.incident201.poseguard.viewmodel.FaceCheckMode
import com.incident201.poseguard.viewmodel.TimerMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionScenarioTest : SessionFixture() {
    private fun cameraFailureText(): String = app.createConfigurationContext(
        Configuration(app.resources.configuration).apply { setLocale(model.gameSettings.value.language.locale) }
    ).getString(R.string.camera_no_frame)

    @Test fun stablePoseCompletesWithSummaryAndAudioEvents() {
        start(1)
        frame(advanceMs = 1_100)
        awaitCondition("Success") { model.gameState.value == GameState.Success }
        assertEquals(0, model.violationCount.value)
        assertEquals(1, model.sessionSummary.value!!.initialTimerSeconds)
        assertEquals(GameState.Success, model.sessionSummary.value!!.result)
        awaitCondition("session completion audio event") { cues.any { it.cue == AudioCue.TimeIsUp } }
        assertTrue(cues.any { it.cue == AudioCue.TimeStartedHoldPosition })
        assertTrue(cues.any { it.cue == AudioCue.TimeIsUp })
    }

    @Test fun briefPoseDropoutRecoversWithoutPenalty() {
        start()
        frame(PoseLandmarks(), 50)
        frame(PoseLandmarks(), 50)
        frame()
        assertEquals(GameState.HoldingPose, model.gameState.value)
        assertEquals(0, model.violationCount.value)
        assertEquals(33, model.poseOverlayState.value.landmarks.size)
    }

    @Test fun persistentDisappearanceFailsAtViolationLimit() {
        onMain { model.updateMaxViolations(1) }
        start()
        disappearUntilViolation()
        assertEquals(GameState.Failed, model.gameState.value)
        assertEquals(1, model.sessionSummary.value!!.violationCounts.disappeared)
        assertFalse(model.sessionSummary.value!!.stoppedByTechnicalError)
        assertTrue(cues.any { it.cue == AudioCue.DefeatTryAgain })
    }

    @Test fun penaltyExtendsTimerAndCooldownSuppressesRepeatedViolation() {
        onMain { model.updateMinimumPenaltyIntervalSeconds(5) }
        start(30)
        disappearUntilViolation()
        assertEquals(1, model.violationCount.value)
        assertTrue(model.timerSeconds.value > 30)
        repeat(10) { frame(PoseLandmarks(), 200) }
        assertEquals(1, model.violationCount.value)
        assertEquals(GameState.HoldingPose, model.gameState.value)
    }

    @Test fun penaltiesFollowTheConfiguredTiersAndTheLastAllowedViolationEndsTheSession() {
        onMain {
            model.updateMaxViolations(5)
            model.updateFirstViolationPenaltyMinutes(1)
            model.updateSecondViolationPenaltyMinutes(2)
            model.updateThirdViolationPenaltyMinutes(3)
            model.updateSubsequentViolationPenaltyMinutes(4)
        }
        start(30)
        var expectedSeconds = 30
        listOf(1, 2, 3, 4).forEachIndexed { index, minutes ->
            disappearUntilViolationCount(index + 1)
            expectedSeconds += minutes * 60
            val remaining = model.timerSeconds.value
            // Only a few seconds of scenario time elapse; a wrong tier would be off by whole minutes.
            assertTrue("Violation ${index + 1}: expected about $expectedSeconds s, got $remaining s",
                remaining in (expectedSeconds - 25)..expectedSeconds)
            assertEquals(GameState.HoldingPose, model.gameState.value)
        }
        disappearUntilViolationCount(5)
        assertEquals(GameState.Failed, model.gameState.value)
        assertEquals(5, model.sessionSummary.value!!.violationCounts.disappeared)
        assertTrue(cues.any { it.cue == AudioCue.DefeatTryAgain })
    }

    @Test fun movementRunsThroughStabilizerSmootherAndTracker() {
        onMain { model.updateMaxViolations(1) }
        start()
        for (step in 1..35) {
            if (model.gameState.value != GameState.HoldingPose) break
            frame(translated((step * .012f).coerceAtMost(.25f)), 150)
        }
        assertEquals(GameState.Failed, model.gameState.value)
        val counts = model.sessionSummary.value!!.violationCounts
        assertTrue(counts.drift + counts.motion > 0)
    }

    @Test fun slowDisplacementIsClassifiedAsDriftAndEmitsDriftCue() {
        onMain { model.updateMotionThresholdFactor(.08f) }
        start()
        for (step in 1..40) {
            if (model.violationCount.value > 0) break
            frame(translated((step * .008f).coerceAtMost(.24f)), 200)
        }
        assertEquals(1, model.ruleViolationCounts.value.drift)
        assertEquals(0, model.ruleViolationCounts.value.motion)
        awaitCondition("drift audio event") { cues.any { it.cue == AudioCue.DriftViolation } }
        assertEquals(GameState.HoldingPose, model.gameState.value)
    }

    @Test fun rapidMotionIsClassifiedAsMotionAndEmitsMotionCue() {
        onMain { model.updateMotionThresholdFactor(.02f) }
        start()
        for (step in 1..8) {
            if (model.violationCount.value > 0) break
            frame(translated((step * .1f).coerceAtMost(.3f)), 200)
        }
        assertEquals(1, model.ruleViolationCounts.value.motion)
        assertEquals(0, model.ruleViolationCounts.value.drift)
        awaitCondition("motion audio event") { cues.any { it.cue == AudioCue.MotionViolation } }
        assertEquals(GameState.HoldingPose, model.gameState.value)
    }

    @Test fun stalledCameraAtDeadlineCannotAwardSuccess() {
        start(1)
        now.addAndGet(6_000)
        awaitCondition("camera failure") { model.gameState.value == GameState.Failed }
        assertEquals(cameraFailureText(), model.sessionSummary.value!!.defeatReason)
        assertEquals(0, model.violationCount.value)
    }

    @Test fun backgroundingCancelsPendingStartAndFailsActiveSession() {
        onMain { model.startSession(); model.onCameraUnavailable() }
        assertEquals(GameState.Idle, model.gameState.value)
        start()
        onMain { model.onCameraUnavailable() }
        assertEquals(GameState.Failed, model.gameState.value)
        assertEquals(cameraFailureText(), model.sessionSummary.value!!.defeatReason)
    }

    @Test fun stopAndRestartClearOldCountersAndAllowSuccess() {
        start()
        disappearUntilViolation()
        onMain { model.stopSession() }
        assertEquals(GameState.Idle, model.gameState.value)
        assertEquals(0, model.violationCount.value)
        assertNull(model.sessionSummary.value)
        start(1)
        frame(advanceMs = 1_100)
        awaitCondition("success after restart") { model.gameState.value == GameState.Success }
        assertEquals(0, model.sessionSummary.value!!.violationCounts.disappeared)
    }

    @Test fun disablingPenaltiesStillCountsViolationsWithoutAddingTime() {
        onMain { model.updatePenaltiesEnabled(false) }
        start(30)
        disappearUntilViolation()
        assertEquals(1, model.violationCount.value)
        assertTrue(model.timerSeconds.value <= 30)
        assertEquals(GameState.HoldingPose, model.gameState.value)
    }

    @Test fun missingReferencePoseCannotStartHolding() {
        begin(initialPose = PoseLandmarks())
        awaitCondition("missing reference failure") { model.gameState.value == GameState.Failed }
        assertEquals(0, model.violationCount.value)
        assertEquals(GameState.Failed, model.sessionSummary.value!!.result)
    }

    @Test fun randomTimerUsesChosenDurationAndCompletes() {
        onMain {
            model.updateTimerMode(TimerMode.Random)
            model.updateRandomDurationRangeSeconds(1, 1)
        }
        start()
        assertEquals(0, model.timerSeconds.value)
        frame(advanceMs = 1_100)
        awaitCondition("random session success") { model.gameState.value == GameState.Success }
        assertEquals(1, model.sessionSummary.value!!.initialTimerSeconds)
        assertTrue(model.sessionSummary.value!!.actualTimerSeconds >= 1)
    }

    @Test fun faceToCameraRuleRejectsFramesWithoutVisibleFace() {
        onMain { model.updateFaceCheckMode(FaceCheckMode.FaceToCamera); model.updateMaxViolations(1) }
        start()
        repeat(8) { if (model.gameState.value == GameState.HoldingPose) frame() }
        assertEquals(GameState.Failed, model.gameState.value)
        assertEquals(1, model.sessionSummary.value!!.violationCounts.face)
    }

    @Test fun faceAwayRuleAcceptsFramesWithoutVisibleFace() {
        onMain { model.updateFaceCheckMode(FaceCheckMode.FaceAwayFromCamera) }
        start()
        repeat(8) { frame() }
        assertEquals(GameState.HoldingPose, model.gameState.value)
        assertEquals(0, model.violationCount.value)
    }

    @Test fun unavailableFaceCropStopsAsTechnicalFailureWithoutPenalty() {
        onMain { model.updateFaceCheckMode(FaceCheckMode.FaceAwayFromCamera) }
        start()
        // Crops below the production 96-pixel minimum cannot run face inference.
        frame(width = 64, height = 64)
        frame(advanceMs = 5_100, width = 64, height = 64)
        assertEquals(GameState.Failed, model.gameState.value)
        assertTrue(model.sessionSummary.value!!.stoppedByTechnicalError)
        assertEquals(0, model.violationCount.value)
        assertFalse(cues.any { it.cue == AudioCue.DefeatTryAgain })
    }
}
