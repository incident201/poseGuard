package com.incident201.poseguard.viewmodel

import com.incident201.poseguard.tracker.FaceDetectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRuleEngineTest {
    private val config = SessionRuleConfig.from(
        GameSettings(
            maxViolations = 3,
            minimumPenaltyIntervalSeconds = 5,
            firstViolationPenaltyMinutes = 1,
            secondViolationPenaltyMinutes = 2,
            faceCheckMode = FaceCheckMode.FaceToCamera
        )
    )

    @Test fun penaltiesFollowTheScheduleUntilDefeat() {
        val engine = SessionRuleEngine()
        assertEquals(SessionRuleEngine.Decision.Penalty(RuleViolationType.Drift, 1, 1), engine.register(RuleViolationType.Drift, 1_000L, config))
        assertEquals(SessionRuleEngine.Decision.Penalty(RuleViolationType.Motion, 2, 2), engine.register(RuleViolationType.Motion, 7_000L, config))
        assertEquals(SessionRuleEngine.Decision.Defeat(RuleViolationType.Drift, 3), engine.register(RuleViolationType.Drift, 13_000L, config))
        assertEquals(RuleViolationCounts(drift = 2, motion = 1), engine.counts)
    }

    @Test fun penaltyIntervalRateLimitsFromTheLastPenalty() {
        val engine = SessionRuleEngine()
        assertFalse(engine.isRateLimited(1_000L, config))
        engine.register(RuleViolationType.Drift, 1_000L, config)
        assertTrue(engine.isRateLimited(5_999L, config))
        assertFalse(engine.isRateLimited(6_000L, config))
    }

    @Test fun faceRuleNeedsFiveConsecutiveFailures() {
        val engine = SessionRuleEngine()
        repeat(4) { assertFalse(engine.onFaceStatus(FaceCheckMode.FaceToCamera, FaceDetectionStatus.FaceNotVisible)) }
        // Unavailable results neither count nor reset the streak.
        assertFalse(engine.onFaceStatus(FaceCheckMode.FaceToCamera, FaceDetectionStatus.Error))
        assertTrue(engine.onFaceStatus(FaceCheckMode.FaceToCamera, FaceDetectionStatus.FaceNotVisible))
        repeat(4) { engine.onFaceStatus(FaceCheckMode.FaceToCamera, FaceDetectionStatus.FaceNotVisible) }
        assertFalse(engine.onFaceStatus(FaceCheckMode.FaceToCamera, FaceDetectionStatus.FaceVisible))
        assertFalse(engine.onFaceStatus(FaceCheckMode.FaceToCamera, FaceDetectionStatus.FaceNotVisible))
        assertFalse(engine.onFaceStatus(FaceCheckMode.Disabled, FaceDetectionStatus.FaceNotVisible))
    }
}
