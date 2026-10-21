package com.incident201.poseguard.viewmodel

import com.incident201.poseguard.tracker.FaceDetectionStatus
import com.incident201.poseguard.tracker.MovementTracker

internal enum class RuleViolationType { Drift, Motion, PersonDisappeared, FaceNotMatchingMode }

internal fun MovementTracker.Violation.toRuleViolationType(): RuleViolationType? = when (this) {
    is MovementTracker.Violation.DriftLimitExceeded -> RuleViolationType.Drift
    is MovementTracker.Violation.MotionLimitExceeded -> RuleViolationType.Motion
    is MovementTracker.Violation.PersonDisappeared -> RuleViolationType.PersonDisappeared
    else -> null
}

/** The subset of [GameSettings] that decides penalties and defeat. */
internal data class SessionRuleConfig(
    val maxViolations: Int,
    val minimumPenaltyIntervalSeconds: Int,
    val penaltiesEnabled: Boolean,
    val firstViolationPenaltyMinutes: Int,
    val secondViolationPenaltyMinutes: Int,
    val thirdViolationPenaltyMinutes: Int,
    val subsequentViolationPenaltyMinutes: Int,
    val faceCheckMode: FaceCheckMode
) {
    fun penaltyMinutesForViolation(violationIndex: Int): Int = when (violationIndex) {
        1 -> firstViolationPenaltyMinutes
        2 -> secondViolationPenaltyMinutes
        3 -> thirdViolationPenaltyMinutes
        else -> subsequentViolationPenaltyMinutes
    }.coerceIn(0, 9999)

    companion object {
        fun from(settings: GameSettings) = SessionRuleConfig(
            maxViolations = settings.maxViolations,
            minimumPenaltyIntervalSeconds = settings.minimumPenaltyIntervalSeconds,
            penaltiesEnabled = settings.penaltiesEnabled,
            firstViolationPenaltyMinutes = settings.firstViolationPenaltyMinutes,
            secondViolationPenaltyMinutes = settings.secondViolationPenaltyMinutes,
            thirdViolationPenaltyMinutes = settings.thirdViolationPenaltyMinutes,
            subsequentViolationPenaltyMinutes = settings.subsequentViolationPenaltyMinutes,
            faceCheckMode = settings.faceCheckMode
        )
    }
}

internal const val FACE_FAIL_FRAMES_THRESHOLD = 5

/**
 * Violation counting, penalty rate limiting and the face rule, without locks or side effects.
 * The live session and the challenge replay share it, so both reach the same decisions.
 */
internal class SessionRuleEngine {
    sealed class Decision {
        /** Rate limited by the minimum penalty interval. */
        object Ignored : Decision()
        data class Penalty(val type: RuleViolationType, val violationIndex: Int, val minutes: Int) : Decision()
        data class Defeat(val type: RuleViolationType, val violationIndex: Int) : Decision()
    }

    var violationCount: Int = 0
        private set
    var counts: RuleViolationCounts = RuleViolationCounts()
        private set
    private var lastPenaltyAtMs = 0L
    private var consecutiveFaceFailFrames = 0

    fun reset() {
        violationCount = 0
        counts = RuleViolationCounts()
        lastPenaltyAtMs = 0L
        consecutiveFaceFailFrames = 0
    }

    fun resetFaceFailFrames() {
        consecutiveFaceFailFrames = 0
    }

    fun isRateLimited(nowMs: Long, config: SessionRuleConfig): Boolean =
        lastPenaltyAtMs > 0L && nowMs - lastPenaltyAtMs < config.minimumPenaltyIntervalSeconds * 1000L

    /** Counts a violation that passed [isRateLimited] and the caller's own session-state checks. */
    fun register(type: RuleViolationType, nowMs: Long, config: SessionRuleConfig): Decision {
        violationCount += 1
        counts = when (type) {
            RuleViolationType.Drift -> counts.copy(drift = counts.drift + 1)
            RuleViolationType.Motion -> counts.copy(motion = counts.motion + 1)
            RuleViolationType.FaceNotMatchingMode -> counts.copy(face = counts.face + 1)
            RuleViolationType.PersonDisappeared -> counts.copy(disappeared = counts.disappeared + 1)
        }
        if (violationCount >= config.maxViolations) {
            return Decision.Defeat(type, violationCount)
        }
        lastPenaltyAtMs = nowMs
        return Decision.Penalty(type, violationCount, config.penaltyMinutesForViolation(violationCount))
    }

    /** Returns true when the face rule has failed for enough consecutive frames. */
    fun onFaceStatus(mode: FaceCheckMode, status: FaceDetectionStatus): Boolean {
        val failed = when (mode) {
            FaceCheckMode.Disabled -> return false
            FaceCheckMode.FaceToCamera -> when (status) {
                FaceDetectionStatus.FaceVisible -> false
                FaceDetectionStatus.FaceNotVisible -> true
                FaceDetectionStatus.NotProcessed, FaceDetectionStatus.Error -> return false
            }
            FaceCheckMode.FaceAwayFromCamera -> when (status) {
                FaceDetectionStatus.FaceNotVisible -> false
                FaceDetectionStatus.FaceVisible -> true
                FaceDetectionStatus.NotProcessed, FaceDetectionStatus.Error -> return false
            }
        }
        if (!failed) {
            consecutiveFaceFailFrames = 0
            return false
        }
        consecutiveFaceFailFrames += 1
        if (consecutiveFaceFailFrames >= FACE_FAIL_FRAMES_THRESHOLD) {
            consecutiveFaceFailFrames = 0
            return true
        }
        return false
    }
}
