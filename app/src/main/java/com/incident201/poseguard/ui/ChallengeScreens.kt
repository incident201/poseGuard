package com.incident201.poseguard.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Textsms
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.incident201.poseguard.R
import com.incident201.poseguard.challenge.AuthoredChallenge
import com.incident201.poseguard.challenge.Blob
import com.incident201.poseguard.challenge.ChallengeFileError
import com.incident201.poseguard.challenge.ChallengeFiles
import com.incident201.poseguard.challenge.ChallengeReport
import com.incident201.poseguard.challenge.ChallengeSpec
import com.incident201.poseguard.challenge.ImportResult
import com.incident201.poseguard.challenge.IncomingReport
import com.incident201.poseguard.challenge.OutgoingReport
import com.incident201.poseguard.challenge.QrCodes
import com.incident201.poseguard.challenge.ReceivedChallenge
import com.incident201.poseguard.challenge.ReportVerifier
import com.incident201.poseguard.challenge.AudioAttachResult
import com.incident201.poseguard.challenge.ChallengeDraft
import com.incident201.poseguard.challenge.ChallengeRules
import com.incident201.poseguard.challenge.MAX_CHALLENGE_SECONDS
import com.incident201.poseguard.challenge.SessionEndCause
import com.incident201.poseguard.challenge.TimerSpec
import com.incident201.poseguard.challenge.TraceViolationType
import com.incident201.poseguard.challenge.ReplayReportVerifier
import com.incident201.poseguard.challenge.VerdictKind
import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.audio.AudioCueMode
import com.incident201.poseguard.audio.AudioCueSettings
import com.incident201.poseguard.audio.PcmSignalPlayer
import com.incident201.poseguard.audio.PcmSignalSettings
import com.incident201.poseguard.audio.TtsPhraseTemplate
import com.incident201.poseguard.intiface.IntifaceBackgroundMode
import com.incident201.poseguard.intiface.IntifaceViolationMode
import com.incident201.poseguard.util.formatDurationHms
import com.incident201.poseguard.viewmodel.AppLanguage
import com.incident201.poseguard.viewmodel.FaceCheckMode
import com.incident201.poseguard.viewmodel.GameViewModel
import com.incident201.poseguard.viewmodel.TimerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Screens of the challenge flow; [CameraScreen] shows one of them instead of the camera layout. */
internal sealed class ChallengePage {
    /** The guide, then [next]. */
    data class Intro(val next: ChallengePage) : ChallengePage()
    data class Hub(val tab: Int = 0) : ChallengePage()
    object Editor : ChallengePage()
    data class Brief(val id: Blob) : ChallengePage()
    data class Report(val reportId: Blob) : ChallengePage()
}

/** Checks reports on the author's device; replace the stub to enable real verification. */
internal val reportVerifier: ReportVerifier = ReplayReportVerifier

@Composable
internal fun ChallengeFlow(
    viewModel: GameViewModel,
    language: AppLanguage,
    page: ChallengePage,
    refreshKey: Any?,
    onNavigate: (ChallengePage?) -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier
) {
    val finishIntro = { target: ChallengePage ->
        viewModel.markChallengeIntroCompleted()
        onNavigate(target)
    }
    BackHandler {
        when (page) {
            // Leaving the guide counts as having seen it.
            is ChallengePage.Intro -> finishIntro(page.next)
            is ChallengePage.Hub -> onNavigate(null)
            else -> onNavigate(ChallengePage.Hub(tabFor(page)))
        }
    }
    when (page) {
        is ChallengePage.Intro -> ChallengeIntro(language, page.next, finishIntro, modifier)
        is ChallengePage.Hub -> ChallengeHub(viewModel, language, page.tab, refreshKey, onNavigate, onScanQr, modifier)
        ChallengePage.Editor -> ChallengeEditor(viewModel, language, onNavigate, modifier)
        is ChallengePage.Brief -> ChallengeBrief(viewModel, language, page.id, refreshKey, onNavigate, modifier)
        is ChallengePage.Report -> ReportScreen(viewModel, language, page.reportId, onNavigate, modifier)
    }
}

private fun tabFor(page: ChallengePage): Int = when (page) {
    is ChallengePage.Intro -> tabFor(page.next)
    is ChallengePage.Hub -> page.tab
    ChallengePage.Editor -> 1
    is ChallengePage.Brief -> 0
    is ChallengePage.Report -> 2
}

// Shared layout

@Composable
private fun ChallengeScaffold(
    title: String,
    language: AppLanguage,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = localizedString(language, R.string.back),
                    tint = colorScheme.onBackground
                )
            }
            Text(
                title,
                color = colorScheme.onBackground,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            actions()
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun CardTitle(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
}

/** Button content: an icon before the label. */
@Composable
private fun IconLabel(icon: ImageVector, text: String, fontWeight: FontWeight? = null) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text, fontWeight = fontWeight, textAlign = TextAlign.Center)
}

private val CompactButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

