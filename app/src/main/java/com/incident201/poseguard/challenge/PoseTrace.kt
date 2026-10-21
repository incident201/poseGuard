package com.incident201.poseguard.challenge

import com.incident201.poseguard.challenge.codec.ByteSink
import com.incident201.poseguard.challenge.codec.ByteSource
import com.incident201.poseguard.challenge.codec.TlvFormatException
import com.incident201.poseguard.tracker.FaceDetectionStatus
import com.incident201.poseguard.tracker.Point3D
import com.incident201.poseguard.tracker.PoseLandmarks
import kotlin.math.roundToInt

/**
 * Landmarks kept in a challenge trace: the head points for the replay figure and every
 * landmark [com.incident201.poseguard.tracker.MovementTracker] reads.
 */
val TRACE_LANDMARK_INDICES: IntArray = intArrayOf(0, 2, 5, 7, 8, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)

private const val POSE_LANDMARK_COUNT = 33
private const val QUANT_MIN = -0.5f
private const val QUANT_RANGE = 2.0f
private const val QUANT_STEPS = 65535f

internal fun quantizeCoordinate(value: Float): Int {
    if (!value.isFinite()) return 0
    return (((value - QUANT_MIN) / QUANT_RANGE) * QUANT_STEPS).roundToInt().coerceIn(0, 65535)
}

internal fun dequantizeCoordinate(q: Int): Float = QUANT_MIN + (q / QUANT_STEPS) * QUANT_RANGE

internal fun quantizePresence(value: Float?): Int =
    ((value ?: 0f).takeIf(Float::isFinite) ?: 0f).coerceIn(0f, 1f).times(255f).roundToInt()

/** Quantized trace landmarks: x, y and presence per [TRACE_LANDMARK_INDICES] entry. */
class TracePose(val qx: IntArray, val qy: IntArray, val qPresence: IntArray) {
    init {
        require(qx.size == TRACE_LANDMARK_INDICES.size && qy.size == qx.size && qPresence.size == qx.size)
    }

    /** Rebuilds the 33-landmark pose the tracker saw; landmarks outside the trace are zero. */
    fun toPoseLandmarks(): PoseLandmarks {
        val all = MutableList(POSE_LANDMARK_COUNT) { Point3D(0f, 0f, 0f, 0f, 0f) }
        TRACE_LANDMARK_INDICES.forEachIndexed { i, index ->
            val presence = qPresence[i] / 255f
            all[index] = Point3D(dequantizeCoordinate(qx[i]), dequantizeCoordinate(qy[i]), 0f, presence, presence)
        }
        return PoseLandmarks.fromAllLandmarks(all)
    }

    override fun equals(other: Any?): Boolean =
        other is TracePose && qx.contentEquals(other.qx) && qy.contentEquals(other.qy) &&
            qPresence.contentEquals(other.qPresence)

    override fun hashCode(): Int = 31 * (31 * qx.contentHashCode() + qy.contentHashCode()) + qPresence.contentHashCode()

    companion object {
        /** Returns null for a pose without the full landmark set (a detector dropout). */
        fun from(pose: PoseLandmarks): TracePose? {
            if (pose.allLandmarks.size < POSE_LANDMARK_COUNT) return null
            val n = TRACE_LANDMARK_INDICES.size
            val qx = IntArray(n)
            val qy = IntArray(n)
            val qp = IntArray(n)
            TRACE_LANDMARK_INDICES.forEachIndexed { i, index ->
                val point = pose.allLandmarks[index]
                qx[i] = quantizeCoordinate(point.x)
                qy[i] = quantizeCoordinate(point.y)
                qp[i] = quantizePresence(point.presence ?: point.visibility)
            }
            return TracePose(qx, qy, qp)
        }
    }
}

/**
 * Replaces the traced coordinates by their quantized values, so the live tracker and the
 * author's replay compute on identical inputs.
 */
fun PoseLandmarks.quantizedForTrace(): PoseLandmarks {
    if (allLandmarks.size < POSE_LANDMARK_COUNT) return this
    return TracePose.from(this)!!.toPoseLandmarks()
}

class TraceFrame(
    /** Camera frame timestamp, the tracker's clock. */
    val timestampMs: Long,
    /** Session clock when the frame was evaluated; penalties and outages use it. */
    val evaluatedAtMs: Long,
    val faceStatus: FaceDetectionStatus,
    /** Null when the tracker received no pose. */
    val pose: TracePose?
)

