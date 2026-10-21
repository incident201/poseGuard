package com.incident201.poseguard.challenge

import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.tracker.FaceDetectionStatus
import com.incident201.poseguard.tracker.PoseLandmarks
import com.incident201.poseguard.viewmodel.RuleViolationCounts
import java.security.SecureRandom

/**
 * A challenge the executor accepted; its rules replace the user's own for the session.
 * [audioFiles] are playable copies of its embedded clips.
 */
data class ActiveChallenge(val spec: ChallengeSpec, val audioFiles: Map<AudioCue, String> = emptyMap())

/**
 * Collects one challenge session. Not thread-safe: the ViewModel calls it under its
 * processing lock, the same lock that serializes tracking.
 */
class ChallengeSessionRecorder(
    private val spec: ChallengeSpec,
    private val attempt: Int,
    private val appVersion: String,
    private val startedAtMonoMs: Long,
    private val startedAtWallMs: Long,
    private val maxTraceBytes: Int = DEFAULT_MAX_TRACE_BYTES
) {
    private var writer: TraceFrameWriter? = null
    private var referenceTimestampMs = 0L
    private var reference: TracePose? = null
    private var clockStartedAtMs = 0L
    private var initialFaceStatus = FaceDetectionStatus.NotProcessed
    private var imageWidth = 0
    private var imageHeight = 0
    private val events = mutableListOf<TraceViolationEvent>()

    val challengeId: Blob get() = spec.id

    fun startTrace(
        referenceTimestampMs: Long,
        reference: PoseLandmarks,
        clockStartedAtMs: Long,
        initialFaceStatus: FaceDetectionStatus,
        imageWidth: Int = 0,
        imageHeight: Int = 0
    ) {
        val tracePose = TracePose.from(reference) ?: return
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.referenceTimestampMs = referenceTimestampMs
        this.reference = tracePose
        this.clockStartedAtMs = clockStartedAtMs
        this.initialFaceStatus = initialFaceStatus
        writer = TraceFrameWriter(referenceTimestampMs, tracePose, maxTraceBytes)
        events.clear()
    }

    /** [trackingPose] must be the quantized pose the tracker receives for this frame. */
    fun frame(timestampMs: Long, evaluatedAtMs: Long, faceStatus: FaceDetectionStatus, trackingPose: PoseLandmarks) {
        writer?.append(TraceFrame(timestampMs, evaluatedAtMs, faceStatus, TracePose.from(trackingPose)))
    }

    fun violation(event: TraceViolationEvent) {
        if (writer != null) events += event
    }

    fun finish(
        endCause: SessionEndCause,
        endedAtMonoMs: Long,
        endedAtWallMs: Long,
        initialTimerSeconds: Int,
        actualTimerSeconds: Int,
        counts: RuleViolationCounts,
        intifaceConnected: Boolean
    ): ChallengeReport {
        val w = writer
        val ref = reference
        return ChallengeReport(
            reportId = Blob(ByteArray(ChallengeSpec.ID_SIZE).also(random::nextBytes)),
            challengeId = spec.id,
            attempt = attempt,
            appVersion = appVersion,
            startedAtWallMs = startedAtWallMs,
            endedAtWallMs = endedAtWallMs,
            startedAtMonoMs = startedAtMonoMs,
            endedAtMonoMs = endedAtMonoMs,
            executorName = "",
            executorComment = "",
            endCause = endCause,
            initialTimerSeconds = initialTimerSeconds,
            actualTimerSeconds = actualTimerSeconds,
            counts = counts,
            events = events.toList(),
            intifaceConnected = intifaceConnected,
            trace = if (w != null && ref != null) {
                SessionTrace(
                    referenceTimestampMs = referenceTimestampMs,
                    reference = ref,
                    clockStartedAtMs = clockStartedAtMs,
                    initialFaceStatus = initialFaceStatus,
                    frameData = w.toByteArray(),
                    frameCount = w.frameCount,
                    truncated = w.truncated,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight
                )
            } else {
                null
            }
        )
    }

    companion object {
        /** About ten hours at 20 fps; longer sessions keep their result but lose the rest of the trace. */
        const val DEFAULT_MAX_TRACE_BYTES = 48 * 1024 * 1024
        private val random = SecureRandom()
    }
}
