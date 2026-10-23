package com.incident201.poseguard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.incident201.poseguard.challenge.TRACE_LANDMARK_INDICES
import com.incident201.poseguard.challenge.TraceIndex
import com.incident201.poseguard.challenge.TracePose
import com.incident201.poseguard.challenge.TraceViolationEvent
import com.incident201.poseguard.tracker.Point3D
import com.incident201.poseguard.util.formatDurationHmsFromMillis

/** Trace prepared for drawing: the session time axis, with each frame's pose decoded when shown. */
internal class ReplayData(
    val startMs: Long,
    val endMs: Long,
    private val index: TraceIndex,
    val reference: List<Point3D>,
    val aspect: Float
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1L)
    private val timestamps: LongArray get() = index.evaluatedAtMs

    /** Landmarks of frame [frameIndex], or null when the tracker received no pose. */
    fun poseAt(frameIndex: Int): List<Point3D>? = index.pose(frameIndex)?.toPoseLandmarks()?.allLandmarks

    /** Index of the last frame at or before [timeMs]. */
    fun frameAt(timeMs: Long): Int {
        var lo = 0
        var hi = timestamps.size - 1
        if (hi < 0 || timeMs < timestamps[0]) return -1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (timestamps[mid] <= timeMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        fun from(
            index: TraceIndex,
            reference: TracePose,
            clockStartedAtMs: Long,
            endedAtMs: Long,
            imageWidth: Int,
            imageHeight: Int
        ): ReplayData {
            val end = maxOf(endedAtMs, index.evaluatedAtMs.lastOrNull() ?: clockStartedAtMs)
            return ReplayData(
                startMs = clockStartedAtMs,
                endMs = end,
                index = index,
                reference = reference.toPoseLandmarks().allLandmarks,
                aspect = if (imageWidth > 0 && imageHeight > 0) imageWidth.toFloat() / imageHeight else 9f / 16f
            )
        }
    }
}

/** Stick figure replay with play/pause, speed, seeking and violation markers. */
@Composable
internal fun ReportReplayPlayer(
    data: ReplayData,
    events: List<TraceViolationEvent>,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var positionMs by remember(data) { mutableFloatStateOf(0f) }
    var playing by remember(data) { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(4) }
    val durationMs = data.durationMs.toFloat()

    LaunchedEffect(playing, speed, data) {
        if (!playing) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (playing) {
            val now = withFrameMillis { it }
            positionMs = (positionMs + (now - last) * speed).coerceAtMost(durationMs)
            last = now
            if (positionMs >= durationMs) playing = false
        }
    }

    val frameIndex = data.frameAt(data.startMs + positionMs.toLong())
    val landmarks = remember(data, frameIndex) { if (frameIndex >= 0) data.poseAt(frameIndex) else data.reference }
    val activeEvent = events.lastOrNull { it.atMs - data.startMs in 0..positionMs.toLong() && positionMs - (it.atMs - data.startMs) < 1_500L }

    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(data.aspect.coerceIn(0.4f, 2f))
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF101418))
        ) {
            Canvas(Modifier.matchParentSize()) {
                val mapper = { p: Point3D -> Offset(p.x.coerceIn(-0.2f, 1.2f) * size.width, p.y.coerceIn(-0.2f, 1.2f) * size.height) }
                // The reference pose stays visible as a ghost, so drift is easy to see.
                drawPoseSkeleton(
                    landmarks = data.reference,
                    connections = TRACE_POSE_CONNECTIONS,
                    map = mapper,
                    boneColor = Color.White.copy(alpha = 0.16f),
                    jointColor = { Color.White.copy(alpha = 0.16f) },
                    boneWidth = 6f,
                    jointRadius = 5f,
                    jointIndices = TRACE_LANDMARK_INDICES.toList()
                )
                if (landmarks != null) {
                    val figureColor = if (activeEvent != null) Color(0xFFFF6E6E) else Color(0xFF6FE3FF)
                    drawPoseSkeleton(
                        landmarks = landmarks,
                        connections = TRACE_POSE_CONNECTIONS,
                        map = mapper,
                        boneColor = figureColor,
                        jointColor = { figureColor },
                        boneWidth = 7f,
                        jointRadius = 6f,
                        jointIndices = TRACE_LANDMARK_INDICES.toList()
                    )
                    val nose = landmarks.getOrNull(0)
                    if (nose != null && isDrawablePosePoint(nose)) {
                        drawCircle(color = figureColor, radius = size.minDimension * 0.045f, center = mapper(nose), style = Stroke(width = 6f))
                    }
                }
            }
            Text(
                text = formatDurationHmsFromMillis(positionMs.toLong()),
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        // Violation markers above the seek bar.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .padding(horizontal = 10.dp)
        ) {
            events.forEach { event ->
                val fraction = ((event.atMs - data.startMs) / durationMs).coerceIn(0f, 1f)
                drawCircle(
                    color = if (event.defeat) colorScheme.error else Color(0xFFFFB74D),
                    radius = 4.dp.toPx(),
                    center = Offset(fraction * size.width, size.height / 2f)
                )
            }
        }
        Slider(
            value = positionMs,
            onValueChange = { positionMs = it },
            valueRange = 0f..durationMs,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(onClick = {
                if (!playing && positionMs >= durationMs) positionMs = 0f
                playing = !playing
            }) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
            }
            listOf(1, 4, 16, 64).forEach { option ->
                FilterChip(selected = speed == option, onClick = { speed = option }, label = { Text("$option×") })
            }
            Spacer(Modifier.width(4.dp))
            Text(
                formatDurationHmsFromMillis(data.durationMs),
                color = colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

