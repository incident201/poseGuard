package com.incident201.poseguard.challenge

import com.incident201.poseguard.challenge.codec.TlvFormatException
import com.incident201.poseguard.tracker.FaceDetectionStatus
import com.incident201.poseguard.tracker.MovementTracker
import com.incident201.poseguard.tracker.PoseLandmarks
import com.incident201.poseguard.viewmodel.FaceCheckMode
import com.incident201.poseguard.viewmodel.GameSettings
import com.incident201.poseguard.viewmodel.RuleViolationCounts
import com.incident201.poseguard.viewmodel.RuleViolationType
import com.incident201.poseguard.viewmodel.SessionClock
import com.incident201.poseguard.viewmodel.SessionRuleConfig
import com.incident201.poseguard.viewmodel.SessionRuleEngine
import com.incident201.poseguard.viewmodel.toRuleViolationType


enum class VerdictKind {
    /** The recording is absent or incomplete, so the outcome cannot be fully verified. */
    Unverified,
    Confirmed,
    Suspicious,
    Mismatch
}

/** [reasons] are short machine-readable codes for the report screen. */
data class Verdict(val kind: VerdictKind, val reasons: List<String> = emptyList())

/** Checks a received report against the challenge it answers, on the author's device. */
fun interface ReportVerifier {
    fun verify(report: ChallengeReport, spec: ChallengeSpec): Verdict
}

/**
 * Checks internal consistency, not camera provenance. A technically consistent synthetic
 * trace may pass; timing/noise checks are heuristics and are therefore only Suspicious.
 * Only call on reports authenticated by ChallengeFiles and off the UI thread.
 */