@Composable
private fun DeleteButton(language: AppLanguage, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth()
    ) {
        IconLabel(Icons.Filled.Delete, localizedString(language, R.string.challenge_delete))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1.2f),
            textAlign = TextAlign.End,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun ListCard(title: String, subtitle: String, detail: String? = null, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            if (detail != null) {
                Text(detail, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        textAlign = TextAlign.Center
    )
}

/** Loads repository data off the main thread; reloads when [key] changes. */
@Composable
private fun <T> rememberLoaded(key: Any?, load: () -> T): T? {
    val state by produceState<T?>(null, key) { value = withContext(Dispatchers.IO) { load() } }
    return state
}

private fun formatDate(ms: Long): String =
    if (ms <= 0L) "" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

// Hub

@Composable
private fun ChallengeHub(
    viewModel: GameViewModel,
    language: AppLanguage,
    initialTab: Int,
    refreshKey: Any?,
    onNavigate: (ChallengePage?) -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val repository = viewModel.challengeRepository
    var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab) }
    var localRefresh by remember { mutableIntStateOf(0) }
    val key = refreshKey to localRefresh
    val received = rememberLoaded(key) { repository.receivedChallenges() }
    val authored = rememberLoaded(key) { repository.authoredChallenges() }
    val outgoing = rememberLoaded(key) { repository.outgoingReports() }
    val incoming = rememberLoaded(key) { repository.incomingReports() }
    var sendingReport by remember { mutableStateOf<OutgoingReport?>(null) }
    val pasteEmptyText = localizedString(language, R.string.challenge_paste_empty)

    val openFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importChallengeFromUri(uri)
    }

    ChallengeScaffold(
        localizedString(language, R.string.challenges_title),
        language,
        onBack = { onNavigate(null) },
        modifier,
        actions = {
            IconButton(onClick = { onNavigate(ChallengePage.Intro(ChallengePage.Hub(tab))) }) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = localizedString(language, R.string.challenge_intro_help),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    ) {
        CardTitle(localizedString(language, R.string.challenge_add_title))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openFileLauncher.launch(arrayOf("*/*")) }, contentPadding = CompactButtonPadding, modifier = Modifier.weight(1f)) {
                IconLabel(Icons.Filled.FileOpen, localizedString(language, R.string.challenge_add_file))
            }
            OutlinedButton(onClick = {
                val text = ChallengeShare.pasteText(context)
                if (text != null && ChallengeFiles.mentionsTextCode(text)) {
                    viewModel.importChallengeFromText(text)
                } else {
                    Toast.makeText(context, pasteEmptyText, Toast.LENGTH_SHORT).show()
                }
            }, contentPadding = CompactButtonPadding, modifier = Modifier.weight(1f)) {
                IconLabel(Icons.Filled.ContentPaste, localizedString(language, R.string.challenge_add_code))
            }
            OutlinedButton(onClick = onScanQr, contentPadding = CompactButtonPadding, modifier = Modifier.weight(1f)) {
                IconLabel(Icons.Filled.QrCodeScanner, localizedString(language, R.string.challenge_add_qr))
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf(
                R.string.challenges_tab_received to Icons.Filled.Inbox,
                R.string.challenges_tab_mine to Icons.Filled.EditNote,
                R.string.challenges_tab_reports to Icons.Filled.Assessment
            ).forEachIndexed { index, (labelRes, icon) ->
                Tab(selected = tab == index, onClick = { tab = index }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(localizedString(language, labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            0 -> ReceivedList(language, received, onOpen = { onNavigate(ChallengePage.Brief(it.spec.id)) })
            1 -> {
                Button(onClick = { onNavigate(ChallengePage.Editor) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    IconLabel(Icons.Filled.Add, localizedString(language, R.string.challenge_create), FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                AuthoredList(language, authored, incoming, onOpen = { onNavigate(ChallengePage.Brief(it.spec.id)) })
            }
            else -> ReportsList(
                language,
                outgoing,
                incoming,
                onSend = { sendingReport = it },
                onOpenIncoming = { onNavigate(ChallengePage.Report(it.report.reportId)) }
            )
        }
    }

    sendingReport?.let { outgoingReport ->
        SendReportDialog(
            viewModel = viewModel,
            language = language,
            report = outgoingReport.report,
            challengeTitle = outgoingReport.challengeTitle,
            onDismiss = { sendingReport = null }
        )
    }
}

@Composable
private fun ReceivedList(language: AppLanguage, items: List<ReceivedChallenge>?, onOpen: (ReceivedChallenge) -> Unit) {
    when {
        items == null -> EmptyText(localizedString(language, R.string.report_loading))
        items.isEmpty() -> EmptyText(localizedString(language, R.string.challenge_empty_received))
        else -> items.forEach { item ->
            ListCard(
                title = item.spec.title,
                subtitle = item.spec.authorName.takeIf { it.isNotBlank() }
                    ?.let { localizedFormatString(language, R.string.challenge_by_author, it) }.orEmpty(),
                detail = attemptsText(language, item.attemptsLeft),
                onClick = { onOpen(item) }
            )
        }
    }
}

@Composable
private fun attemptsText(language: AppLanguage, attemptsLeft: Int?): String = when {
    attemptsLeft == null -> localizedString(language, R.string.challenge_attempts_unlimited)
    attemptsLeft == 0 -> localizedString(language, R.string.challenge_no_attempts)
    else -> localizedFormatString(language, R.string.challenge_attempts_left, attemptsLeft)
}

@Composable
private fun AuthoredList(
    language: AppLanguage,
    items: List<AuthoredChallenge>?,
    incoming: List<IncomingReport>?,
    onOpen: (AuthoredChallenge) -> Unit
) {
    when {
        items == null -> EmptyText(localizedString(language, R.string.report_loading))
        items.isEmpty() -> EmptyText(localizedString(language, R.string.challenge_empty_mine))
        else -> items.forEach { item ->
            val reports = incoming.orEmpty().count { it.report.challengeId == item.spec.id }
            ListCard(
                title = item.spec.title,
                subtitle = formatDate(item.spec.createdAtMs),
                detail = localizedFormatString(language, R.string.challenge_reports_count, reports),
                onClick = { onOpen(item) }
            )
        }
    }
}

@Composable
private fun ReportsList(
    language: AppLanguage,
    outgoing: List<OutgoingReport>?,
    incoming: List<IncomingReport>?,
    onSend: (OutgoingReport) -> Unit,
    onOpenIncoming: (IncomingReport) -> Unit
) {
    if (outgoing == null || incoming == null) {
        EmptyText(localizedString(language, R.string.report_loading))
        return
    }
    if (outgoing.isEmpty() && incoming.isEmpty()) {
        EmptyText(localizedString(language, R.string.challenge_empty_reports))
        return
    }
    if (incoming.isNotEmpty()) {
        CardTitle(localizedString(language, R.string.challenge_reports_incoming))
        Spacer(Modifier.height(8.dp))
        incoming.forEach { item ->
            ListCard(
                title = item.challenge.spec.title,
                subtitle = item.report.executorName.ifBlank { localizedString(language, R.string.report_anonymous) } +
                    " · " + formatDate(item.report.endedAtWallMs),
                detail = localizedString(language, endCauseLabel(item.report.endCause)),
                onClick = { onOpenIncoming(item) }
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    if (outgoing.isNotEmpty()) {
        CardTitle(localizedString(language, R.string.challenge_reports_mine))
        Spacer(Modifier.height(8.dp))
        outgoing.forEach { item ->
            ListCard(
                title = item.challengeTitle,
                subtitle = formatDate(item.report.endedAtWallMs) + " · " +
                    localizedFormatString(language, R.string.report_attempt, item.report.attempt),
                detail = localizedString(language, endCauseLabel(item.report.endCause)) + " → " +
                    localizedString(language, R.string.challenge_send_report),
                onClick = { onSend(item) }
            )
        }
    }
}

internal fun endCauseLabel(cause: SessionEndCause): Int = when (cause) {
    SessionEndCause.Completed -> R.string.report_cause_completed
    SessionEndCause.TooManyViolations -> R.string.report_cause_too_many_violations
    SessionEndCause.CameraNoFrame -> R.string.report_cause_camera_no_frame
    SessionEndCause.CameraNoBody -> R.string.report_cause_camera_no_body
    SessionEndCause.FaceCheckUnavailable -> R.string.report_cause_face_unavailable
    SessionEndCause.Surrendered -> R.string.report_cause_surrendered
}

// Editor

/**
 * Every rule of the challenge is edited here. The draft lives in the ViewModel, so it survives
 * leaving the screen; it is reset when the challenge is created or with "Start over".
 */
@Composable
private fun ChallengeEditor(
    viewModel: GameViewModel,
    language: AppLanguage,
    onNavigate: (ChallengePage?) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft by viewModel.challengeDraft.collectAsState()
    val intifaceState by viewModel.intifaceState.collectAsState()
    val rules = draft.rules
    var creating by remember { mutableStateOf(false) }
    val titleRequiredText = localizedString(language, R.string.challenge_title_required)
    val copiedText = localizedString(language, R.string.challenge_copy_settings_done)
    fun edit(transform: (ChallengeDraft) -> ChallengeDraft) = viewModel.updateChallengeDraft(transform)
    fun editRules(transform: (ChallengeRules) -> ChallengeRules) = edit { it.copy(rules = transform(it.rules)) }

    ChallengeScaffold(localizedString(language, R.string.challenge_editor_title), language, onBack = { onNavigate(ChallengePage.Hub(1)) }, modifier) {
        Text(
            localizedString(language, R.string.challenge_editor_hint),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        viewModel.copySettingsIntoDraft()
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    }
                },
                contentPadding = CompactButtonPadding,
                modifier = Modifier.weight(1.4f)
            ) { IconLabel(Icons.Filled.Tune, localizedString(language, R.string.challenge_copy_settings)) }
            OutlinedButton(onClick = viewModel::resetChallengeDraft, contentPadding = CompactButtonPadding, modifier = Modifier.weight(1f)) {
                IconLabel(Icons.Filled.RestartAlt, localizedString(language, R.string.challenge_reset_draft))
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionCard {
            OutlinedTextField(
                value = draft.title,
                onValueChange = { value -> edit { it.copy(title = value.take(ChallengeSpec.MAX_TITLE_LENGTH)) } },
                label = { Text(localizedString(language, R.string.challenge_field_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.description,
                onValueChange = { value -> edit { it.copy(description = value.take(ChallengeSpec.MAX_DESCRIPTION_LENGTH)) } },
                label = { Text(localizedString(language, R.string.challenge_field_description)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.authorName,
                onValueChange = { value -> edit { it.copy(authorName = value.take(ChallengeSpec.MAX_AUTHOR_LENGTH)) } },
                label = { Text(localizedString(language, R.string.challenge_field_author)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        DurationEditor(language, rules.timer, onChange = { timer -> editRules { it.copy(timer = timer) } })
        SectionCard {
            CardTitle(localizedString(language, R.string.challenge_section_errors))
            Spacer(Modifier.height(8.dp))
            IntegerSettingField(localizedString(language, R.string.maximum_violations), rules.maxViolations, { v -> editRules { it.copy(maxViolations = v) } }, 0, 9999)
            IntegerSettingField(
                localizedString(language, R.string.challenge_penalty_interval),
                rules.minimumPenaltyIntervalSeconds,
                { v -> editRules { it.copy(minimumPenaltyIntervalSeconds = v) } },
                0,
                30
            )
            SwitchRow(localizedString(language, R.string.penalty_for_violation), rules.penaltiesEnabled) { v -> editRules { it.copy(penaltiesEnabled = v) } }
            if (rules.penaltiesEnabled) {
                Spacer(Modifier.height(8.dp))
                IntegerSettingField(localizedString(language, R.string.first_violation_min), rules.firstViolationPenaltyMinutes, { v -> editRules { it.copy(firstViolationPenaltyMinutes = v) } }, 0, 9999)
                IntegerSettingField(localizedString(language, R.string.second_violation_min), rules.secondViolationPenaltyMinutes, { v -> editRules { it.copy(secondViolationPenaltyMinutes = v) } }, 0, 9999)
                IntegerSettingField(localizedString(language, R.string.third_violation_min), rules.thirdViolationPenaltyMinutes, { v -> editRules { it.copy(thirdViolationPenaltyMinutes = v) } }, 0, 9999)
                IntegerSettingField(localizedString(language, R.string.subsequent_violation_min), rules.subsequentViolationPenaltyMinutes, { v -> editRules { it.copy(subsequentViolationPenaltyMinutes = v) } }, 0, 9999)
            }
        }
        SectionCard {
            CardTitle(localizedString(language, R.string.challenge_group_sensitivity))
            Spacer(Modifier.height(8.dp))
            Text(localizedString(language, R.string.pose_deviation_tolerance), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            DriftToleranceDropdown(language, driftTolerancePresetFor(rules.driftThresholdFactor)) { preset ->
                editRules { it.copy(driftThresholdFactor = preset.thresholdFactor) }
            }
            Spacer(Modifier.height(8.dp))
            Text(localizedString(language, R.string.motion_sensitivity), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            MotionSensitivityDropdown(language, motionSensitivityPresetFor(rules.motionThresholdFactor)) { preset ->
                editRules { it.copy(motionThresholdFactor = preset.thresholdFactor) }
            }
        }
        SectionCard {
            CardTitle(localizedString(language, R.string.challenge_group_face))
            RadioOptions(
                options = listOf(FaceCheckMode.Disabled, FaceCheckMode.FaceToCamera, FaceCheckMode.FaceAwayFromCamera),
                selected = rules.faceCheckMode,
                label = { localizedString(language, faceModeLabel(it)) },
                onSelected = { mode -> editRules { it.copy(faceCheckMode = mode) } }
            )
        }
        SoundsEditor(viewModel, language, rules, ::editRules)
        IntifaceEditor(language, rules, intifaceState.isSupported, ::editRules)
        SectionCard {
            IntegerSettingField(localizedString(language, R.string.challenge_attempts), draft.attemptsAllowed, { v -> edit { it.copy(attemptsAllowed = v) } }, 0, 99)
        }
        SectionCard {
            CardTitle(localizedString(language, R.string.challenge_visibility))
            Spacer(Modifier.height(4.dp))
            SwitchRow(localizedString(language, R.string.challenge_show_duration), draft.visibility.showDuration) { v ->
                edit { it.copy(visibility = it.visibility.copy(showDuration = v)) }
            }
            SwitchRow(localizedString(language, R.string.challenge_show_penalties), draft.visibility.showPenalties) { v ->
                edit { it.copy(visibility = it.visibility.copy(showPenalties = v)) }
            }
            Text(
                localizedString(language, R.string.challenge_visibility_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
        Button(
            enabled = !creating,
            onClick = {
                if (draft.title.isBlank()) {
                    Toast.makeText(context, titleRequiredText, Toast.LENGTH_SHORT).show()
                    return@Button
                }
                creating = true
                scope.launch {
                    val created = viewModel.createChallenge(draft)
                    creating = false
                    onNavigate(ChallengePage.Brief(created.spec.id))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            IconLabel(Icons.Filled.Add, localizedString(language, R.string.challenge_create_button), FontWeight.Bold)
        }
    }
}

@Composable
private fun DurationEditor(language: AppLanguage, timer: TimerSpec, onChange: (TimerSpec) -> Unit) {
    SectionCard {
        CardTitle(localizedString(language, R.string.challenge_duration))
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(TimerMode.Exact to R.string.challenge_duration_exact, TimerMode.Random to R.string.challenge_duration_random)
                .forEachIndexed { index, (mode, labelRes) ->
                    SegmentedButton(
                        selected = timer.mode == mode,
                        onClick = { onChange(timer.copy(mode = mode)) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2)
                    ) { Text(localizedString(language, labelRes)) }
                }
        }
        Spacer(Modifier.height(8.dp))
        val maxMinutes = MAX_CHALLENGE_SECONDS / 60
        if (timer.mode == TimerMode.Exact) {
            IntegerSettingField(localizedString(language, R.string.challenge_minutes), (timer.exactSeconds / 60).coerceAtLeast(1), { onChange(timer.copy(exactSeconds = it * 60)) }, 1, maxMinutes)
        } else {
            IntegerSettingField(localizedString(language, R.string.challenge_min_minutes), (timer.randomMinSeconds / 60).coerceAtLeast(1), { minutes ->
                onChange(timer.copy(randomMinSeconds = minutes * 60, randomMaxSeconds = maxOf(timer.randomMaxSeconds, minutes * 60)))
            }, 1, maxMinutes)
            IntegerSettingField(localizedString(language, R.string.challenge_max_minutes), (timer.randomMaxSeconds / 60).coerceAtLeast(1), { minutes ->
                onChange(timer.copy(randomMaxSeconds = minutes * 60, randomMinSeconds = minOf(timer.randomMinSeconds, minutes * 60)))
            }, 1, maxMinutes)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> RadioOptions(options: List<T>, selected: T, label: @Composable (T) -> String, onSelected: (T) -> Unit) {
    options.forEach { option ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onSelected(option) }
        ) {
            RadioButton(selected = selected == option, onClick = { onSelected(option) })
            Text(label(option), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A card that shows a one-line summary and expands to its full settings. */
@Composable
private fun ExpandableSection(
    language: AppLanguage,
    title: String,
    summary: String,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                CardTitle(title)
                Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            TextButton(onClick = { expanded = !expanded }) {
                IconLabel(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    localizedString(language, if (expanded) R.string.challenge_collapse else R.string.settings_configure)
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

private fun faceModeLabel(mode: FaceCheckMode): Int = when (mode) {
    FaceCheckMode.Disabled -> R.string.do_not_check
    FaceCheckMode.FaceToCamera -> R.string.face_to_camera
    FaceCheckMode.FaceAwayFromCamera -> R.string.face_away
}

/** Plays the editor's sound previews: embedded clips and PCM tones. */
private class CuePreview(private val context: Context) {
    var playing by mutableStateOf<AudioCue?>(null)
        private set
    private var player: MediaPlayer? = null
    private val pcm = PcmSignalPlayer()
    private val main = Handler(Looper.getMainLooper())

    fun toggleFile(cue: AudioCue, uri: String) {
        if (playing == cue) return stop()
        stop()
        val media = MediaPlayer()
        player = media
        playing = cue
        runCatching {
            media.setDataSource(context, Uri.parse(uri))
            media.setOnPreparedListener { if (player === it) it.start() }
            media.setOnCompletionListener { release(it) }
            media.setOnErrorListener { failed, _, _ -> release(failed); true }
            media.prepareAsync()
        }.onFailure { release(media) }
    }

    fun togglePcm(cue: AudioCue, settings: PcmSignalSettings) {
        if (playing == cue) return stop()
        stop()
        playing = cue
        runCatching { pcm.play(settings) { main.post { if (playing == cue && player == null) playing = null } } }
            .onFailure { playing = null }
    }

    fun stop() {
        player?.let(::release)
        pcm.stop()
        playing = null
    }

    fun close() {
        stop()
        pcm.close()
    }

    private fun release(media: MediaPlayer) {
        if (player === media) {
            player = null
            playing = null
        }
        runCatching { media.stop() }
        runCatching { media.release() }
    }
}

@Composable
private fun SoundsEditor(
    viewModel: GameViewModel,
    language: AppLanguage,
    rules: ChallengeRules,
    editRules: ((ChallengeRules) -> ChallengeRules) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preview = remember { CuePreview(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { preview.close() } }
    var cueAwaitingFile by remember { mutableStateOf<AudioCue?>(null) }
    var pcmConfiguring by remember { mutableStateOf<AudioCue?>(null) }
    var ttsConfiguring by remember { mutableStateOf<TtsPhraseTemplate?>(null) }
    val messages = mapOf(
        AudioAttachResult.TooLarge to localizedString(language, R.string.challenge_audio_too_large),
        AudioAttachResult.TotalTooLarge to localizedString(language, R.string.challenge_audio_total_too_large),
        AudioAttachResult.Unreadable to localizedString(language, R.string.challenge_audio_unreadable)
    )
    // Clips are played from private copies; the picked document may not stay readable.
    val previewFiles = rememberLoaded(rules.audioClips) {
        val dir = File(context.cacheDir, "challenge_preview").apply { mkdirs() }
        rules.audioClips.mapValues { (cue, clip) ->
            File(dir, cue.name).apply { writeBytes(clip.data.bytes) }.let { Uri.fromFile(it).toString() }
        }
    }.orEmpty()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val cue = cueAwaitingFile
        cueAwaitingFile = null
        if (cue == null || uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            messages[viewModel.attachDraftAudio(cue, uri)]?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
    }
    val clipBytes = rules.audioClips.values.sumOf { it.data.size }
    val summary = when {
        rules.audioClips.isNotEmpty() -> localizedFormatString(
            language,
            R.string.challenge_sounds_files,
            rules.audioClips.size,
            Formatter.formatShortFileSize(context, clipBytes.toLong())
        )
        rules.usesCustomSounds -> localizedString(language, R.string.challenge_sounds_custom)
        else -> localizedString(language, R.string.challenge_sounds_default)
    }

    ExpandableSection(language, localizedString(language, R.string.challenge_group_sounds), summary) {
        Text(localizedString(language, R.string.challenge_sounds_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        AudioCue.entries.forEach { cue ->
            val cueSettings = rules.audioCueSettings[cue] ?: AudioCueSettings()
            val template = cue.ttsPhraseTemplate()
            AudioCueSettingRow(
                language = language,
                cue = cue,
                cueSettings = cueSettings.copy(audioFileUri = previewFiles[cue]),
                ttsText = rules.customTtsTemplates[template] ?: localizedString(language, template.defaultTextRes()),
                isAudioFilePreviewing = preview.playing == cue,
                isPcmPreviewing = preview.playing == cue,
                onModeSelected = { mode ->
                    preview.stop()
                    if (mode == AudioCueMode.AudioFile) {
                        cueAwaitingFile = cue
                        picker.launch(arrayOf("audio/*"))
                    } else {
                        editRules {
                            it.copy(
                                audioCueSettings = it.audioCueSettings + (cue to cueSettings.copy(mode = mode)),
                                audioClips = it.audioClips - cue
                            )
                        }
                    }
                },
                onAudioFilePreviewClick = { previewFiles[cue]?.let { preview.toggleFile(cue, it) } },
                onPcmPreviewClick = { preview.togglePcm(cue, cueSettings.pcmSettings) },
                onPcmConfigureClick = {
                    preview.stop()
                    pcmConfiguring = cue
                },
                onTtsConfigureClick = {
                    preview.stop()
                    ttsConfiguring = template
                },
                colorScheme = MaterialTheme.colorScheme
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
        val penaltyTemplate = TtsPhraseTemplate.PenaltyAddedToTimer
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(localizedString(language, R.string.audio_cue_penalty_added_to_timer), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                Text(
                    rules.customTtsTemplates[penaltyTemplate] ?: localizedString(language, penaltyTemplate.defaultTextRes()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = { ttsConfiguring = penaltyTemplate }) {
                Text(localizedString(language, R.string.settings_configure))
            }
        }
    }

    pcmConfiguring?.let { cue ->
        val cueSettings = rules.audioCueSettings[cue] ?: AudioCueSettings()
        PcmSettingsDialog(
            language = language,
            initialSettings = cueSettings.pcmSettings,
            onDismiss = { pcmConfiguring = null },
            onApply = { pcm ->
                editRules { it.copy(audioCueSettings = it.audioCueSettings + (cue to cueSettings.copy(mode = AudioCueMode.Pcm, pcmSettings = pcm))) }
                pcmConfiguring = null
            }
        )
    }
    ttsConfiguring?.let { template ->
        TtsPhraseSettingsDialog(
            language = language,
            template = template,
            initialCustomText = rules.customTtsTemplates[template],
            defaultText = localizedString(language, template.defaultTextRes()),
            onDismiss = { ttsConfiguring = null },
            onApply = { text ->
                editRules {
                    val trimmed = text?.take(ChallengeRules.MAX_PHRASE_LENGTH)
                    it.copy(customTtsTemplates = if (trimmed == null) it.customTtsTemplates - template else it.customTtsTemplates + (template to trimmed))
                }
                ttsConfiguring = null
            }
        )
    }
}

@Composable
private fun IntifaceEditor(
    language: AppLanguage,
    rules: ChallengeRules,
    supportedHere: Boolean,
    editRules: ((ChallengeRules) -> ChallengeRules) -> Unit
) {
    val summary = when {
        !rules.usesIntiface -> localizedString(language, R.string.challenge_intiface_off)
        rules.intifaceRequired -> localizedString(language, R.string.challenge_intiface_used_required)
        else -> localizedString(language, R.string.challenge_intiface_used)
    }
    ExpandableSection(language, localizedString(language, R.string.challenge_group_intiface), summary) {
        Text(
            localizedString(language, if (supportedHere) R.string.challenge_intiface_hint else R.string.challenge_intiface_hint_offline),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(localizedString(language, R.string.intiface_background_title), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        RadioOptions(
            options = IntifaceBackgroundMode.entries,
            selected = rules.intifaceBackgroundMode,
            label = { localizedString(language, if (it == IntifaceBackgroundMode.Off) R.string.intiface_mode_off else R.string.intiface_mode_vibration) },
            onSelected = { mode -> editRules { it.copy(intifaceBackgroundMode = mode) } }
        )
        if (rules.intifaceBackgroundMode == IntifaceBackgroundMode.Vibration) {
            IntifaceVibrationSettingsEditor(language, rules.intifaceBackgroundVibration, { v -> editRules { it.copy(intifaceBackgroundVibration = v) } }, true, false)
        }
        Spacer(Modifier.height(8.dp))
        Text(localizedString(language, R.string.intiface_violations_title), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        RadioOptions(
            options = IntifaceViolationMode.entries,
            selected = rules.intifaceViolationMode,
            label = {
                localizedString(
                    language,
                    when (it) {
                        IntifaceViolationMode.Off -> R.string.intiface_mode_off
                        IntifaceViolationMode.Vibration -> R.string.intiface_mode_vibration
                        IntifaceViolationMode.Pause -> R.string.intiface_mode_pause
                    }
                )
            },
            onSelected = { mode -> editRules { it.copy(intifaceViolationMode = mode) } }
        )
        when (rules.intifaceViolationMode) {
            IntifaceViolationMode.Off -> Unit
            IntifaceViolationMode.Vibration ->
                IntifaceVibrationSettingsEditor(language, rules.intifaceViolationVibration, { v -> editRules { it.copy(intifaceViolationVibration = v) } }, true, true)
            IntifaceViolationMode.Pause -> DoubleSettingField(
                localizedString(language, R.string.intiface_pause_duration_seconds),
                rules.intifaceViolationPauseSeconds,
                { v -> editRules { it.copy(intifaceViolationPauseSeconds = v) } },
                0.05,
                60.0,
                2
            )
        }
        if (rules.usesIntiface) {
            Spacer(Modifier.height(8.dp))
            SwitchRow(localizedString(language, R.string.challenge_intiface_required), rules.intifaceRequired) { v ->
                editRules { it.copy(intifaceRequired = v) }
            }
        }
    }
}

// Brief

@Composable
private fun ChallengeBrief(
    viewModel: GameViewModel,
    language: AppLanguage,
    id: Blob,
    refreshKey: Any?,
    onNavigate: (ChallengePage?) -> Unit,
    modifier: Modifier
) {
    val scope = rememberCoroutineScope()
    val repository = viewModel.challengeRepository
    var localRefresh by remember { mutableIntStateOf(0) }
    val loaded = rememberLoaded(Triple(id, refreshKey, localRefresh)) {
        val authored = repository.authoredChallenge(id)
        val received = repository.receivedChallenge(id)
        val reports = if (authored != null) repository.incomingReports().filter { it.report.challengeId == id } else emptyList()
        Triple(authored, received, reports)
    }
    val intifaceState by viewModel.intifaceState.collectAsState()
    val authored = loaded?.first
    val received = loaded?.second
    val spec = authored?.spec ?: received?.spec
    val backPage = ChallengePage.Hub(if (authored != null) 1 else 0)

    ChallengeScaffold(spec?.title ?: localizedString(language, R.string.challenges_title), language, onBack = { onNavigate(backPage) }, modifier) {
        if (loaded == null) {
            EmptyText(localizedString(language, R.string.report_loading))
            return@ChallengeScaffold
        }
        if (spec == null) {
            EmptyText(localizedString(language, R.string.challenge_import_damaged))
            return@ChallengeScaffold
        }
        val own = authored != null
        if (spec.authorName.isNotBlank()) {
            Text(localizedFormatString(language, R.string.challenge_by_author, spec.authorName), color = MaterialTheme.colorScheme.onBackground)
        }
        if (spec.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(spec.description, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp)
        }
        Spacer(Modifier.height(12.dp))
        SectionCard {
            challengeRuleRows(language, spec, revealAll = own, intifaceSupported = intifaceState.isSupported).forEach { (label, value) ->
                InfoRow(label, value)
            }
            InfoRow(
                localizedString(language, R.string.challenge_attempts).substringBefore(" ("),
                if (own) {
                    if (spec.attemptsAllowed == 0) localizedString(language, R.string.challenge_attempts_unlimited) else spec.attemptsAllowed.toString()
                } else {
                    attemptsText(language, received?.attemptsLeft)
                }
            )
        }
        val canRun = own || received?.attemptsLeft != 0
        Button(
            enabled = canRun,
            onClick = {
                scope.launch {
                    if (viewModel.acceptChallenge(spec.id)) onNavigate(null)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            IconLabel(
                if (own) Icons.Filled.Science else Icons.Filled.PlayArrow,
                localizedString(language, if (own) R.string.challenge_test_run else R.string.challenge_accept),
                FontWeight.Bold
            )
        }
        Spacer(Modifier.height(12.dp))
        if (own) {
            ShareChallengeCard(viewModel, language, spec)
            val reports = loaded.third
            if (reports.isNotEmpty()) {
                CardTitle(localizedString(language, R.string.challenge_reports_incoming))
                Spacer(Modifier.height(8.dp))
                reports.forEach { item ->
                    ListCard(
                        title = item.report.executorName.ifBlank { localizedString(language, R.string.report_anonymous) },
                        subtitle = formatDate(item.report.endedAtWallMs),
                        detail = localizedString(language, endCauseLabel(item.report.endCause)),
                        onClick = { onNavigate(ChallengePage.Report(item.report.reportId)) }
                    )
                }
            }
        }
        DeleteButton(language) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    if (own) repository.deleteAuthored(spec.id) else repository.deleteReceived(spec.id)
                }
                if (viewModel.activeChallenge.value?.spec?.id == spec.id) viewModel.leaveChallenge()
                onNavigate(backPage)
            }
        }
    }
}

@Composable
private fun ShareChallengeCard(viewModel: GameViewModel, language: AppLanguage, spec: ChallengeSpec) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val taskFile = rememberLoaded(spec.id) { viewModel.challengeRepository.taskFile(spec) }
    // Embedded audio makes the task far too large for a text code or a QR code.
    val fileOnly = spec.rules.audioClips.isNotEmpty()
    val code = taskFile?.takeUnless { fileOnly }?.let(ChallengeFiles::toTextCode)
    var showQr by remember { mutableStateOf(false) }
    val shareTitle = localizedString(language, R.string.challenge_share)
    val copiedText = localizedString(language, R.string.challenge_code_copied)
    val shareText = code?.let { localizedFormatString(language, R.string.challenge_share_text, spec.title, it) }

    SectionCard {
        CardTitle(shareTitle)
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = taskFile != null,
            onClick = {
                val bytes = taskFile ?: return@Button
                scope.launch {
                    val file = withContext(Dispatchers.IO) {
                        ChallengeShare.stage(context, bytes, ChallengeShare.fileName(spec.title, ChallengeFiles.TASK_EXTENSION))
                    }
                    ChallengeShare.shareFile(context, file, shareTitle)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { IconLabel(Icons.Filled.Share, localizedString(language, R.string.challenge_share_file)) }
        if (fileOnly) {
            Text(
                localizedString(language, R.string.challenge_share_file_only),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            return@SectionCard
        }
        OutlinedButton(
            enabled = shareText != null,
            onClick = { shareText?.let { ChallengeShare.shareText(context, it, shareTitle) } },
            modifier = Modifier.fillMaxWidth()
        ) { IconLabel(Icons.Filled.Textsms, localizedString(language, R.string.challenge_share_code)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = code != null,
                onClick = {
                    code?.let {
                        ChallengeShare.copyText(context, spec.title, it)
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    }
                },
                contentPadding = CompactButtonPadding,
                modifier = Modifier.weight(1f)
            ) { IconLabel(Icons.Filled.ContentCopy, localizedString(language, R.string.challenge_copy_code)) }
            OutlinedButton(
                enabled = code != null,
                onClick = { showQr = true },
                contentPadding = CompactButtonPadding,
                modifier = Modifier.weight(1f)
            ) { IconLabel(Icons.Filled.QrCode, localizedString(language, R.string.challenge_show_qr)) }
        }
    }

    if (showQr && code != null) {
        val qr = remember(code) { QrCodes.encode(code, 900) }
        AlertDialog(
            onDismissRequest = { showQr = false },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text(localizedString(language, R.string.final_close)) } },
            title = { Text(spec.title) },
            text = {
                if (qr != null) {
                    QrImage(qr)
                } else {
                    Text(localizedString(language, R.string.challenge_qr_too_large))
                }
            }
        )
    }
}

@Composable
private fun QrImage(bitmap: Bitmap) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(androidx.compose.ui.graphics.Color.White)
    )
}

/**
 * Rule summary for the brief. Executors see the duration and penalties only when the author
 * allowed it, everything else in short form; the author ([revealAll]) sees everything and which
 * parts are hidden from the executor.
 */
@Composable
private fun challengeRuleRows(
    language: AppLanguage,
    spec: ChallengeSpec,
    revealAll: Boolean,
    intifaceSupported: Boolean
): List<Pair<String, String>> {
    val rules = spec.rules
    val context = LocalContext.current
    val hidden = localizedString(language, R.string.challenge_hidden_value)
    val hiddenNote = " · " + localizedString(language, R.string.challenge_hidden_from_executor)
    val rows = mutableListOf<Pair<String, String>>()

    val duration = if (rules.timer.mode == TimerMode.Exact) {
        formatDurationHms(rules.timer.exactSeconds)
    } else {
        localizedFormatString(language, R.string.challenge_range_value, formatDurationHms(rules.timer.randomMinSeconds), formatDurationHms(rules.timer.randomMaxSeconds))
    }
    rows += localizedString(language, R.string.challenge_group_duration) to when {
        spec.visibility.showDuration -> duration
        revealAll -> duration + hiddenNote
        else -> hidden
    }

    rows += localizedString(language, R.string.challenge_group_errors) to
        if (rules.maxViolations == 0) localizedString(language, R.string.challenge_errors_none)
        else localizedFormatString(language, R.string.challenge_errors_value, rules.maxViolations)

    val penalties = if (!rules.penaltiesEnabled) {
        localizedString(language, R.string.challenge_penalties_off)
    } else {
        localizedFormatString(
            language,
            R.string.challenge_penalties_value,
            rules.firstViolationPenaltyMinutes,
            rules.secondViolationPenaltyMinutes,
            rules.thirdViolationPenaltyMinutes,
            rules.subsequentViolationPenaltyMinutes
        ) + " · " + localizedFormatString(language, R.string.challenge_penalty_interval_value, rules.minimumPenaltyIntervalSeconds)
    }
    rows += localizedString(language, R.string.challenge_group_penalties) to when {
        spec.visibility.showPenalties -> penalties
        revealAll -> penalties + hiddenNote
        else -> hidden
    }

    rows += localizedString(language, R.string.challenge_group_sensitivity) to localizedFormatString(
        language,
        R.string.challenge_sensitivity_value,
        localizedString(language, driftTolerancePresetFor(rules.driftThresholdFactor).nameRes),
        localizedString(language, motionSensitivityPresetFor(rules.motionThresholdFactor).nameRes)
    )

    rows += localizedString(language, R.string.challenge_group_face) to localizedString(language, faceModeLabel(rules.faceCheckMode))

    rows += localizedString(language, R.string.challenge_group_sounds) to when {
        revealAll && rules.audioClips.isNotEmpty() -> localizedFormatString(
            language,
            R.string.challenge_sounds_files,
            rules.audioClips.size,
            Formatter.formatShortFileSize(context, rules.audioClips.values.sumOf { it.data.size }.toLong())
        )
        rules.usesCustomSounds -> localizedString(language, R.string.challenge_sounds_custom)
        else -> localizedString(language, R.string.challenge_sounds_default)
    }

    if (rules.usesIntiface || intifaceSupported) {
        val required = if (rules.intifaceRequired) " · " + localizedString(language, R.string.challenge_intiface_used_required) else ""
        rows += localizedString(language, R.string.challenge_group_intiface) to when {
            !rules.usesIntiface -> localizedString(language, R.string.challenge_intiface_off)
            !revealAll -> localizedString(language, if (rules.intifaceRequired) R.string.challenge_intiface_used_required else R.string.challenge_intiface_used)
            else -> localizedFormatString(
                language,
                R.string.challenge_intiface_details,
                localizedString(language, if (rules.intifaceBackgroundMode == IntifaceBackgroundMode.Off) R.string.challenge_intiface_mode_off else R.string.challenge_intiface_mode_vibration),
                localizedString(
                    language,
                    when (rules.intifaceViolationMode) {
                        IntifaceViolationMode.Off -> R.string.challenge_intiface_mode_off
                        IntifaceViolationMode.Vibration -> R.string.challenge_intiface_mode_vibration
                        IntifaceViolationMode.Pause -> R.string.challenge_intiface_mode_pause
                    }
                )
            ) + required
        }
    }
    return rows
}

// Sending a report

@Composable
internal fun SendReportDialog(
    viewModel: GameViewModel,
    language: AppLanguage,
    report: ChallengeReport,
    challengeTitle: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(viewModel.challengeRepository.executorName) }
    var comment by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val chooserTitle = localizedString(language, R.string.challenge_send_report)
    val failedText = localizedString(language, R.string.challenge_msg_report_save_failed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(chooserTitle) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(ChallengeSpec.MAX_AUTHOR_LENGTH) },
                    label = { Text(localizedString(language, R.string.challenge_field_author)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(500) },
                    label = { Text(localizedString(language, R.string.challenge_report_comment)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !sending, onClick = {
                sending = true
                scope.launch {
                    val file = withContext(Dispatchers.IO) {
                        val repository = viewModel.challengeRepository
                        repository.executorName = name
                        repository.reportFile(report, name, comment)?.let { bytes ->
                            ChallengeShare.stage(context, bytes, ChallengeShare.fileName(challengeTitle, ChallengeFiles.REPORT_EXTENSION))
                        }
                    }
                    sending = false
                    if (file == null) {
                        Toast.makeText(context, failedText, Toast.LENGTH_SHORT).show()
                    } else {
                        ChallengeShare.shareFile(context, file, chooserTitle)
                        onDismiss()
                    }
                }
            }) { Text(localizedString(language, R.string.challenge_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localizedString(language, R.string.cancel)) } }
    )
}

// Report

@Composable
private fun ReportScreen(
    viewModel: GameViewModel,
    language: AppLanguage,
    reportId: Blob,
    onNavigate: (ChallengePage?) -> Unit,
    modifier: Modifier
) {
    val scope = rememberCoroutineScope()
    val repository = viewModel.challengeRepository
    val incoming = rememberLoaded(reportId) { repository.incomingReports().firstOrNull { it.report.reportId == reportId } }
    val replay = rememberLoaded(incoming) {
        val trace = incoming?.report?.trace ?: return@rememberLoaded null
        runCatching {
            ReplayData.from(
                frames = trace.frames(),
                reference = trace.reference,
                clockStartedAtMs = trace.clockStartedAtMs,
                endedAtMs = incoming.report.endedAtMonoMs,
                imageWidth = trace.imageWidth,
                imageHeight = trace.imageHeight
            )
        }.getOrNull()
    }
    val backPage = incoming?.let { ChallengePage.Brief(it.challenge.spec.id) } ?: ChallengePage.Hub(2)

    ChallengeScaffold(localizedString(language, R.string.report_viewer_title), language, onBack = { onNavigate(backPage) }, modifier) {
        val item = incoming
        if (item == null) {
            EmptyText(localizedString(language, R.string.report_loading))
            return@ChallengeScaffold
        }
        val report = item.report
        val verdict = rememberLoaded(item) { reportVerifier.verify(report, item.challenge.spec) }
        Text(item.challenge.spec.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            localizedString(language, endCauseLabel(report.endCause)),
            color = if (report.success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            localizedString(
                language,
                when (verdict?.kind) {
                    null -> R.string.report_loading
                    VerdictKind.Unverified -> R.string.report_verdict_unverified
                    VerdictKind.Confirmed -> R.string.report_verdict_confirmed
                    VerdictKind.Suspicious -> R.string.report_verdict_suspicious
                    VerdictKind.Mismatch -> R.string.report_verdict_mismatch
                }
            ) + verdict?.reasons.orEmpty().map { reason ->
                localizedString(language, when (reason) {
                    "task" -> R.string.report_reason_task
                    "attempts" -> R.string.report_reason_attempts
                    "timing" -> R.string.report_reason_timing
                    "trace" -> R.string.report_reason_trace
                    "static" -> R.string.report_reason_static
                    "events" -> R.string.report_reason_events
                    "no_trace" -> R.string.report_reason_no_trace
                    else -> R.string.report_reason_result
                })
            }.joinToString(prefix = if (verdict?.reasons.isNullOrEmpty()) "" else ": "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))
        SectionCard {
            InfoRow(localizedString(language, R.string.challenge_field_author), report.executorName.ifBlank { localizedString(language, R.string.report_anonymous) })
            InfoRow(localizedString(language, R.string.report_started), formatDate(report.startedAtWallMs))
            InfoRow(localizedFormatString(language, R.string.report_attempt, report.attempt), "")
            InfoRow(localizedString(language, R.string.final_initial_timer), formatDurationHms(report.initialTimerSeconds))
            InfoRow(localizedString(language, R.string.final_actual_timer), formatDurationHms(report.actualTimerSeconds))
            if (item.challenge.spec.rules.usesIntiface) {
                Text(
                    localizedString(language, if (report.intifaceConnected) R.string.report_intiface_connected else R.string.report_intiface_not_connected),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            if (report.executorComment.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                CardTitle(localizedString(language, R.string.report_comment))
                Text(report.executorComment, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionCard {
            CardTitle(localizedString(language, R.string.final_errors))
            InfoRow(localizedString(language, R.string.movement_gauge_drift), report.counts.drift.toString())
            InfoRow(localizedString(language, R.string.movement_gauge_motion), report.counts.motion.toString())
            InfoRow(localizedString(language, R.string.violation_count_face), report.counts.face.toString())
            InfoRow(localizedString(language, R.string.violation_count_disappeared), report.counts.disappeared.toString())
        }
        SectionCard {
            CardTitle(localizedString(language, R.string.report_replay))
            Spacer(Modifier.height(8.dp))
            val trace = report.trace
            when {
                trace == null -> Text(localizedString(language, R.string.report_no_trace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                replay == null -> Text(localizedString(language, R.string.report_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    ReportReplayPlayer(data = replay, events = report.events)
                    if (trace.truncated) {
                        Text(localizedString(language, R.string.report_trace_truncated), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }
        SectionCard {
            CardTitle(localizedString(language, R.string.report_events))
            Spacer(Modifier.height(4.dp))
            if (report.events.isEmpty()) {
                Text(localizedString(language, R.string.report_no_events), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val start = report.trace?.clockStartedAtMs ?: report.startedAtMonoMs
            report.events.forEach { event ->
                val what = localizedString(
                    language,
                    when (event.type) {
                        TraceViolationType.Drift -> R.string.movement_gauge_drift
                        TraceViolationType.Motion -> R.string.movement_gauge_motion
                        TraceViolationType.PersonDisappeared -> R.string.violation_count_disappeared
                        TraceViolationType.Face -> R.string.violation_count_face
                    }
                )
                val outcome = if (event.defeat) {
                    localizedString(language, R.string.report_event_defeat)
                } else {
                    localizedFormatString(language, R.string.report_event_penalty, event.penaltyMinutes)
                }
                InfoRow("${formatDurationHms(((event.atMs - start) / 1000L).coerceAtLeast(0L))}  #${event.violationIndex} $what", outcome)
            }
        }
        DeleteButton(language) {
            scope.launch {
                withContext(Dispatchers.IO) { repository.deleteIncoming(report.reportId) }
                onNavigate(backPage)
            }
        }
    }
}

// Import feedback

/** Text for the import toast and the page to open, or null to stay where the user is. */
@Composable
internal fun importFeedback(language: AppLanguage, result: ImportResult): Pair<String, ChallengePage?> = when (result) {
    is ImportResult.TaskImported ->
        (if (result.alreadyKnown) localizedString(language, R.string.challenge_already_imported)
        else localizedFormatString(language, R.string.challenge_imported, result.challenge.spec.title)) to
            ChallengePage.Brief(result.challenge.spec.id)
    is ImportResult.OwnTask -> localizedString(language, R.string.challenge_own_task) to ChallengePage.Brief(result.challenge.spec.id)
    is ImportResult.ReportImported ->
        (if (result.alreadyKnown) localizedString(language, R.string.report_already_imported)
        else localizedFormatString(
            language,
            R.string.report_imported,
            result.report.report.executorName.ifBlank { result.report.challenge.spec.title }
        )) to ChallengePage.Report(result.report.report.reportId)
    is ImportResult.Failed -> localizedString(
        language,
        when (result.error) {
            ChallengeFileError.NotAChallengeFile -> R.string.challenge_import_not_file
            ChallengeFileError.UnsupportedVersion -> R.string.challenge_import_version
            ChallengeFileError.WrongDomain -> R.string.challenge_import_domain
            ChallengeFileError.Damaged -> R.string.challenge_import_damaged
            ChallengeFileError.UnknownChallenge -> R.string.challenge_import_unknown
        }
    ) to null
}
