package com.incident201.poseguard.challenge.codec

import com.incident201.poseguard.challenge.Blob
import com.incident201.poseguard.challenge.ChallengeReport
import com.incident201.poseguard.challenge.SessionEndCause
import com.incident201.poseguard.challenge.SessionTrace
import com.incident201.poseguard.challenge.TRACE_LANDMARK_INDICES
import com.incident201.poseguard.challenge.TracePose
import com.incident201.poseguard.challenge.TraceViolationEvent
import com.incident201.poseguard.challenge.TraceViolationType
import com.incident201.poseguard.tracker.FaceDetectionStatus
import com.incident201.poseguard.viewmodel.RuleViolationCounts

/** TLV layout of [ChallengeReport]. Tags are part of the file format: never reuse or renumber them. */
object ReportCodec {
    private const val REPORT_ID = 1
    private const val CHALLENGE_ID = 2
    private const val ATTEMPT = 3
    private const val APP_VERSION = 4
    private const val STARTED_WALL = 5
    private const val ENDED_WALL = 6
    private const val STARTED_MONO = 7
    private const val ENDED_MONO = 8
    private const val EXECUTOR_NAME = 9
    private const val EXECUTOR_COMMENT = 10
    private const val END_CAUSE = 11
    private const val INITIAL_TIMER = 12
    private const val ACTUAL_TIMER = 13
    private const val COUNTS = 14
    private const val EVENT = 15
    private const val INTIFACE_CONNECTED = 16
    private const val TRACE = 17

    private const val T_REFERENCE_TS = 1
    private const val T_REFERENCE_POSE = 2
    private const val T_CLOCK_START = 3
    private const val T_INITIAL_FACE = 4
    private const val T_FRAMES = 5
    private const val T_FRAME_COUNT = 6
    private const val T_TRUNCATED = 7
    private const val T_IMAGE_WIDTH = 8
    private const val T_IMAGE_HEIGHT = 9

    const val MAX_TEXT_LENGTH = 500

    fun encode(report: ChallengeReport): ByteArray = TlvWriter().apply {
        bytes(REPORT_ID, report.reportId.bytes)
        bytes(CHALLENGE_ID, report.challengeId.bytes)
        int(ATTEMPT, report.attempt)
        string(APP_VERSION, report.appVersion)
        long(STARTED_WALL, report.startedAtWallMs)
        long(ENDED_WALL, report.endedAtWallMs)
        long(STARTED_MONO, report.startedAtMonoMs)
        long(ENDED_MONO, report.endedAtMonoMs)
        string(EXECUTOR_NAME, report.executorName)
        string(EXECUTOR_COMMENT, report.executorComment)
        enum(END_CAUSE, report.endCause)
        int(INITIAL_TIMER, report.initialTimerSeconds)
        int(ACTUAL_TIMER, report.actualTimerSeconds)
        message(COUNTS) {
            int(1, report.counts.drift)
            int(2, report.counts.motion)
            int(3, report.counts.face)
            int(4, report.counts.disappeared)
        }
        report.events.forEach { event ->
            message(EVENT) {
                long(1, event.atMs)
                enum(2, event.type)
                int(3, event.violationIndex)
                int(4, event.penaltyMinutes)
                bool(5, event.defeat)
            }
        }
        bool(INTIFACE_CONNECTED, report.intifaceConnected)
        report.trace?.let { trace ->
            message(TRACE) {
                long(T_REFERENCE_TS, trace.referenceTimestampMs)
                bytes(T_REFERENCE_POSE, encodePose(trace.reference))
                long(T_CLOCK_START, trace.clockStartedAtMs)
                enum(T_INITIAL_FACE, trace.initialFaceStatus)
                bytes(T_FRAMES, trace.frameData)
                int(T_FRAME_COUNT, trace.frameCount)
                bool(T_TRUNCATED, trace.truncated)
                int(T_IMAGE_WIDTH, trace.imageWidth)
                int(T_IMAGE_HEIGHT, trace.imageHeight)
            }
        }
    }.toByteArray()