private val FACE_STATUS_CODES = arrayOf(
    FaceDetectionStatus.NotProcessed,
    FaceDetectionStatus.FaceVisible,
    FaceDetectionStatus.FaceNotVisible,
    FaceDetectionStatus.Error
)

private fun FaceDetectionStatus.code(): Int = FACE_STATUS_CODES.indexOf(this)

/** Delta-encoded frame stream. Appending is cheap, so the recorder writes as frames arrive. */
class TraceFrameWriter(private val referenceTimestampMs: Long, reference: TracePose, private val maxBytes: Int) {
    private val sink = ByteSink(64 * 1024)
    private var previousTs = referenceTimestampMs
    private var previousPose = reference

    var frameCount: Int = 0
        private set
    var truncated: Boolean = false
        private set

    fun append(frame: TraceFrame) {
        if (truncated) return
        if (sink.size >= maxBytes) {
            truncated = true
            return
        }
        sink.zigzag(frame.timestampMs - previousTs)
        sink.zigzag(frame.evaluatedAtMs - frame.timestampMs)
        val pose = frame.pose
        sink.byte(frame.faceStatus.code() or (if (pose != null) 0x4 else 0))
        if (pose != null) {
            for (i in pose.qx.indices) {
                sink.zigzag((pose.qx[i] - previousPose.qx[i]).toLong())
                sink.zigzag((pose.qy[i] - previousPose.qy[i]).toLong())
                sink.zigzag((pose.qPresence[i] - previousPose.qPresence[i]).toLong())
            }
            previousPose = pose
        }
        previousTs = frame.timestampMs
        frameCount += 1
    }

    fun toByteArray(): ByteArray = sink.toByteArray()
}

object TraceFrameReader {
    fun decode(data: ByteArray, referenceTimestampMs: Long, reference: TracePose, maxFrames: Int = 2_000_000): List<TraceFrame> {
        val frames = ArrayList<TraceFrame>()
        forEach(data, referenceTimestampMs, reference, maxFrames) { frames += it }
        return frames
    }

    /** Streaming replay avoids retaining millions of decoded poses just to check a report. */
    fun forEach(data: ByteArray, referenceTimestampMs: Long, reference: TracePose,
                maxFrames: Int = 2_000_000, consume: (TraceFrame) -> Unit) {
        val source = ByteSource(data)
        var count = 0
        var previousTs = referenceTimestampMs
        var previousPose = reference
        val n = TRACE_LANDMARK_INDICES.size
        while (source.hasMore) {
            if (count >= maxFrames) throw TlvFormatException("Too many frames")
            val ts = checkedAdd(previousTs, source.zigzag())
            val evaluatedAt = checkedAdd(ts, source.zigzag())
            val flags = source.byte()
            if (flags and 0xF8 != 0) throw TlvFormatException("Unknown frame flags")
            val face = FACE_STATUS_CODES.getOrNull(flags and 0x3) ?: throw TlvFormatException("Bad face status")
            val pose = if (flags and 0x4 != 0) {
                val qx = IntArray(n)
                val qy = IntArray(n)
                val qp = IntArray(n)
                for (i in 0 until n) {
                    qx[i] = coordinate(previousPose.qx[i], source.zigzag(), 65535)
                    qy[i] = coordinate(previousPose.qy[i], source.zigzag(), 65535)
                    qp[i] = coordinate(previousPose.qPresence[i], source.zigzag(), 255)
                    if (qx[i] !in 0..65535 || qy[i] !in 0..65535 || qp[i] !in 0..255) {
                        throw TlvFormatException("Landmark out of range")
                    }
                }
                TracePose(qx, qy, qp).also { previousPose = it }
            } else {
                null
            }
            consume(TraceFrame(ts, evaluatedAt, face, pose))
            count++
            previousTs = ts
        }
    }

    private fun checkedAdd(a: Long, b: Long): Long = try { Math.addExact(a, b) }
        catch (e: ArithmeticException) { throw TlvFormatException("Timestamp overflow") }

    private fun coordinate(previous: Int, delta: Long, max: Int): Int {
        val value = checkedAdd(previous.toLong(), delta)
        if (value !in 0L..max.toLong()) throw TlvFormatException("Landmark out of range")
        return value.toInt()
    }
}
