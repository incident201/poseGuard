package com.incident201.poseguard.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Textsms
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.incident201.poseguard.R
import com.incident201.poseguard.challenge.TRACE_LANDMARK_INDICES
import com.incident201.poseguard.tracker.Point3D
import com.incident201.poseguard.viewmodel.AppLanguage
import kotlin.math.sin

/**
 * The guide shown before the challenges are opened for the first time. [next] is where the
 * user was going: the hub, or a task or report they just opened from a messenger. [onDone] gets
 * the page to show afterwards.
 */
@Composable
internal fun ChallengeIntro(
    language: AppLanguage,
    next: ChallengePage,
    onDone: (ChallengePage) -> Unit,
    modifier: Modifier = Modifier
) {
    val pages = listOf<Pair<Int, List<Int>>>(
        R.string.challenge_intro_title_1 to listOf(R.string.challenge_intro_tip_1a, R.string.challenge_intro_tip_1b),
        R.string.challenge_intro_title_2 to listOf(R.string.challenge_intro_tip_2a, R.string.challenge_intro_tip_2b),
        R.string.challenge_intro_title_3 to listOf(R.string.challenge_intro_tip_3a, R.string.challenge_intro_tip_3b, R.string.challenge_intro_tip_3c),
        R.string.challenge_intro_title_4 to listOf(R.string.challenge_intro_tip_4a, R.string.challenge_intro_tip_4b, R.string.challenge_intro_tip_4c),
        R.string.challenge_intro_title_5 to listOf(R.string.challenge_intro_tip_5a, R.string.challenge_intro_tip_5b, R.string.challenge_intro_tip_5c)
    )
    GuidePager(
        language = language,
        pageCount = pages.size,
        onSkip = { onDone(next) },
        finishButton = {
            if (next is ChallengePage.Hub) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onDone(next) }) { Text(localizedString(language, R.string.challenge_intro_to_hub)) }
                    Button(onClick = { onDone(ChallengePage.Editor) }) { Text(localizedString(language, R.string.challenge_create)) }
                }
            } else {
                Button(onClick = { onDone(next) }) { Text(localizedString(language, R.string.challenge_intro_open)) }
            }
        },
        modifier = modifier
    ) { index, compact ->
        val (title, tips) = pages[index]
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(if (compact) 22.dp else 28.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                when (index) {
                    0 -> ExchangeIllustration(language)
                    1 -> RulesIllustration(language)
                    2 -> TransportIllustration(language)
                    3 -> SessionIllustration(language)
                    else -> ReportIllustration(language)
                }
            }
            Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
            OnboardingTipsList(language, tips, compact, Modifier.fillMaxWidth(), titleId = title)
        }
    }
}

// Illustrations: drawn from the app's own icons and stick figure, so they follow the theme and
// show the real controls rather than decoration.

private val FigureBlue = Color(0xFF6FE3FF)
private val FigureRed = Color(0xFFFF6E6E)

@Composable
private fun Caption(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, textAlign = TextAlign.Center)
}

@Composable
private fun Phone(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 112.dp, height = 200.dp)
            .border(3.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun ExchangeIllustration(language: AppLanguage) {
    val primary = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Phone { Icon(Icons.Filled.EditNote, null, tint = primary, modifier = Modifier.size(56.dp)) }
            Spacer(Modifier.height(6.dp))
            Caption(localizedString(language, R.string.challenge_intro_author))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = primary, modifier = Modifier.size(34.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = primary, modifier = Modifier.size(34.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(34.dp))
                Icon(Icons.Filled.Assessment, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(34.dp))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Phone { StickFigure(StandingPose, FigureBlue, Modifier.fillMaxSize()) }
            Spacer(Modifier.height(6.dp))
            Caption(localizedString(language, R.string.challenge_intro_executor))
        }
    }
}