    fun decode(data: ByteArray): ChallengeReport {
        val r = TlvReader(data)
        val counts = r.message(COUNTS)
        return ChallengeReport(
            reportId = Blob(r.requireBytes(REPORT_ID)),
            challengeId = Blob(r.requireBytes(CHALLENGE_ID)),
            attempt = r.int(ATTEMPT) ?: 0,
            appVersion = r.string(APP_VERSION).orEmpty().take(40),
            startedAtWallMs = r.long(STARTED_WALL) ?: 0L,
            endedAtWallMs = r.long(ENDED_WALL) ?: 0L,
            startedAtMonoMs = r.long(STARTED_MONO) ?: 0L,
            endedAtMonoMs = r.long(ENDED_MONO) ?: 0L,
            executorName = r.string(EXECUTOR_NAME).orEmpty().take(MAX_TEXT_LENGTH),
            executorComment = r.string(EXECUTOR_COMMENT).orEmpty().take(MAX_TEXT_LENGTH),
            endCause = r.enum(END_CAUSE, SessionEndCause.Surrendered),
            initialTimerSeconds = r.int(INITIAL_TIMER) ?: 0,
            actualTimerSeconds = r.int(ACTUAL_TIMER) ?: 0,
            counts = RuleViolationCounts(
                drift = counts?.int(1) ?: 0,
                motion = counts?.int(2) ?: 0,
                face = counts?.int(3) ?: 0,
                disappeared = counts?.int(4) ?: 0
            ),
            events = r.repeated(EVENT).map { entry ->
                val e = TlvReader(entry)
                TraceViolationEvent(
                    atMs = e.long(1) ?: 0L,
                    type = e.enum(2, TraceViolationType.Drift),
                    violationIndex = e.int(3) ?: 0,
                    penaltyMinutes = e.int(4) ?: 0,
                    defeat = e.bool(5) ?: false
                )
            },
            intifaceConnected = r.bool(INTIFACE_CONNECTED) ?: false,
            trace = r.message(TRACE)?.let { t ->
                SessionTrace(
                    referenceTimestampMs = t.long(T_REFERENCE_TS) ?: 0L,
                    reference = decodePose(t.requireBytes(T_REFERENCE_POSE)),
                    clockStartedAtMs = t.long(T_CLOCK_START) ?: 0L,
                    initialFaceStatus = t.enum(T_INITIAL_FACE, FaceDetectionStatus.NotProcessed),
                    frameData = t.bytes(T_FRAMES) ?: ByteArray(0),
                    frameCount = t.int(T_FRAME_COUNT) ?: 0,
                    truncated = t.bool(T_TRUNCATED) ?: false,
                    imageWidth = (t.int(T_IMAGE_WIDTH) ?: 0).coerceIn(0, 100_000),
                    imageHeight = (t.int(T_IMAGE_HEIGHT) ?: 0).coerceIn(0, 100_000)
                )
            }
        )
    }

    private fun encodePose(pose: TracePose): ByteArray = ByteSink().apply {
        for (i in pose.qx.indices) {
            varint(pose.qx[i].toLong())
            varint(pose.qy[i].toLong())
            varint(pose.qPresence[i].toLong())
        }
    }.toByteArray()

    private fun decodePose(data: ByteArray): TracePose {
        val source = ByteSource(data)
        val n = TRACE_LANDMARK_INDICES.size
        val qx = IntArray(n)
        val qy = IntArray(n)
        val qp = IntArray(n)
        for (i in 0 until n) {
            qx[i] = source.varint().toInt()
            qy[i] = source.varint().toInt()
            qp[i] = source.varint().toInt()
            if (qx[i] !in 0..65535 || qy[i] !in 0..65535 || qp[i] !in 0..255) {
                throw TlvFormatException("Reference landmark out of range")
            }
        }
        return TracePose(qx, qy, qp)
    }
}
