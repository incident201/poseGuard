package com.incident201.poseguard.challenge

import com.incident201.poseguard.tracker.FaceDetectionStatus
import com.incident201.poseguard.viewmodel.RuleViolationCounts

/** Why a challenge session ended. */
enum class SessionEndCause {
    Completed,
    TooManyViolations,
    CameraNoFrame,
    CameraNoBody,
    FaceCheckUnavailable,
    Surrendered
}

enum class TraceViolationType { Drift, Motion, PersonDisappeared, Face }

/** A violation as the executor's app recorded it; the author's replay must reproduce it. */
data class TraceViolationEvent(
    val atMs: Long,
    val type: TraceViolationType,
    val violationIndex: Int,
    val penaltyMinutes: Int,
    val defeat: Boolean
)

/** Recorded tracking from the moment the reference pose was taken. */
class SessionTrace(
    val referenceTimestampMs: Long,
    val reference: TracePose,
    val clockStartedAtMs: Long,
    val initialFaceStatus: FaceDetectionStatus,
    /** [TraceFrameWriter] stream. */
    val frameData: ByteArray,
    val frameCount: Int,
    val truncated: Boolean,
    /** Camera frame size the normalized landmarks refer to; 0 when unknown. */
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
) {
    fun frames(): List<TraceFrame> = TraceFrameReader.decode(frameData, referenceTimestampMs, reference)
}

data class ChallengeReport(
    val reportId: Blob,
    val challengeId: Blob,
    val attempt: Int,
    val appVersion: String,
    val startedAtWallMs: Long,
    val endedAtWallMs: Long,
    /** Session clock (elapsedRealtime) at start and end, for the wall-clock plausibility check. */
    val startedAtMonoMs: Long,
    val endedAtMonoMs: Long,
    val executorName: String,
    val executorComment: String,
    val endCause: SessionEndCause,
    val initialTimerSeconds: Int,
    val actualTimerSeconds: Int,
    val counts: RuleViolationCounts,
    val events: List<TraceViolationEvent>,
    val intifaceConnected: Boolean,
    /** Null when the session ended before the pose was taken. */
    val trace: SessionTrace?
) {
    val success: Boolean get() = endCause == SessionEndCause.Completed
}