@Composable
private fun RulesIllustration(language: AppLanguage) {
    @Composable
    fun Rule(icon: ImageVector, label: Int, hidden: Boolean = false, hideable: Boolean = false) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(localizedString(language, label), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (hideable) {
                if (hidden) {
                    Text("??:??", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
    Column(Modifier.widthIn(max = 360.dp)) {
        Rule(Icons.Filled.Timer, R.string.challenge_group_duration, hidden = true, hideable = true)
        Rule(Icons.Filled.Warning, R.string.challenge_group_errors)
        Rule(Icons.Filled.MoreTime, R.string.challenge_group_penalties, hideable = true)
        Rule(Icons.Filled.Tune, R.string.challenge_group_sensitivity)
        Rule(Icons.Filled.Face, R.string.challenge_group_face)
        Rule(Icons.AutoMirrored.Filled.VolumeUp, R.string.challenge_group_sounds)
        Rule(Icons.Filled.Vibration, R.string.challenge_group_intiface)
    }
}

@Composable
private fun TransportIllustration(language: AppLanguage) {
    @Composable
    fun Way(icon: ImageVector, label: Int, sample: String?, modifier: Modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
            Box(
                Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(50.dp)) }
            Spacer(Modifier.height(8.dp))
            Text(localizedString(language, label), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            if (sample != null) {
                Text(sample, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth()) {
            Way(Icons.AutoMirrored.Filled.InsertDriveFile, R.string.challenge_add_file, ".pgtask", Modifier.weight(1f))
            Way(Icons.Filled.Textsms, R.string.challenge_add_code, "PGT2:…", Modifier.weight(1f))
            Way(Icons.Filled.QrCode, R.string.challenge_add_qr, null, Modifier.weight(1f))
        }
        Spacer(Modifier.height(32.dp))
        // Embedded audio: file only.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AudioFile, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(8.dp))
            Text(localizedString(language, R.string.challenge_intro_file_only), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        }
    }
}

@Composable
private fun SessionIllustration(language: AppLanguage) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StickFigure(StandingPose, FigureBlue, Modifier.size(width = 200.dp, height = 240.dp))
        Spacer(Modifier.height(8.dp))
        Text("??:??:??", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The session's stop button, as on the main screen.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Filled.Stop, null, tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(localizedString(language, R.string.stop), color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Flag, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(34.dp))
            Icon(Icons.Filled.Assessment, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(34.dp))
        }
    }
}

/** A looping replay: the figure drifts twice, turns red, and the timeline marks both moments. */
@Composable
private fun ReportIllustration(language: AppLanguage) {
    val progress by rememberInfiniteTransition(label = "introReplay").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6_000, easing = LinearEasing), RepeatMode.Restart),
        label = "introReplayProgress"
    )
    val marks = listOf(0.35f, 0.72f)
    val drifting = marks.any { progress in it..(it + 0.1f) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(width = 260.dp, height = 280.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF101418)),
            contentAlignment = Alignment.Center
        ) {
            StickFigure(StandingPose, Color.White.copy(alpha = 0.16f), Modifier.fillMaxSize().padding(12.dp))
            StickFigure(
                swayingPose(progress, drift = if (drifting) 1f else 0f),
                if (drifting) FigureRed else FigureBlue,
                Modifier.fillMaxSize().padding(12.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        val head = MaterialTheme.colorScheme.primary
        Canvas(Modifier.width(260.dp).height(16.dp)) {
            val y = size.height / 2
            drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = 4f)
            marks.forEach { drawCircle(FigureRed, radius = 6f, center = Offset(it * size.width, y)) }
            drawCircle(head, radius = 9f, center = Offset(progress * size.width, y))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text(localizedString(language, R.string.report_verdict_confirmed), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        }
    }
}

// Stick figure

/** A person standing with arms held out, in normalized image coordinates. */
private val StandingPose: List<Point3D> = figure(armLift = 0f)

private fun swayingPose(progress: Float, drift: Float): List<Point3D> {
    val sway = sin(progress * 2f * Math.PI.toFloat() * 3f) * 0.006f
    return figure(armLift = drift).map { it.copy(x = it.x + sway) }
}

private fun figure(armLift: Float): List<Point3D> {
    val points = mutableMapOf(
        0 to (0.50f to 0.12f),
        2 to (0.47f to 0.10f), 5 to (0.53f to 0.10f),
        7 to (0.44f to 0.12f), 8 to (0.56f to 0.12f),
        11 to (0.38f to 0.27f), 12 to (0.62f to 0.27f),
        13 to (0.27f to (0.40f - 0.10f * armLift)), 14 to (0.73f to 0.40f),
        15 to (0.22f to (0.53f - 0.24f * armLift)), 16 to (0.78f to 0.53f),
        23 to (0.42f to 0.56f), 24 to (0.58f to 0.56f),
        25 to (0.41f to 0.74f), 26 to (0.59f to 0.74f),
        27 to (0.40f to 0.92f), 28 to (0.60f to 0.92f)
    )
    return List(33) { i ->
        points[i]?.let { (x, y) -> Point3D(x, y, 0f, 1f, 1f) } ?: Point3D(Float.NaN, Float.NaN, 0f, 0f, 0f)
    }
}

@Composable
private fun StickFigure(landmarks: List<Point3D>, color: Color, modifier: Modifier) {
    Canvas(modifier) { drawFigure(landmarks, color) }
}

private fun DrawScope.drawFigure(landmarks: List<Point3D>, color: Color) {
    // Keep the figure's proportions whatever the box.
    val side = minOf(size.width, size.height)
    val left = (size.width - side) / 2
    val top = (size.height - side) / 2
    val map = { p: Point3D -> Offset(left + p.x * side, top + p.y * side) }
    val stroke = side * 0.035f
    drawPoseSkeleton(
        landmarks = landmarks,
        connections = TRACE_POSE_CONNECTIONS.filter { (a, b) -> a > 10 && b > 10 },
        map = map,
        boneColor = color,
        jointColor = { color },
        boneWidth = stroke,
        jointRadius = stroke * 0.8f,
        jointIndices = TRACE_LANDMARK_INDICES.filter { it > 10 }
    )
    landmarks.getOrNull(0)?.let { nose ->
        drawCircle(color, radius = side * 0.07f, center = map(nose), style = Stroke(width = stroke))
    }
}
