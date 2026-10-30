package com.incident201.poseguard.challenge

import com.incident201.poseguard.tracker.*
import com.incident201.poseguard.viewmodel.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportVerifierTest {
    private fun spec(rules: ChallengeRules = ChallengeRules(timer = TimerSpec(exactSeconds = 2))) =
        ChallengeSpec(Blob(ByteArray(16)), "Test", "", "", 0, rules, ChallengeVisibility(), 2, Blob(ByteArray(32)), Blob(ByteArray(0)))

    private fun pose(jitter: Float = 0f) = PoseLandmarks.fromAllLandmarks(List(33) { i ->
        Point3D(if (i % 2 == 0) 0.6f + jitter else 0.4f + jitter, 0.1f + i * 0.02f, 0f, 0.9f, 0.95f)
    }).quantizedForTrace()

    private fun report(spec: ChallengeSpec = spec(), duration: Long = 2000, static: Boolean = false,
                       face: FaceDetectionStatus = FaceDetectionStatus.FaceVisible,
                       missing: Boolean = false, cause: SessionEndCause = SessionEndCause.Completed,
                       faceAt: ((Long) -> FaceDetectionStatus)? = null): ChallengeReport {
        val reference = pose()
        val recorder = ChallengeSessionRecorder(spec, 1, "test", 9000, 19000)
        recorder.startTrace(9900, reference, 10000, FaceDetectionStatus.FaceVisible)
        val engine = SessionRuleEngine()
        val config = SessionRuleConfig.from(spec.rules.applyTo(GameSettings()))
        val tracker = MovementTracker().apply {
            driftThresholdFactor = spec.rules.driftThresholdFactor
            motionThresholdFactor = spec.rules.motionThresholdFactor
            wristDriftWeight = spec.rules.wristDriftWeight
            startTracking(reference)
        }
        var end = 10000L
        var defeated = false
        fun violation(type: RuleViolationType, at: Long) {
            if (defeated || engine.isRateLimited(at, config)) return
            val kind = when (type) {
                RuleViolationType.Drift -> TraceViolationType.Drift
                RuleViolationType.Motion -> TraceViolationType.Motion
                RuleViolationType.PersonDisappeared -> TraceViolationType.PersonDisappeared
                RuleViolationType.FaceNotMatchingMode -> TraceViolationType.Face
            }
            when(val decision = engine.register(type, at, config)) {
                is SessionRuleEngine.Decision.Defeat -> {
                    recorder.violation(TraceViolationEvent(at, kind, decision.violationIndex, 0, true))
                    defeated = true
                }
                is SessionRuleEngine.Decision.Penalty -> recorder.violation(TraceViolationEvent(at, kind, decision.violationIndex, decision.minutes, false))
                else -> Unit
            }
        }
        for (step in 1..(duration / 50).toInt()) {
            end = 10000L + step * 50
            val frameFace = faceAt?.invoke(end - 10000) ?: face
            val framePose = if(missing) PoseLandmarks() else pose(if(static) 0f else (step % 7) * 0.00005f)
            recorder.frame(end - 10, end, frameFace, framePose)
            tracker.trackFrame(framePose, end - 10).violation.toRuleViolationType()?.let { violation(it, end) }
            if (!defeated && engine.onFaceStatus(config.faceCheckMode, frameFace)) violation(RuleViolationType.FaceNotMatchingMode, end)
            if (defeated) break
        }
        return recorder.finish(if (defeated) SessionEndCause.TooManyViolations else cause,
            end, end + 10000, spec.rules.timer.exactSeconds, ((end - 10000) / 1000).toInt(), engine.counts, false)
    }

    @Test fun liveInputsReplayToSameSuccessfulOutcome() {
        assertEquals(Verdict(VerdictKind.Confirmed), ReplayReportVerifier.verify(report(), spec()))
    }
    @Test fun faceAndDisappearanceDefeatsReplayWithSameEvents() {
        val faceSpec = spec(ChallengeRules(timer = TimerSpec(exactSeconds = 60), faceCheckMode = FaceCheckMode.FaceToCamera,
            maxViolations = 2, minimumPenaltyIntervalSeconds = 0))
        assertEquals(Verdict(VerdictKind.Confirmed), ReplayReportVerifier.verify(report(faceSpec, face = FaceDetectionStatus.FaceNotVisible), faceSpec))
        val missingSpec = spec(ChallengeRules(timer = TimerSpec(exactSeconds = 60), maxViolations = 2, minimumPenaltyIntervalSeconds = 0))
        assertEquals(Verdict(VerdictKind.Confirmed), ReplayReportVerifier.verify(report(missingSpec, duration = 4000, missing = true), missingSpec))
    }
    @Test fun alteredCountsEventsDurationOutcomeAndTaskAreRejected() {
        val original = report()
        val edits = listOf(original.copy(counts = RuleViolationCounts(face = 1)),
            original.copy(actualTimerSeconds = 999), original.copy(initialTimerSeconds = 1),
            original.copy(endCause = SessionEndCause.TooManyViolations),
            original.copy(challengeId = Blob(ByteArray(16) { 3 })),
            original.copy(events = listOf(TraceViolationEvent(11000, TraceViolationType.Face, 1, 0, false))))
        edits.forEach { assertEquals(VerdictKind.Mismatch, ReplayReportVerifier.verify(it, spec()).kind) }
    }
    @Test fun penaltyExtensionsPreventEarlySuccess() {
        val s = spec(ChallengeRules(timer = TimerSpec(exactSeconds = 1), maxViolations = 99,
            faceCheckMode = FaceCheckMode.FaceToCamera, minimumPenaltyIntervalSeconds = 5, firstViolationPenaltyMinutes = 1))
        val r = report(s, duration = 2000, face = FaceDetectionStatus.FaceNotVisible)
        assertEquals(VerdictKind.Mismatch, ReplayReportVerifier.verify(r, s).kind)
        assertEquals(VerdictKind.Confirmed, ReplayReportVerifier.verify(r.copy(endCause = SessionEndCause.Surrendered), s).kind)
        val disabled = s.copy(rules = s.rules.copy(penaltiesEnabled = false))
        assertEquals(VerdictKind.Confirmed, ReplayReportVerifier.verify(report(disabled, duration = 1000, face = FaceDetectionStatus.FaceNotVisible), disabled).kind)
    }
    @Test fun staticTruncatedClockSkewAndTooManyAttemptsAreSuspicious() {
        val s = spec(ChallengeRules(timer = TimerSpec(exactSeconds = 12)))
        val r = report(s, 12000, static = true)
        assertEquals(VerdictKind.Suspicious, ReplayReportVerifier.verify(r, s).kind)
        val normal = report()
        val t = normal.trace!!
        val truncated = SessionTrace(t.referenceTimestampMs,t.reference,t.clockStartedAtMs,t.initialFaceStatus,t.frameData,t.frameCount,true)
        for (edited in listOf(normal.copy(trace = truncated), normal.copy(endedAtWallMs = normal.endedAtWallMs + 6000), normal.copy(attempt = 3))) {
            assertEquals(VerdictKind.Suspicious, ReplayReportVerifier.verify(edited, spec()).kind)
        }
    }
    @Test fun noTraceCannotConfirmSuccess() {
        assertEquals(VerdictKind.Mismatch, ReplayReportVerifier.verify(report().copy(trace = null), spec()).kind)
        val early = report().copy(trace = null, actualTimerSeconds = 0, endCause = SessionEndCause.Surrendered)
        assertEquals(VerdictKind.Unverified, ReplayReportVerifier.verify(early, spec()).kind)
    }

    @Test fun cameraTimeoutIsUnverifiedWithoutHidingOtherInconsistencies() {
        val s = spec(ChallengeRules(timer = TimerSpec(exactSeconds = 60)))
        val original = report(s, duration = 500, cause = SessionEndCause.CameraNoFrame)
        val stopped = original.copy(endedAtMonoMs = original.endedAtMonoMs + 5_100,
            endedAtWallMs = original.endedAtWallMs + 5_100, actualTimerSeconds = 5)
        assertEquals(Verdict(VerdictKind.Unverified, listOf("camera_no_frame")), ReplayReportVerifier.verify(stopped, s))
        assertEquals(VerdictKind.Mismatch, ReplayReportVerifier.verify(stopped.copy(counts = RuleViolationCounts(face = 1)), s).kind)
        assertEquals(VerdictKind.Suspicious, ReplayReportVerifier.verify(stopped.copy(endCause = SessionEndCause.Surrendered), s).kind)
        assertEquals(VerdictKind.Suspicious, ReplayReportVerifier.verify(stopped.copy(endedAtWallMs = stopped.endedAtWallMs + 6_000), s).kind)
        assertEquals(VerdictKind.Mismatch, ReplayReportVerifier.verify(stopped.copy(endCause = SessionEndCause.Completed), s).kind)
    }

    @Test fun emptyTraceCannotConfirmSuccessOrHideAlteredEvents() {
        val s = spec(ChallengeRules(timer = TimerSpec(exactSeconds = 60)))
        val stopped = report(s, duration = 0, cause = SessionEndCause.Surrendered)
        assertEquals(0, stopped.trace!!.frameCount)
        assertEquals(Verdict(VerdictKind.Unverified, listOf("no_trace")), ReplayReportVerifier.verify(stopped, s))
        assertEquals(VerdictKind.Mismatch, ReplayReportVerifier.verify(stopped.copy(counts = RuleViolationCounts(face = 1)), s).kind)
        val successWithoutFrames = stopped.copy(endCause = SessionEndCause.Completed,
            endedAtMonoMs = 70_000, endedAtWallMs = 80_000, actualTimerSeconds = 60)
        assertEquals(VerdictKind.Mismatch, ReplayReportVerifier.verify(successWithoutFrames, s).kind)
        assertEquals(VerdictKind.Suspicious, ReplayReportVerifier.verify(
            successWithoutFrames.copy(endCause = SessionEndCause.Surrendered), s).kind)
        val trace = stopped.trace!!
        val truncated = SessionTrace(trace.referenceTimestampMs, trace.reference, trace.clockStartedAtMs,
            trace.initialFaceStatus, trace.frameData, trace.frameCount, true)
        assertEquals(VerdictKind.Suspicious, ReplayReportVerifier.verify(stopped.copy(trace = truncated), s).kind)
    }
    @Test fun malformedFrameStreamAndFrameCountAreRejected() {
        val r = report(); val t = r.trace!!
        val bad = SessionTrace(t.referenceTimestampMs,t.reference,t.clockStartedAtMs,t.initialFaceStatus,t.frameData.copyOf(t.frameData.size - 1),t.frameCount,false)
        assertEquals(VerdictKind.Mismatch, ReplayReportVerifier.verify(r.copy(trace = bad), spec()).kind)
        val count = SessionTrace(t.referenceTimestampMs,t.reference,t.clockStartedAtMs,t.initialFaceStatus,t.frameData,t.frameCount + 1,false)
        assertEquals(VerdictKind.Mismatch, ReplayReportVerifier.verify(r.copy(trace = count), spec()).kind)
    }
    @Test fun faceOutageCanOnlyEndAsTechnicalInterruption() {
        val s = spec(ChallengeRules(timer = TimerSpec(exactSeconds = 60), faceCheckMode = FaceCheckMode.FaceToCamera))
        val r = report(s, duration = 4500, face = FaceDetectionStatus.Error, cause = SessionEndCause.FaceCheckUnavailable)
        val later = r.copy(endedAtMonoMs = 15050, endedAtWallMs = 25050, actualTimerSeconds = 5)
        assertEquals(VerdictKind.Confirmed, ReplayReportVerifier.verify(later, s).kind)
        assertEquals(VerdictKind.Mismatch, ReplayReportVerifier.verify(later.copy(endCause = SessionEndCause.Completed), s).kind)
    }

    @Test fun faceRecoveryAfterDeadlineAllowsCompletionWithoutSuspiciousTiming() {
        val s = spec(ChallengeRules(timer = TimerSpec(exactSeconds = 60), faceCheckMode = FaceCheckMode.FaceToCamera))
        val r = report(s, duration = 63_250, faceAt = { elapsed ->
            if (elapsed in 59_000 until 63_000) FaceDetectionStatus.Error else FaceDetectionStatus.FaceVisible
        })
        assertEquals(Verdict(VerdictKind.Confirmed), ReplayReportVerifier.verify(r, s))
        val late = report(s, duration = 64_500, faceAt = { elapsed ->
            if (elapsed in 59_000 until 63_000) FaceDetectionStatus.Error else FaceDetectionStatus.FaceVisible
        })
        assertTrue(ReplayReportVerifier.verify(late, s).reasons.contains("timing"))
        assertTrue(ReplayReportVerifier.verify(report(duration = 3500), spec()).reasons.contains("timing"))
    }

    @Test fun externalInterruptionAtFaceDeadlineIsAllowedButContinuedTrackingIsNot() {
        val s = spec(ChallengeRules(timer = TimerSpec(exactSeconds = 60),
            faceCheckMode = FaceCheckMode.FaceToCamera, intifaceRequired = true))
        val beforeDeadline = report(s, duration = 4950, face = FaceDetectionStatus.Error,
            cause = SessionEndCause.IntifaceUnavailable)
        val atDeadline = beforeDeadline.copy(endedAtMonoMs = 15050, endedAtWallMs = 25050, actualTimerSeconds = 5)
        for (cause in listOf(SessionEndCause.IntifaceUnavailable, SessionEndCause.CameraNoFrame, SessionEndCause.Surrendered)) {
            assertEquals(Verdict(VerdictKind.Confirmed), ReplayReportVerifier.verify(atDeadline.copy(endCause = cause), s))
            val continued = report(s, duration = 5500, face = FaceDetectionStatus.Error, cause = cause)
            assertEquals(VerdictKind.Mismatch, ReplayReportVerifier.verify(continued, s).kind)
        }
    }

    @Test fun requiredIntifaceCannotConfirmSuccessWithoutAConnection() {
        val s = spec().copy(rules = spec().rules.copy(intifaceRequired = true))
        val disconnected = report(s)
        assertEquals(Verdict(VerdictKind.Mismatch, listOf("intiface")), ReplayReportVerifier.verify(disconnected, s))
        assertEquals(Verdict(VerdictKind.Confirmed), ReplayReportVerifier.verify(disconnected.copy(intifaceConnected = true), s))
        val interrupted = report(s, duration = 500, cause = SessionEndCause.IntifaceUnavailable)
        assertEquals(Verdict(VerdictKind.Confirmed), ReplayReportVerifier.verify(interrupted, s))
        assertEquals(VerdictKind.Mismatch, ReplayReportVerifier.verify(interrupted.copy(intifaceConnected = true), s).kind)
        assertEquals(VerdictKind.Mismatch, ReplayReportVerifier.verify(interrupted, spec()).kind)
        assertEquals(VerdictKind.Unverified, ReplayReportVerifier.verify(interrupted.copy(trace = null, actualTimerSeconds = 0), s).kind)
    }
    @Test fun backwardsFramesAndLongGapsCannotBeConfirmed() {
        val r = report(); val t = r.trace!!
        for (offsets in listOf(listOf(500L, 400L), listOf(50L, 3000L))) {
            val writer = TraceFrameWriter(t.referenceTimestampMs, t.reference, 10000)
            offsets.forEach { offset -> writer.append(TraceFrame(10000 + offset, 10000 + offset, FaceDetectionStatus.FaceVisible, t.reference)) }
            val trace = SessionTrace(t.referenceTimestampMs,t.reference,t.clockStartedAtMs,t.initialFaceStatus,writer.toByteArray(),writer.frameCount,false)
            val changed = r.copy(trace = trace, endedAtMonoMs = 13000, endedAtWallMs = 23000, actualTimerSeconds = 3)
            assertEquals(VerdictKind.Suspicious, ReplayReportVerifier.verify(changed, spec()).kind)
        }
    }

    @Test fun overflowingVarintIsRejected() {
        val source = com.incident201.poseguard.challenge.codec.ByteSource(ByteArray(10) { if (it == 9) 2 else 0x80.toByte() })
        assertTrue(runCatching { source.varint() }.exceptionOrNull() is com.incident201.poseguard.challenge.codec.TlvFormatException)
    }

}
