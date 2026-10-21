package com.incident201.poseguard.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.incident201.poseguard.challenge.TRACE_LANDMARK_INDICES
import com.incident201.poseguard.tracker.Point3D

/** MediaPipe pose landmark pairs drawn as bones. */
internal val POSE_CONNECTIONS = listOf(
    11 to 12,
    11 to 13,
    13 to 15,
    15 to 17,
    15 to 19,
    15 to 21,
    17 to 19,
    12 to 14,
    14 to 16,
    16 to 18,
    16 to 20,
    16 to 22,
    18 to 20,
    11 to 23,
    12 to 24,
    23 to 24,
    23 to 25,
    25 to 27,
    27 to 29,
    27 to 31,
    29 to 31,
    24 to 26,
    26 to 28,
    28 to 30,
    28 to 32,
    30 to 32
)

/** Bones available in a challenge trace, plus the face points that outline the head. */
internal val TRACE_POSE_CONNECTIONS: List<Pair<Int, Int>> =
    POSE_CONNECTIONS.filter { (a, b) -> a in TRACE_LANDMARK_INDICES && b in TRACE_LANDMARK_INDICES } +
        listOf(0 to 2, 0 to 5, 2 to 7, 5 to 8)

internal fun isDrawablePosePoint(point: Point3D): Boolean =
    point.x.isFinite() && point.y.isFinite() && point.presence?.let { it >= 0.5f } != false

/**
 * Draws bones and joints. [map] converts a landmark to canvas coordinates; [jointColor]
 * picks a color per landmark index.
 */
internal fun DrawScope.drawPoseSkeleton(
    landmarks: List<Point3D>,
    connections: List<Pair<Int, Int>>,
    map: (Point3D) -> Offset,
    boneColor: Color,
    jointColor: (Int) -> Color,
    boneWidth: Float = 2.5f,
    jointRadius: Float = 4f,
    jointIndices: Collection<Int>? = null
) {
    connections.forEach { (startIndex, endIndex) ->
        val start = landmarks.getOrNull(startIndex) ?: return@forEach
        val end = landmarks.getOrNull(endIndex) ?: return@forEach
        if (!isDrawablePosePoint(start) || !isDrawablePosePoint(end)) return@forEach
        drawLine(color = boneColor, start = map(start), end = map(end), strokeWidth = boneWidth)
    }
    val indices = jointIndices ?: landmarks.indices
    indices.forEach { index ->
        val point = landmarks.getOrNull(index) ?: return@forEach
        if (!isDrawablePosePoint(point)) return@forEach
        drawCircle(color = jointColor(index), radius = jointRadius, center = map(point))
    }
}