object ReplayReportVerifier : ReportVerifier {
    override fun verify(report: ChallengeReport, spec: ChallengeSpec): Verdict {
        val mismatch = linkedSetOf<String>()
        val suspicious = linkedSetOf<String>()
        if (report.challengeId != spec.id) mismatch += "task"
        if (report.attempt < 1 || (spec.attemptsAllowed > 0 && report.attempt > spec.attemptsAllowed)) suspicious += "attempts"
        if (report.initialTimerSeconds !in spec.rules.timer.minSeconds..spec.rules.timer.maxSeconds) mismatch += "result"
        if ((spec.rules.intifaceRequired && report.success && !report.intifaceConnected) ||
            (report.endCause == SessionEndCause.IntifaceUnavailable &&
                (!spec.rules.intifaceRequired || report.intifaceConnected))) mismatch += "intiface"
        val start = report.startedAtMonoMs
        val end = report.endedAtMonoMs
        if (start < 0 || end < start || end - start > MAX_SESSION_MS || report.startedAtWallMs < 0 ||
            report.endedAtWallMs < report.startedAtWallMs) return Verdict(VerdictKind.Suspicious, listOf("timing"))
        if (kotlin.math.abs((report.endedAtWallMs - report.startedAtWallMs) - (end - start)) > 5_000L) suspicious += "timing"
        val trace = report.trace
        if (trace == null) {
            if (report.endCause !in setOf(SessionEndCause.Surrendered, SessionEndCause.CameraNoFrame,
                    SessionEndCause.CameraNoBody, SessionEndCause.IntifaceUnavailable) ||
                report.actualTimerSeconds != 0 || report.counts != RuleViolationCounts() || report.events.isNotEmpty()) mismatch += "result"
            return when {
                mismatch.isNotEmpty() -> Verdict(VerdictKind.Mismatch, mismatch.toList())
                suspicious.isNotEmpty() -> Verdict(VerdictKind.Suspicious, suspicious.toList())
                else -> Verdict(VerdictKind.Unverified, listOf("no_trace"))
            }
        }
        if (trace.clockStartedAtMs !in start..end || trace.referenceTimestampMs < 0 ||
            trace.referenceTimestampMs > trace.clockStartedAtMs ||
            trace.clockStartedAtMs - trace.referenceTimestampMs > 1_200L) return Verdict(VerdictKind.Suspicious, listOf("timing"))
        if (trace.truncated) suspicious += "trace"
        val settings = spec.rules.applyTo(GameSettings())
        val config = SessionRuleConfig.from(settings)
        val engine = SessionRuleEngine()
        val clock = SessionClock().apply {
            reset(report.initialTimerSeconds)
            start(trace.clockStartedAtMs)
        }
        val tracker = MovementTracker().apply {
            driftThresholdFactor = spec.rules.driftThresholdFactor
            motionThresholdFactor = spec.rules.motionThresholdFactor
            wristDriftWeight = spec.rules.wristDriftWeight
            startTracking(trace.reference.toPoseLandmarks())
        }
        val events = mutableListOf<TraceViolationEvent>()
        var defeatedAt: Long? = null
        var outageSince: Long? = if (settings.faceCheckMode != FaceCheckMode.Disabled &&
            trace.initialFaceStatus.unavailable()) trace.clockStartedAtMs else null
        var previousTs = trace.referenceTimestampMs
        var previousEvaluation = trace.clockStartedAtMs
        var count = 0
        var staticSince = trace.referenceTimestampMs
        var previousPose: TracePose? = trace.reference
        var impossibleTiming = false
        var completionBlockedUntilMs = trace.clockStartedAtMs

        fun register(type: RuleViolationType, at: Long) {
            if (defeatedAt != null || engine.isRateLimited(at, config)) return
            val traceType = when (type) {
                RuleViolationType.Drift -> TraceViolationType.Drift
                RuleViolationType.Motion -> TraceViolationType.Motion
                RuleViolationType.PersonDisappeared -> TraceViolationType.PersonDisappeared
                RuleViolationType.FaceNotMatchingMode -> TraceViolationType.Face
            }
            when (val decision = engine.register(type, at, config)) {
                is SessionRuleEngine.Decision.Defeat -> {
                    events += TraceViolationEvent(at, traceType, decision.violationIndex, 0, true)
                    defeatedAt = at
                }
                is SessionRuleEngine.Decision.Penalty -> {
                    events += TraceViolationEvent(at, traceType, decision.violationIndex, decision.minutes, false)
                    if (config.penaltiesEnabled) clock.addPenalty(decision.minutes * 60)
                }
                else -> Unit
            }
        }
        try {
            TraceFrameReader.forEach(trace.frameData, trace.referenceTimestampMs, trace.reference) { frame ->
                count++
                if (frame.timestampMs <= previousTs || frame.evaluatedAtMs < previousEvaluation ||
                    frame.timestampMs > frame.evaluatedAtMs || frame.evaluatedAtMs !in trace.clockStartedAtMs..end) {
                    impossibleTiming = true
                    return@forEach
                }
                if (frame.timestampMs - previousTs > 2_000 || frame.timestampMs - previousTs < 5 ||
                    frame.evaluatedAtMs - frame.timestampMs > 2_000) suspicious += "timing"
                if (frame.pose != null && frame.pose == previousPose) {
                    if (frame.timestampMs - staticSince >= 10_000) suspicious += "static"
                } else staticSince = frame.timestampMs
                previousPose = frame.pose
                previousTs = frame.timestampMs
                previousEvaluation = frame.evaluatedAtMs
                if (defeatedAt != null) mismatch += "result"
                // The live timer waits for face detection to recover. Give that
                // recovery the same completion grace as an ordinary deadline.
                if (outageSince != null) completionBlockedUntilMs = frame.evaluatedAtMs
                if (outageSince == null && frame.evaluatedAtMs - completionBlockedUntilMs > 1_000 &&
                    clock.snapshot(frame.evaluatedAtMs - 1_000).finished) suspicious += "timing"
                if (settings.faceCheckMode != FaceCheckMode.Disabled) {
                    if (outageSince?.let { frame.evaluatedAtMs - it >= 5_000 } == true) mismatch += "result"
                    if (frame.faceStatus.unavailable()) {
                        if (outageSince == null) outageSince = frame.evaluatedAtMs
                    } else outageSince = null
                }
                val result = tracker.trackFrame(frame.pose?.toPoseLandmarks() ?: PoseLandmarks(), frame.timestampMs)
                result.violation.toRuleViolationType()?.let { register(it, frame.evaluatedAtMs) }
                if (defeatedAt == null && engine.onFaceStatus(config.faceCheckMode, frame.faceStatus)) {
                    register(RuleViolationType.FaceNotMatchingMode, frame.evaluatedAtMs)
                }
            }
        } catch (e: TlvFormatException) {
            return Verdict(VerdictKind.Mismatch, listOf("trace"))
        }
        if (impossibleTiming) return Verdict(VerdictKind.Suspicious, listOf("timing"))
        if (count != trace.frameCount) mismatch += "trace"
        val emptyTrace = count == 0
        val cameraStopped = report.endCause == SessionEndCause.CameraNoFrame && end - previousTs > 5_000
        if (end - previousTs > 5_000 && !cameraStopped) suspicious += "trace"
        if (report.actualTimerSeconds != clock.snapshot(end).elapsedSeconds) mismatch += "result"
        if (!trace.truncated) {
            if (engine.counts != report.counts || events != report.events) mismatch += "events"
            val faceTimedOut = outageSince?.let { end - it >= 5_000 } == true
            when (report.endCause) {
                SessionEndCause.Completed -> if (emptyTrace || defeatedAt != null || outageSince != null || !clock.snapshot(end).finished || end - previousTs > 5_000) mismatch += "result"
                SessionEndCause.TooManyViolations -> if (defeatedAt == null || end - defeatedAt > 1_000) mismatch += "result"
                SessionEndCause.FaceCheckUnavailable -> if (!faceTimedOut || defeatedAt != null) mismatch += "result"
                SessionEndCause.CameraNoBody -> mismatch += "result" // Only before reference acquisition.
                SessionEndCause.CameraNoFrame, SessionEndCause.Surrendered,
                SessionEndCause.IntifaceUnavailable -> if (defeatedAt != null || faceTimedOut) mismatch += "result"
            }
        }
        return when {
            mismatch.isNotEmpty() -> Verdict(VerdictKind.Mismatch, mismatch.toList())
            suspicious.isNotEmpty() -> Verdict(VerdictKind.Suspicious, suspicious.toList())
            emptyTrace -> Verdict(VerdictKind.Unverified, listOf("no_trace"))
            cameraStopped -> Verdict(VerdictKind.Unverified, listOf("camera_no_frame"))
            else -> Verdict(VerdictKind.Confirmed)
        }
    }

    // A bounded offline session budget, including long penalty extensions.
    private const val MAX_SESSION_MS = 365L * 24 * 60 * 60 * 1000
    private fun FaceDetectionStatus.unavailable() =
        this == FaceDetectionStatus.Error || this == FaceDetectionStatus.NotProcessed
}
