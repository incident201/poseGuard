package com.incident201.poseguard.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.incident201.poseguard.BuildConfig
import com.incident201.poseguard.R
import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.audio.AudioCueEvent
import com.incident201.poseguard.audio.AudioCueMode
import com.incident201.poseguard.audio.AudioCueSettings
import com.incident201.poseguard.audio.PcmChannel
import com.incident201.poseguard.audio.PcmPattern
import com.incident201.poseguard.audio.PcmSignalSettings
import com.incident201.poseguard.audio.TtsPhraseTemplate
import com.incident201.poseguard.audio.TtsVoiceMode
import com.incident201.poseguard.challenge.ActiveChallenge
import com.incident201.poseguard.challenge.AuthoredChallenge
import com.incident201.poseguard.challenge.Blob
import com.incident201.poseguard.challenge.ChallengeRules
import com.incident201.poseguard.challenge.TimerSpec
import com.incident201.poseguard.challenge.ChallengeFileError
import com.incident201.poseguard.challenge.ChallengeFileKind
import com.incident201.poseguard.challenge.ChallengeFiles
import com.incident201.poseguard.challenge.ChallengeReport
import com.incident201.poseguard.challenge.ChallengeRepository
import com.incident201.poseguard.challenge.ImportResult
import com.incident201.poseguard.challenge.ChallengeSessionRecorder
import com.incident201.poseguard.challenge.AudioAttachResult
import com.incident201.poseguard.challenge.AudioClip
import com.incident201.poseguard.challenge.ChallengeDraft
import com.incident201.poseguard.challenge.SessionEndCause
import com.incident201.poseguard.challenge.TraceViolationEvent
import com.incident201.poseguard.challenge.TraceViolationType
import com.incident201.poseguard.challenge.applyTo
import com.incident201.poseguard.challenge.quantizedForTrace
import com.incident201.poseguard.intiface.IntifaceBackgroundMode
import com.incident201.poseguard.intiface.IntifaceController
import com.incident201.poseguard.intiface.IntifaceDeviceInfo
import com.incident201.poseguard.intiface.IntifaceUiState
import com.incident201.poseguard.intiface.IntifaceVibrationPattern
import com.incident201.poseguard.intiface.IntifaceVibrationSettings
import com.incident201.poseguard.intiface.IntifaceViolationMode
import com.incident201.poseguard.intiface.createIntifaceController
import com.incident201.poseguard.tracker.AccelerationMode
import com.incident201.poseguard.tracker.CameraFrame
import com.incident201.poseguard.tracker.FaceDetectionStatus
import com.incident201.poseguard.tracker.FaceCandidateCropper
import com.incident201.poseguard.tracker.FaceDetectorService
import com.incident201.poseguard.tracker.MovementTracker
import com.incident201.poseguard.tracker.Point3D
import com.incident201.poseguard.tracker.PoseFrameCropper
import com.incident201.poseguard.tracker.PoseIdentityStabilizationResult
import com.incident201.poseguard.tracker.PoseIdentityStabilizer
import com.incident201.poseguard.tracker.PoseLandmarkerModel
import com.incident201.poseguard.tracker.PoseLandmarks
import com.incident201.poseguard.tracker.PoseOcclusionGuard
import com.incident201.poseguard.tracker.PoseOcclusionGuardConfig
import com.incident201.poseguard.tracker.PoseSmoother
import com.incident201.poseguard.tracker.landmark
import com.incident201.poseguard.util.hasRequiredLocalNetworkPermission
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

private const val PREF_OCCLUSION_FREEZE_VIS_ALWAYS = "occlusion_freeze_visibility_always"
private const val PREF_OCCLUSION_FREEZE_VIS_P10_ALWAYS = "occlusion_freeze_visibility_p10_always"
private const val PREF_OCCLUSION_FREEZE_VIS_HARD = "occlusion_freeze_visibility_hard"
private const val PREF_OCCLUSION_FREEZE_VIS_SOFT = "occlusion_freeze_visibility_soft"
private const val PREF_OCCLUSION_JITTER_FREEZE_THRESHOLD = "occlusion_jitter_freeze_threshold"
private const val PREF_WRIST_DRIFT_WEIGHT = "wrist_drift_weight"
private const val DEFAULT_OCCLUSION_FREEZE_VIS_ALWAYS = 0.05f
private const val DEFAULT_OCCLUSION_FREEZE_VIS_P10_ALWAYS = 0.02f
private const val DEFAULT_OCCLUSION_FREEZE_VIS_HARD = 0.08f
private const val DEFAULT_OCCLUSION_FREEZE_VIS_SOFT = 0.1f
private const val DEFAULT_OCCLUSION_JITTER_FREEZE_THRESHOLD = 0.01f
private const val DEFAULT_WRIST_DRIFT_WEIGHT = 0.7f
private const val PREF_DEBUG_MODE_ENABLED = "debug_mode_enabled"
private const val PREF_ACCELERATION_MODE = "acceleration_mode"
private const val PREF_ACCELERATION_MODE_AUTO_RESOLVED = "acceleration_mode_auto_resolved"
private const val PREF_ACCELERATION_MODE_VERSION_CODE = "acceleration_mode_version_code"
private const val PREF_POSE_LANDMARKER_MODEL = "pose_landmarker_model"
private const val PREF_ONBOARDING_COMPLETED = "onboarding_completed"
private const val PREF_CHALLENGE_INTRO_COMPLETED = "challenge_intro_completed"
private const val PREF_POSE_SMOOTHER_MIN_CUTOFF = "pose_smoother_min_cutoff"
private const val PREF_POSE_SMOOTHER_BETA = "pose_smoother_beta"
private const val PREF_POSE_SMOOTHER_DERIVATIVE_CUTOFF = "pose_smoother_derivative_cutoff"
private const val PREF_SENSITIVITY_PRESETS_VERSION = "sensitivity_presets_version"
private const val CURRENT_SENSITIVITY_PRESETS_VERSION = 2
private const val MAX_POSE_DROPOUT_HOLD_FRAMES = 5
private const val MAX_POSE_DROPOUT_HOLD_MS = 180L
private const val FACE_CHECK_UNAVAILABLE_TIMEOUT_MS = 5_000L
private const val PREF_CUSTOMIZE_AUDIO_ENABLED = "customize_audio_enabled"
private const val PREF_TTS_VOICE_MODE = "tts_voice_mode"
private const val PREF_AUDIO_CUE_MODE_PREFIX = "audio_cue_mode_"
private const val PREF_AUDIO_CUE_URI_PREFIX = "audio_cue_uri_"
private const val PREF_TTS_TEMPLATE_PREFIX = "tts_template_"
private const val LEGACY_PREF_AUDIO_CUE_TTS_TEXT_PREFIX = "audio_cue_tts_text_"
private const val PREF_AUDIO_CUE_PCM_FREQUENCY_PREFIX = "audio_cue_pcm_frequency_"
private const val PREF_AUDIO_CUE_PCM_DURATION_PREFIX = "audio_cue_pcm_duration_"
private const val PREF_AUDIO_CUE_PCM_CHANNEL_PREFIX = "audio_cue_pcm_channel_"
private const val PREF_AUDIO_CUE_PCM_AMPLITUDE_PREFIX = "audio_cue_pcm_amplitude_"
private const val PREF_AUDIO_CUE_PCM_FADE_IN_PREFIX = "audio_cue_pcm_fade_in_"
private const val PREF_AUDIO_CUE_PCM_FADE_OUT_PREFIX = "audio_cue_pcm_fade_out_"
private const val PREF_AUDIO_CUE_PCM_PATTERN_PREFIX = "audio_cue_pcm_pattern_"
private const val PREF_TIMER_MODE = "timer_mode"
private const val PREF_RANDOM_MIN_DURATION_SECONDS = "random_min_duration_seconds"
private const val PREF_RANDOM_MAX_DURATION_SECONDS = "random_max_duration_seconds"
private const val PREF_SELECTED_DURATION_SECONDS = "selected_duration_seconds"
private const val PREF_FACE_DETECTION_CONFIDENCE = "face_conf"
private const val PREF_INTIFACE_WEBSOCKET_URL = "intiface_websocket_url"
private const val PREF_INTIFACE_CONNECTION_ENABLED = "intiface_connection_enabled"
private const val PREF_INTIFACE_SELECTED_DEVICE_NAME = "intiface_selected_device_name"
private const val PREF_INTIFACE_SELECTED_DEVICE_DISPLAY_NAME = "intiface_selected_device_display_name"
private const val PREF_INTIFACE_SELECTED_DEVICE_INDEX = "intiface_selected_device_index"
private const val PREF_INTIFACE_BACKGROUND_MODE = "intiface_background_mode"
private const val PREF_INTIFACE_BACKGROUND_STRENGTH = "intiface_background_strength"
private const val PREF_INTIFACE_BACKGROUND_PATTERN = "intiface_background_pattern"
private const val PREF_INTIFACE_BACKGROUND_PULSE_LENGTH = "intiface_background_pulse_length"
private const val PREF_INTIFACE_BACKGROUND_PULSE_PAUSE = "intiface_background_pulse_pause"
private const val PREF_INTIFACE_VIOLATION_MODE = "intiface_violation_mode"
private const val PREF_INTIFACE_VIOLATION_STRENGTH = "intiface_violation_strength"
private const val PREF_INTIFACE_VIOLATION_PATTERN = "intiface_violation_pattern"
private const val PREF_INTIFACE_VIOLATION_PULSE_LENGTH = "intiface_violation_pulse_length"
private const val PREF_INTIFACE_VIOLATION_PULSE_PAUSE = "intiface_violation_pulse_pause"
private const val PREF_INTIFACE_VIOLATION_PAUSE_SECONDS = "intiface_violation_pause_seconds"
private const val PREF_INTIFACE_VIOLATION_DURATION = "intiface_violation_duration"
private const val DEFAULT_INTIFACE_WEBSOCKET_URL = "ws://127.0.0.1:12345"
private const val LEGACY_INTIFACE_WEBSOCKET_URL = "ws://10.0.2.2:12345/buttplug"

enum class GameState {
    Idle,
    WaitingForStabilization,
    StartingDelay,
    HoldingPose,
    Success,
    Failed
}

enum class TimerMode {
    Exact,
    Random
}

enum class FaceCheckMode {
    FaceToCamera,
    FaceAwayFromCamera,
    Disabled
}

enum class AppLanguage(val languageTag: String, val labelRes: Int) {
    English("en-US", R.string.language_english),
    Russian("ru-RU", R.string.language_russian),
    Spanish("es-ES", R.string.language_spanish),
    Italian("it-IT", R.string.language_italian),
    German("de-DE", R.string.language_german),
    French("fr-FR", R.string.language_french);

    val locale: Locale get() = Locale.forLanguageTag(languageTag)
}

internal fun accelerationModeAfterAppUpdate(
    savedMode: AccelerationMode,
    wasAutomaticallyResolved: Boolean,
    savedVersionCode: Int,
    currentVersionCode: Int
): AccelerationMode =
    if (wasAutomaticallyResolved && savedVersionCode != currentVersionCode) {
        AccelerationMode.Auto
    } else {
        savedMode
    }

data class GameSettings(
    val language: AppLanguage = AppLanguage.English,
    val accelerationMode: AccelerationMode = AccelerationMode.Auto,
    val poseLandmarkerModel: PoseLandmarkerModel = PoseLandmarkerModel.Heavy,
    val faceCheckMode: FaceCheckMode = FaceCheckMode.Disabled,
    val faceDetectionConfidence: Float = 0.8f,
    val driftThresholdFactor: Float = 0.160f,
    val motionThresholdFactor: Float = 0.04f,
    val minimumPenaltyIntervalSeconds: Int = 5,
    val maxViolations: Int = 4,
    val penaltiesEnabled: Boolean = true,
    val firstViolationPenaltyMinutes: Int = 1,
    val secondViolationPenaltyMinutes: Int = 3,
    val thirdViolationPenaltyMinutes: Int = 3,
    val subsequentViolationPenaltyMinutes: Int = 3,
    val timelapseRecordingEnabled: Boolean = true,
    val occlusionFreezeVisibilityAlways: Float = DEFAULT_OCCLUSION_FREEZE_VIS_ALWAYS,
    val occlusionFreezeVisibilityP10Always: Float = DEFAULT_OCCLUSION_FREEZE_VIS_P10_ALWAYS,
    val occlusionFreezeVisibilityHard: Float = DEFAULT_OCCLUSION_FREEZE_VIS_HARD,
    val occlusionFreezeVisibilitySoft: Float = DEFAULT_OCCLUSION_FREEZE_VIS_SOFT,
    val occlusionJitterFreezeThreshold: Float = DEFAULT_OCCLUSION_JITTER_FREEZE_THRESHOLD,
    val wristDriftWeight: Float = DEFAULT_WRIST_DRIFT_WEIGHT,
    val poseSmootherMinCutoff: Float = PoseSmoother.DEFAULT_MIN_CUTOFF,
    val poseSmootherBeta: Float = PoseSmoother.DEFAULT_BETA,
    val poseSmootherDerivativeCutoff: Float = PoseSmoother.DEFAULT_DERIVATIVE_CUTOFF,
    val debugModeEnabled: Boolean = false,
    val customizeAudioEnabled: Boolean = false,
    val ttsVoiceMode: TtsVoiceMode = TtsVoiceMode.DefaultVoice,
    val customTtsTemplates: Map<TtsPhraseTemplate, String> = emptyMap(),
    val audioCueSettings: Map<AudioCue, AudioCueSettings> =
        AudioCue.entries.associateWith { AudioCueSettings() },
    val intifaceWebSocketUrl: String = DEFAULT_INTIFACE_WEBSOCKET_URL,
    val intifaceConnectionEnabled: Boolean = false,
    val intifaceSelectedDeviceName: String = "",
    val intifaceSelectedDeviceDisplayName: String = "",
    val intifaceSelectedDeviceIndex: Long? = null,
    val intifaceBackgroundMode: IntifaceBackgroundMode = IntifaceBackgroundMode.Off,
    val intifaceBackgroundVibration: IntifaceVibrationSettings = IntifaceVibrationSettings(
        strength = 0.2, pulseLengthSeconds = 0.5, pulsePauseSeconds = 0.5
    ),
    val intifaceViolationMode: IntifaceViolationMode = IntifaceViolationMode.Off,
    val intifaceViolationVibration: IntifaceVibrationSettings = IntifaceVibrationSettings(
        strength = 0.7,
        pattern = IntifaceVibrationPattern.Pulse,
        durationSeconds = 1.0,
        pulseLengthSeconds = 0.25,
        pulsePauseSeconds = 0.25
    ),
    val intifaceViolationPauseSeconds: Double = 1.0
)

private fun GameSettings.toPoseOcclusionGuardConfig(): PoseOcclusionGuardConfig {
    return PoseOcclusionGuardConfig(
        freezeVisibilityAlways = occlusionFreezeVisibilityAlways,
        freezeVisibilityP10Always = occlusionFreezeVisibilityP10Always,
        freezeVisibilityHard = occlusionFreezeVisibilityHard,
        freezeVisibilitySoft = occlusionFreezeVisibilitySoft,
        jitterFreezeThreshold = occlusionJitterFreezeThreshold
    )
}

data class FaceOverlayPoint(val x: Float, val y: Float)

data class FaceOverlayState(
    val status: FaceDetectionStatus = FaceDetectionStatus.NotProcessed,
    val faceRect: PoseOverlayRect? = null,
    val keypoints: List<FaceOverlayPoint> = emptyList(),
    val score: Float = 0f,
    val detectorInputRect: PoseOverlayRect? = null,
    val detectorInputWidth: Int = 0,
    val detectorInputHeight: Int = 0,
    val debugMessage: String = ""
)

data class PoseOverlayState(
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val landmarks: List<Point3D> = emptyList(),
    val cropRect: PoseOverlayRect? = null,
    val face: FaceOverlayState = FaceOverlayState(),
    val frozenLandmarkIndices: Set<Int> = emptySet(),
    val identityDebugText: String = ""
)

data class PoseOverlayRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class MovementGaugeState(
    val active: Boolean = false,
    val driftNormalizedScore: Float = 0f,
    val driftThresholdFactor: Float = 0f,
    val motionNormalizedScore: Float = 0f,
    val motionThresholdFactor: Float = 0f
)

data class RuleViolationCounts(
    val drift: Int = 0,
    val motion: Int = 0,
    val face: Int = 0,
    val disappeared: Int = 0
)

data class SessionSummary(
    val result: GameState,
    val initialTimerSeconds: Int,
    val actualTimerSeconds: Int,
    val violationCounts: RuleViolationCounts,
    val settings: GameSettings,
    val defeatReason: String = "",
    val stoppedByTechnicalError: Boolean = false,
    /** Visibility belongs to this attempt, even after another challenge is selected. */
    val showInitialTimer: Boolean = true
)

/** One-shot challenge notices for the UI. */
enum class ChallengeMessage { IntifaceRequired, NoAttemptsLeft, ReportSaveFailed }

class GameViewModel internal constructor(
    application: Application,
    private val sessionTiming: SessionTiming,
    private val intifaceController: IntifaceController = createIntifaceController(application.applicationContext)
) : AndroidViewModel(application), SensorEventListener {
    constructor(application: Application) : this(application, SessionTiming())

    private val tag = "GameViewModel"
    private val defaultDurationSeconds = 180
    private val startDelaySeconds = sessionTiming.startDelaySeconds
    private val poseOcclusionCalibrationSeconds = 2
    private val stabilizationDurationMs = sessionTiming.stabilizationDurationMs
    private val gyroscopeStillThresholdRadPerSec = 0.08f

    private val sensorManager =
        application.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gyroscopeSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var stabilizationStableSinceMs: Long? = null
    private var stabilizationCompleted = false
    private var stabilizationFallbackJob: Job? = null

    private val prefs: SharedPreferences = application.getSharedPreferences("game_settings", Context.MODE_PRIVATE)
    val intifaceState: StateFlow<IntifaceUiState> = intifaceController.state

    private val _gameState = MutableStateFlow(GameState.Idle)
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()
    private val _violationCount = MutableStateFlow(0)
    val violationCount: StateFlow<Int> = _violationCount.asStateFlow()
    private val _ruleViolationCounts = MutableStateFlow(RuleViolationCounts())
    val ruleViolationCounts: StateFlow<RuleViolationCounts> = _ruleViolationCounts.asStateFlow()
    private val _selectedDurationSeconds = MutableStateFlow(
        prefs.getInt(PREF_SELECTED_DURATION_SECONDS, defaultDurationSeconds).coerceAtLeast(1)
    )
    val selectedDurationSeconds: StateFlow<Int> = _selectedDurationSeconds.asStateFlow()
    private val _timerMode = MutableStateFlow(
        runCatching {
            TimerMode.valueOf(prefs.getString(PREF_TIMER_MODE, TimerMode.Exact.name) ?: TimerMode.Exact.name)
        }.getOrDefault(TimerMode.Exact)
    )
    val timerMode: StateFlow<TimerMode> = _timerMode.asStateFlow()
    private val _timerSeconds = MutableStateFlow(
        if (_timerMode.value == TimerMode.Exact) _selectedDurationSeconds.value else 0
    )
    val timerSeconds: StateFlow<Int> = _timerSeconds.asStateFlow()
    private val _randomMinDurationSeconds = MutableStateFlow(
        prefs.getInt(PREF_RANDOM_MIN_DURATION_SECONDS, defaultDurationSeconds).coerceAtLeast(1)
    )
    val randomMinDurationSeconds: StateFlow<Int> = _randomMinDurationSeconds.asStateFlow()
    private val _randomMaxDurationSeconds = MutableStateFlow(
        prefs.getInt(PREF_RANDOM_MAX_DURATION_SECONDS, defaultDurationSeconds)
            .coerceAtLeast(_randomMinDurationSeconds.value)
    )
    val randomMaxDurationSeconds: StateFlow<Int> = _randomMaxDurationSeconds.asStateFlow()
    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    private val _defeatReason = MutableStateFlow("")
    val defeatReason: StateFlow<String> = _defeatReason.asStateFlow()
    private val _movementGaugeState = MutableStateFlow(MovementGaugeState())
    val movementGaugeState: StateFlow<MovementGaugeState> = _movementGaugeState.asStateFlow()
    private val _startDelayRemainingSeconds = MutableStateFlow(0)
    val startDelayRemainingSeconds: StateFlow<Int> = _startDelayRemainingSeconds.asStateFlow()
    private val _poseOverlayState = MutableStateFlow(PoseOverlayState())
    val poseOverlayState: StateFlow<PoseOverlayState> = _poseOverlayState.asStateFlow()
    private val _audioCueEvents = MutableSharedFlow<AudioCueEvent>(extraBufferCapacity = 8)
    val audioCueEvents: SharedFlow<AudioCueEvent> = _audioCueEvents.asSharedFlow()
    private val _gameSettings = MutableStateFlow(loadSettings())
    val gameSettings: StateFlow<GameSettings> = _gameSettings.asStateFlow()
    private val _onboardingCompleted = MutableStateFlow(
        prefs.getBoolean(PREF_ONBOARDING_COMPLETED, false)
    )
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted.asStateFlow()
    private val _challengeIntroCompleted = MutableStateFlow(prefs.getBoolean(PREF_CHALLENGE_INTRO_COMPLETED, false))
    /** Whether the challenge guide was shown; it comes before the challenges are first opened. */
    val challengeIntroCompleted: StateFlow<Boolean> = _challengeIntroCompleted.asStateFlow()
    private val _sessionSummary = MutableStateFlow<SessionSummary?>(null)
    val sessionSummary: StateFlow<SessionSummary?> = _sessionSummary.asStateFlow()

    val challengeRepository = ChallengeRepository(application.applicationContext)
    private val _activeChallenge = MutableStateFlow<ActiveChallenge?>(null)
    /** The accepted challenge whose rules the next sessions use. */
    val activeChallenge: StateFlow<ActiveChallenge?> = _activeChallenge.asStateFlow()
    private val _challengeReport = MutableStateFlow<ChallengeReport?>(null)
    /** Report of the challenge session that just ended; cleared with the final screen. */
    val challengeReport: StateFlow<ChallengeReport?> = _challengeReport.asStateFlow()
    private val _challengeMessage = MutableStateFlow<ChallengeMessage?>(null)
    val challengeMessage: StateFlow<ChallengeMessage?> = _challengeMessage.asStateFlow()
    /** Settings sessions run with: the user's own, or the active challenge over them. */
    val effectiveSettings: StateFlow<GameSettings> =
        combine(_gameSettings, _activeChallenge) { settings, challenge ->
            challenge?.let { it.spec.rules.applyTo(settings, it.audioFiles) } ?: settings
        }.stateIn(viewModelScope, SharingStarted.Eagerly, _gameSettings.value)
    // Guarded by processingLock.
    private var challengeRecorder: ChallengeSessionRecorder? = null
    @Volatile private var sessionRulesCache: Triple<GameSettings, ActiveChallenge?, GameSettings>? = null

    /** Rules of the running session, read on the result worker as well as on the main thread. */
    private val sessionRules: GameSettings
        get() {
            val base = _gameSettings.value
            val challenge = _activeChallenge.value
            val cached = sessionRulesCache
            if (cached != null && cached.first === base && cached.second === challenge) return cached.third
            return (challenge?.let { it.spec.rules.applyTo(base, it.audioFiles) } ?: base).also {
                sessionRulesCache = Triple(base, challenge, it)
            }
        }

    private var sessionInitialTimerSeconds = defaultDurationSeconds
    @Volatile private var sessionTimerMode = TimerMode.Exact
    /** Random timers and challenges with an undisclosed duration count up instead of down. */
    @Volatile private var sessionShowsElapsed = false
    private val sessionClock = SessionClock()

    private enum class PendingTerminalResult {
        Success,
        Defeat
    }

    // Guarded by sessionTargetLock.
    private var pendingTerminalResult: PendingTerminalResult? = null
    private var sessionSettingsSnapshot = _gameSettings.value

    private data class AnalyzedPoseFrame(
        val pose: PoseLandmarks,
        val timestampMs: Long,
        val imageWidth: Int,
        val imageHeight: Int,
        val face: FaceOverlayState
    )

    private data class PendingPoseFrame(
        val frame: CameraFrame,
        val processingGeneration: Long
    )

    private val faceDetectorService = FaceDetectorService(application.applicationContext, _gameSettings.value.faceDetectionConfidence)
    private val movementTracker = MovementTracker()
    private val poseIdentityStabilizer = PoseIdentityStabilizer()
    private val poseSmoother = PoseSmoother()
    private val poseOcclusionGuard = PoseOcclusionGuard()

    private val frameLock = Any()
    private val processingLock = Any()
    // When both locks are needed, always acquire processingLock before sessionTargetLock.
    private val sessionTargetLock = Any()
    private var processingGeneration = 0L
    private var lastProcessedPoseTimestampMs = Long.MIN_VALUE
    private val mediaPipeResultExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var isCleared = false
    private val pendingFrames = LinkedHashMap<Long, PendingPoseFrame>()
    private var latestAnalyzedFrame: AnalyzedPoseFrame? = null
    private var lastUsablePoseForDropoutHold: PoseLandmarks? = null
    private var lastUsablePoseTimestampMs: Long? = null
    private var consecutivePoseDropoutFrames: Int = 0
    private var rawPoseOkFrames = 0
    private var rawPoseMissingFrames = 0
    private var startDelayJob: Job? = null
    private var timerJob: Job? = null
    private var intifaceBackgroundJob: Job? = null
    private var intifaceOverrideJob: Job? = null
    private val intifaceSignalGeneration = AtomicLong(0L)
    private val intifaceSignalCommandMutex = Mutex()
    private val intifaceDeviceSelectionMutex = Mutex()

    private enum class RunMode { Background, Violation }

    // Counters are guarded by processingLock; register() additionally runs under sessionTargetLock.
    private val ruleEngine = SessionRuleEngine()
    // Guarded by processingLock; both unavailable statuses belong to the same outage.
    private var faceCheckUnavailableSinceMs: Long? = null


    @Volatile private var localizedResources: Pair<AppLanguage, android.content.res.Resources>? = null

    private fun tr(resId: Int, vararg args: Any): String {
        val language = _gameSettings.value.language
        val res = localizedResources?.takeIf { it.first == language }?.second ?: run {
            val config = android.content.res.Configuration(getApplication<Application>().resources.configuration)
            config.setLocale(language.locale)
            getApplication<Application>().createConfigurationContext(config).resources
                .also { localizedResources = language to it }
        }
        return if (args.isEmpty()) res.getString(resId) else res.getString(resId, *args)
    }

    /** Job/sensor/Intiface fields belong to the main thread; result processing runs on a worker. */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            viewModelScope.launch(Dispatchers.Main.immediate) { block() }
        }
    }

    init {
        applySettingsToEngines(_gameSettings.value)
        _statusMessage.value = tr(R.string.status_initial)
        viewModelScope.launch {
            intifaceState.collect { state ->
                synchronized(processingLock) { stopIfRequiredIntifaceUnavailable(state) }
            }
        }
    }

    private fun loadSettings(): GameSettings {
        val mode = runCatching { FaceCheckMode.valueOf(prefs.getString("face_mode", FaceCheckMode.Disabled.name) ?: FaceCheckMode.Disabled.name) }
            .getOrDefault(FaceCheckMode.Disabled)
        val language = runCatching { AppLanguage.valueOf(prefs.getString("app_language", AppLanguage.English.name) ?: AppLanguage.English.name) }.getOrDefault(AppLanguage.English)
        val ttsVoiceMode = runCatching {
            TtsVoiceMode.valueOf(
                prefs.getString(PREF_TTS_VOICE_MODE, TtsVoiceMode.DefaultVoice.name)
                    ?: TtsVoiceMode.DefaultVoice.name
            )
        }.getOrDefault(TtsVoiceMode.DefaultVoice)
        val (driftThresholdFactor, motionThresholdFactor) = loadSensitivityThresholds()
        val occlusionFreezeVisibilityAlways =
            prefs.getFloat(
                PREF_OCCLUSION_FREEZE_VIS_ALWAYS,
                DEFAULT_OCCLUSION_FREEZE_VIS_ALWAYS
            ).coerceIn(0f, 0.05f)
        val occlusionFreezeVisibilityP10Always =
            prefs.getFloat(
                PREF_OCCLUSION_FREEZE_VIS_P10_ALWAYS,
                DEFAULT_OCCLUSION_FREEZE_VIS_P10_ALWAYS
            ).coerceIn(0f, 0.05f)
        val occlusionFreezeVisibilityHard =
            prefs.getFloat(
                PREF_OCCLUSION_FREEZE_VIS_HARD,
                DEFAULT_OCCLUSION_FREEZE_VIS_HARD
            ).coerceIn(0f, 0.10f)
        val occlusionFreezeVisibilitySoft =
            prefs.getFloat(
                PREF_OCCLUSION_FREEZE_VIS_SOFT,
                DEFAULT_OCCLUSION_FREEZE_VIS_SOFT
            ).coerceIn(0f, 0.20f)
        val occlusionJitterFreezeThreshold =
            prefs.getFloat(
                PREF_OCCLUSION_JITTER_FREEZE_THRESHOLD,
                DEFAULT_OCCLUSION_JITTER_FREEZE_THRESHOLD
            ).coerceIn(0f, 0.30f)
        val wristDriftWeight = prefs.getFloat(
            PREF_WRIST_DRIFT_WEIGHT,
            DEFAULT_WRIST_DRIFT_WEIGHT
        ).coerceIn(0f, 1f)
        val backgroundMode = enumPref(PREF_INTIFACE_BACKGROUND_MODE, IntifaceBackgroundMode.Off)
        val violationMode = enumPref(PREF_INTIFACE_VIOLATION_MODE, IntifaceViolationMode.Off)
        val backgroundPattern = enumPref(PREF_INTIFACE_BACKGROUND_PATTERN, IntifaceVibrationPattern.Constant)
        val violationPattern = enumPref(PREF_INTIFACE_VIOLATION_PATTERN, IntifaceVibrationPattern.Pulse)
        return GameSettings(
            language = language,
            accelerationMode = loadAccelerationMode(),
            poseLandmarkerModel = enumPref(PREF_POSE_LANDMARKER_MODEL, PoseLandmarkerModel.Heavy),
            faceCheckMode = mode,
            faceDetectionConfidence = prefs.getFloat(PREF_FACE_DETECTION_CONFIDENCE, 0.8f)
                .takeIf(Float::isFinite)
                ?.coerceIn(0.5f, 0.95f)
                ?: 0.8f,
            driftThresholdFactor = driftThresholdFactor,
            motionThresholdFactor = motionThresholdFactor,
            minimumPenaltyIntervalSeconds = prefs.getInt("penalty_interval_sec", 5).coerceIn(0, 30),
            maxViolations = prefs.getInt("max_violations", 4).coerceIn(0, 9999),
            penaltiesEnabled = prefs.getBoolean("penalties_enabled", true),
            firstViolationPenaltyMinutes = prefs.getInt("penalty_1_min", 1).coerceIn(0, 9999),
            secondViolationPenaltyMinutes = prefs.getInt("penalty_2_min", 3).coerceIn(0, 9999),
            thirdViolationPenaltyMinutes = prefs.getInt("penalty_3_min", 3).coerceIn(0, 9999),
            subsequentViolationPenaltyMinutes = prefs.getInt("penalty_subsequent_min", 3).coerceIn(0, 9999),
            timelapseRecordingEnabled = prefs.getBoolean("timelapse_recording_enabled", true),
            occlusionFreezeVisibilityAlways = occlusionFreezeVisibilityAlways,
            occlusionFreezeVisibilityP10Always = occlusionFreezeVisibilityP10Always,
            occlusionFreezeVisibilityHard = occlusionFreezeVisibilityHard,
            occlusionFreezeVisibilitySoft = occlusionFreezeVisibilitySoft,
            occlusionJitterFreezeThreshold = occlusionJitterFreezeThreshold,
            wristDriftWeight = wristDriftWeight,
            poseSmootherMinCutoff = prefs.getFloat(
                PREF_POSE_SMOOTHER_MIN_CUTOFF,
                PoseSmoother.DEFAULT_MIN_CUTOFF
            ).coerceIn(PoseSmoother.MIN_CUTOFF_RANGE, PoseSmoother.MAX_CUTOFF_RANGE),
            poseSmootherBeta = prefs.getFloat(
                PREF_POSE_SMOOTHER_BETA,
                PoseSmoother.DEFAULT_BETA
            ).coerceIn(PoseSmoother.MIN_BETA_RANGE, PoseSmoother.MAX_BETA_RANGE),
            poseSmootherDerivativeCutoff = prefs.getFloat(
                PREF_POSE_SMOOTHER_DERIVATIVE_CUTOFF,
                PoseSmoother.DEFAULT_DERIVATIVE_CUTOFF
            ).coerceIn(PoseSmoother.MIN_CUTOFF_RANGE, PoseSmoother.MAX_CUTOFF_RANGE),
            debugModeEnabled = prefs.getBoolean(PREF_DEBUG_MODE_ENABLED, false),
            customizeAudioEnabled = prefs.getBoolean(PREF_CUSTOMIZE_AUDIO_ENABLED, false),
            ttsVoiceMode = ttsVoiceMode,
            customTtsTemplates = loadCustomTtsTemplates(),
            audioCueSettings = loadAudioCueSettings(),
            intifaceWebSocketUrl = loadIntifaceWebSocketUrl(),
            intifaceConnectionEnabled = intifaceController.state.value.isSupported &&
                prefs.getBoolean(PREF_INTIFACE_CONNECTION_ENABLED, false),
            intifaceSelectedDeviceName = prefs.getString(PREF_INTIFACE_SELECTED_DEVICE_NAME, "")?.trim().orEmpty(),
            intifaceSelectedDeviceDisplayName = prefs.getString(PREF_INTIFACE_SELECTED_DEVICE_DISPLAY_NAME, "")?.trim().orEmpty(),
            intifaceSelectedDeviceIndex = if (prefs.contains(PREF_INTIFACE_SELECTED_DEVICE_INDEX)) prefs.getLong(PREF_INTIFACE_SELECTED_DEVICE_INDEX, -1L).takeIf { it >= 0L } else null,
            intifaceBackgroundMode = backgroundMode,
            intifaceBackgroundVibration = IntifaceVibrationSettings(
                strength = getDoublePref(PREF_INTIFACE_BACKGROUND_STRENGTH, 0.2).coerceIn(0.0, 1.0),
                pattern = backgroundPattern,
                pulseLengthSeconds = getDoublePref(PREF_INTIFACE_BACKGROUND_PULSE_LENGTH, 0.5).coerceIn(0.05, 10.0),
                pulsePauseSeconds = getDoublePref(PREF_INTIFACE_BACKGROUND_PULSE_PAUSE, 0.5).coerceIn(0.05, 10.0)
            ),
            intifaceViolationMode = violationMode,
            intifaceViolationVibration = IntifaceVibrationSettings(
                strength = getDoublePref(PREF_INTIFACE_VIOLATION_STRENGTH, 0.7).coerceIn(0.0, 1.0),
                pattern = violationPattern,
                durationSeconds = getDoublePref(PREF_INTIFACE_VIOLATION_DURATION, 1.0).coerceIn(0.05, 60.0),
                pulseLengthSeconds = getDoublePref(PREF_INTIFACE_VIOLATION_PULSE_LENGTH, 0.25).coerceIn(0.05, 10.0),
                pulsePauseSeconds = getDoublePref(PREF_INTIFACE_VIOLATION_PULSE_PAUSE, 0.25).coerceIn(0.05, 10.0)
            ),
            intifaceViolationPauseSeconds =
                getDoublePref(PREF_INTIFACE_VIOLATION_PAUSE_SECONDS, 1.0).coerceIn(0.05, 60.0)
        )
    }

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        runCatching { enumValueOf<T>(prefs.getString(key, default.name) ?: default.name) }.getOrDefault(default)

    private fun loadAccelerationMode(): AccelerationMode {
        val savedMode = enumPref(PREF_ACCELERATION_MODE, AccelerationMode.Auto)
        val wasAutomaticallyResolved = prefs.getBoolean(PREF_ACCELERATION_MODE_AUTO_RESOLVED, false)
        val savedVersionCode = prefs.getInt(PREF_ACCELERATION_MODE_VERSION_CODE, -1)
        val mode = accelerationModeAfterAppUpdate(
            savedMode = savedMode,
            wasAutomaticallyResolved = wasAutomaticallyResolved,
            savedVersionCode = savedVersionCode,
            currentVersionCode = BuildConfig.VERSION_CODE
        )
        if (mode == AccelerationMode.Auto && savedMode != AccelerationMode.Auto) {
            prefs.edit()
                .putString(PREF_ACCELERATION_MODE, AccelerationMode.Auto.name)
                .putBoolean(PREF_ACCELERATION_MODE_AUTO_RESOLVED, false)
                .remove(PREF_ACCELERATION_MODE_VERSION_CODE)
                .apply()
        }
        return mode
    }

    private fun getDoublePref(key: String, defaultValue: Double): Double =
        if (prefs.contains(key)) java.lang.Double.longBitsToDouble(prefs.getLong(key, 0L)) else defaultValue

    private fun putDoublePref(key: String, value: Double) {
        prefs.edit().putLong(key, java.lang.Double.doubleToRawLongBits(value)).apply()
    }

    private fun loadIntifaceWebSocketUrl(): String {
        val saved = prefs.getString(PREF_INTIFACE_WEBSOCKET_URL, null)?.trim()
        return when {
            saved == null -> DEFAULT_INTIFACE_WEBSOCKET_URL
            saved == LEGACY_INTIFACE_WEBSOCKET_URL -> {
                prefs.edit().putString(PREF_INTIFACE_WEBSOCKET_URL, DEFAULT_INTIFACE_WEBSOCKET_URL).apply()
                DEFAULT_INTIFACE_WEBSOCKET_URL
            }
            else -> saved
        }
    }

    private fun loadCustomTtsTemplates(): Map<TtsPhraseTemplate, String> =
        TtsPhraseTemplate.entries.mapNotNull { template ->
            val currentValue = prefs
                .getString(PREF_TTS_TEMPLATE_PREFIX + template.name, null)
                ?.takeIf { it.isNotBlank() }
            val legacyValue = template.audioCueForLegacyMigration()?.let { cue ->
                prefs.getString(LEGACY_PREF_AUDIO_CUE_TTS_TEXT_PREFIX + cue.name, null)
                    ?.takeIf { it.isNotBlank() }
            }

            (currentValue ?: legacyValue)?.let { template to it }
        }.toMap()

    private fun TtsPhraseTemplate.audioCueForLegacyMigration(): AudioCue? = when (this) {
        TtsPhraseTemplate.PlaceDeviceStill -> AudioCue.PlaceDeviceStill
        TtsPhraseTemplate.TakePosition -> AudioCue.TakePosition
        TtsPhraseTemplate.TimeStartedHoldPosition -> AudioCue.TimeStartedHoldPosition
        TtsPhraseTemplate.TimeIsUp -> AudioCue.TimeIsUp
        TtsPhraseTemplate.DefeatTryAgain -> AudioCue.DefeatTryAgain
        TtsPhraseTemplate.MotionViolation -> AudioCue.MotionViolation
        TtsPhraseTemplate.DriftViolation -> AudioCue.DriftViolation
        TtsPhraseTemplate.ViolationRecorded -> AudioCue.ViolationRecorded
        TtsPhraseTemplate.FaceTurnedAway -> AudioCue.FaceTurnedAway
        TtsPhraseTemplate.FaceLookedAtCamera -> AudioCue.FaceLookedAtCamera
        TtsPhraseTemplate.PenaltyAddedToTimer -> null
    }

    private fun loadAudioCueSettings(): Map<AudioCue, AudioCueSettings> =
        AudioCue.entries.associateWith { cue ->
            val mode = runCatching {
                AudioCueMode.valueOf(
                    prefs.getString(PREF_AUDIO_CUE_MODE_PREFIX + cue.name, AudioCueMode.UseTts.name)
                        ?: AudioCueMode.UseTts.name
                )
            }.getOrDefault(AudioCueMode.UseTts)
            val defaultPcmSettings = PcmSignalSettings()
            val pcmChannel = runCatching {
                PcmChannel.valueOf(
                    prefs.getString(
                        PREF_AUDIO_CUE_PCM_CHANNEL_PREFIX + cue.name,
                        defaultPcmSettings.channel.name
                    ) ?: defaultPcmSettings.channel.name
                )
            }.getOrDefault(defaultPcmSettings.channel)
            val pcmPattern = runCatching {
                PcmPattern.valueOf(
                    prefs.getString(
                        PREF_AUDIO_CUE_PCM_PATTERN_PREFIX + cue.name,
                        defaultPcmSettings.pattern.name
                    ) ?: defaultPcmSettings.pattern.name
                )
            }.getOrDefault(defaultPcmSettings.pattern)
            AudioCueSettings(
                mode = mode,
                audioFileUri = prefs.getString(PREF_AUDIO_CUE_URI_PREFIX + cue.name, null),
                pcmSettings = PcmSignalSettings(
                    frequencyHz = prefs.getInt(
                        PREF_AUDIO_CUE_PCM_FREQUENCY_PREFIX + cue.name,
                        defaultPcmSettings.frequencyHz
                    ).coerceIn(20, 20_000),
                    durationSeconds = prefs.getFloat(
                        PREF_AUDIO_CUE_PCM_DURATION_PREFIX + cue.name,
                        defaultPcmSettings.durationSeconds
                    ).takeIf(Float::isFinite)
                        ?.coerceIn(0.05f, 10.0f)
                        ?: defaultPcmSettings.durationSeconds,
                    channel = pcmChannel,
                    amplitudePercent = prefs.getInt(
                        PREF_AUDIO_CUE_PCM_AMPLITUDE_PREFIX + cue.name,
                        defaultPcmSettings.amplitudePercent
                    ).coerceIn(0, 100),
                    fadeInMs = prefs.getInt(
                        PREF_AUDIO_CUE_PCM_FADE_IN_PREFIX + cue.name,
                        defaultPcmSettings.fadeInMs
                    ).coerceIn(0, 5_000),
                    fadeOutMs = prefs.getInt(
                        PREF_AUDIO_CUE_PCM_FADE_OUT_PREFIX + cue.name,
                        defaultPcmSettings.fadeOutMs
                    ).coerceIn(0, 5_000),
                    pattern = pcmPattern
                )
            )
        }

    private fun loadSensitivityThresholds(): Pair<Float, Float> {
        val rawDrift = prefs.getFloat("pose_drift_factor_v2", 0.160f).coerceIn(0.05f, 0.40f)
        val rawMotion = prefs.getFloat("pose_motion_factor_v2", 0.04f).coerceIn(0.02f, 0.25f)
        val presetsVersion = prefs.getInt(PREF_SENSITIVITY_PRESETS_VERSION, 1)

        if (presetsVersion < CURRENT_SENSITIVITY_PRESETS_VERSION) {
            val (migratedDrift, migratedMotion) = migrateLegacySensitivityThresholds(rawDrift, rawMotion)
            prefs.edit()
                .putFloat("pose_drift_factor_v2", migratedDrift)
                .putFloat("pose_motion_factor_v2", migratedMotion)
                .putInt(PREF_SENSITIVITY_PRESETS_VERSION, CURRENT_SENSITIVITY_PRESETS_VERSION)
                .apply()
            return migratedDrift to migratedMotion
        }

        return normalizeDriftThreshold(rawDrift) to normalizeMotionThreshold(rawMotion)
    }

    private fun migrateLegacySensitivityThresholds(drift: Float, motion: Float): Pair<Float, Float> {
        return when {
            abs(drift - 0.12f) < 0.001f && abs(motion - 0.06f) < 0.001f -> 0.160f to 0.04f
            else -> normalizeDriftThreshold(drift) to normalizeMotionThreshold(motion)
        }
    }


    private fun normalizeDriftThreshold(drift: Float): Float {
        val presets = listOf(0.200f, 0.180f, 0.160f, 0.140f, 0.120f)
        return presets.firstOrNull { preset -> abs(preset - drift) < 0.001f } ?: 0.160f
    }

    private fun normalizeMotionThreshold(motion: Float): Float {
        val presets = listOf(0.08f, 0.06f, 0.04f, 0.03f, 0.02f)
        return presets.firstOrNull { preset -> abs(preset - motion) < 0.001f } ?: 0.04f
    }

    private fun applySettingsToEngines(settings: GameSettings) {
        synchronized(processingLock) {
            movementTracker.driftThresholdFactor = settings.driftThresholdFactor
            movementTracker.motionThresholdFactor = settings.motionThresholdFactor
            movementTracker.wristDriftWeight = settings.wristDriftWeight
            poseSmoother.updateConfig(
                minCutoff = settings.poseSmootherMinCutoff,
                beta = settings.poseSmootherBeta,
                derivativeCutoff = settings.poseSmootherDerivativeCutoff
            )
            poseOcclusionGuard.updateConfig(settings.toPoseOcclusionGuardConfig())
        }
        faceDetectorService.setMinDetectionConfidence(settings.faceDetectionConfidence)
    }

    fun updateFaceCheckMode(mode: FaceCheckMode) {
        _gameSettings.value = _gameSettings.value.copy(faceCheckMode = mode)
        prefs.edit().putString("face_mode", mode.name).apply()
        synchronized(processingLock) {
            ruleEngine.resetFaceFailFrames()
            faceCheckUnavailableSinceMs = null
        }
    }

    fun updateFaceDetectionConfidence(value: Float) {
        val normalized = value.coerceIn(0.5f, 0.95f)
        _gameSettings.value = _gameSettings.value.copy(faceDetectionConfidence = normalized)
        prefs.edit().putFloat(PREF_FACE_DETECTION_CONFIDENCE, normalized).apply()
        faceDetectorService.setMinDetectionConfidence(normalized)
        synchronized(processingLock) {
            ruleEngine.resetFaceFailFrames()
            faceCheckUnavailableSinceMs = null
        }
    }

    fun updateDriftThresholdFactor(value: Float) {
        val normalized = value.coerceIn(0.05f, 0.40f)
        _gameSettings.value = _gameSettings.value.copy(driftThresholdFactor = normalized)
        prefs.edit()
            .putFloat("pose_drift_factor_v2", normalized)
            .putInt(PREF_SENSITIVITY_PRESETS_VERSION, CURRENT_SENSITIVITY_PRESETS_VERSION)
            .apply()
        synchronized(processingLock) { movementTracker.driftThresholdFactor = normalized }
    }

    fun updateMotionThresholdFactor(value: Float) {
        val normalized = value.coerceIn(0.02f, 0.25f)
        _gameSettings.value = _gameSettings.value.copy(motionThresholdFactor = normalized)
        prefs.edit()
            .putFloat("pose_motion_factor_v2", normalized)
            .putInt(PREF_SENSITIVITY_PRESETS_VERSION, CURRENT_SENSITIVITY_PRESETS_VERSION)
            .apply()
        synchronized(processingLock) { movementTracker.motionThresholdFactor = normalized }
    }

    fun updateWristDriftWeight(value: Float) {
        val normalized = value.coerceIn(0f, 1f)
        _gameSettings.value = _gameSettings.value.copy(wristDriftWeight = normalized)
        prefs.edit().putFloat(PREF_WRIST_DRIFT_WEIGHT, normalized).apply()
        synchronized(processingLock) {
            movementTracker.wristDriftWeight = normalized
        }
    }

    fun updatePoseSmootherMinCutoff(value: Float) {
        updatePoseSmootherConfig(
            prefKey = PREF_POSE_SMOOTHER_MIN_CUTOFF,
            normalized = value.coerceIn(PoseSmoother.MIN_CUTOFF_RANGE, PoseSmoother.MAX_CUTOFF_RANGE)
        ) { copy(poseSmootherMinCutoff = it) }
    }

    fun updatePoseSmootherBeta(value: Float) {
        updatePoseSmootherConfig(
            prefKey = PREF_POSE_SMOOTHER_BETA,
            normalized = value.coerceIn(PoseSmoother.MIN_BETA_RANGE, PoseSmoother.MAX_BETA_RANGE)
        ) { copy(poseSmootherBeta = it) }
    }

    fun updatePoseSmootherDerivativeCutoff(value: Float) {
        updatePoseSmootherConfig(
            prefKey = PREF_POSE_SMOOTHER_DERIVATIVE_CUTOFF,
            normalized = value.coerceIn(PoseSmoother.MIN_CUTOFF_RANGE, PoseSmoother.MAX_CUTOFF_RANGE)
        ) { copy(poseSmootherDerivativeCutoff = it) }
    }

    private fun updatePoseSmootherConfig(
        prefKey: String,
        normalized: Float,
        apply: GameSettings.(Float) -> GameSettings
    ) {
        val updated = _gameSettings.value.apply(normalized)
        _gameSettings.value = updated
        prefs.edit().putFloat(prefKey, normalized).apply()
        synchronized(processingLock) {
            poseSmoother.updateConfig(
                minCutoff = updated.poseSmootherMinCutoff,
                beta = updated.poseSmootherBeta,
                derivativeCutoff = updated.poseSmootherDerivativeCutoff
            )
        }
    }

    fun updateOcclusionFreezeVisibilityAlways(value: Float) {
        updatePoseOcclusionConfig(
            prefKey = PREF_OCCLUSION_FREEZE_VIS_ALWAYS,
            normalized = value.coerceIn(0f, 0.05f)
        ) { copy(occlusionFreezeVisibilityAlways = it) }
    }

    fun updateOcclusionFreezeVisibilityP10Always(value: Float) {
        updatePoseOcclusionConfig(
            prefKey = PREF_OCCLUSION_FREEZE_VIS_P10_ALWAYS,
            normalized = value.coerceIn(0f, 0.05f)
        ) { copy(occlusionFreezeVisibilityP10Always = it) }
    }

    fun updateOcclusionFreezeVisibilityHard(value: Float) {
        updatePoseOcclusionConfig(
            prefKey = PREF_OCCLUSION_FREEZE_VIS_HARD,
            normalized = value.coerceIn(0f, 0.10f)
        ) { copy(occlusionFreezeVisibilityHard = it) }
    }

    fun updateOcclusionFreezeVisibilitySoft(value: Float) {
        updatePoseOcclusionConfig(
            prefKey = PREF_OCCLUSION_FREEZE_VIS_SOFT,
            normalized = value.coerceIn(0f, 0.20f)
        ) { copy(occlusionFreezeVisibilitySoft = it) }
    }

    fun updateOcclusionJitterFreezeThreshold(value: Float) {
        updatePoseOcclusionConfig(
            prefKey = PREF_OCCLUSION_JITTER_FREEZE_THRESHOLD,
            normalized = value.coerceIn(0f, 0.30f)
        ) { copy(occlusionJitterFreezeThreshold = it) }
    }

    private fun updatePoseOcclusionConfig(
        prefKey: String,
        normalized: Float,
        apply: GameSettings.(Float) -> GameSettings
    ) {
        val updated = _gameSettings.value.apply(normalized)
        _gameSettings.value = updated
        prefs.edit().putFloat(prefKey, normalized).apply()
        synchronized(processingLock) {
            poseOcclusionGuard.updateConfig(updated.toPoseOcclusionGuardConfig())
        }
    }


    fun updateMinimumPenaltyIntervalSeconds(value: Int) {
        val normalized = value.coerceIn(0, 30)
        _gameSettings.value = _gameSettings.value.copy(minimumPenaltyIntervalSeconds = normalized)
        prefs.edit().putInt("penalty_interval_sec", normalized).apply()
    }

    fun updateMaxViolations(value: Int) {
        val normalized = value.coerceIn(0, 9999)
        _gameSettings.value = _gameSettings.value.copy(maxViolations = normalized)
        prefs.edit().putInt("max_violations", normalized).apply()
    }


    fun updateLanguage(language: AppLanguage) {
        _gameSettings.value = _gameSettings.value.copy(language = language)
        prefs.edit().putString("app_language", language.name).apply()
        if (_gameState.value == GameState.Idle) {
            _statusMessage.value = tr(R.string.status_initial)
        }
    }

    fun updateAccelerationMode(mode: AccelerationMode) {
        _gameSettings.value = _gameSettings.value.copy(accelerationMode = mode)
        prefs.edit()
            .putString(PREF_ACCELERATION_MODE, mode.name)
            .putBoolean(PREF_ACCELERATION_MODE_AUTO_RESOLVED, false)
            .remove(PREF_ACCELERATION_MODE_VERSION_CODE)
            .apply()
    }

    fun updatePoseLandmarkerModel(model: PoseLandmarkerModel) {
        _gameSettings.value = _gameSettings.value.copy(poseLandmarkerModel = model)
        prefs.edit().putString(PREF_POSE_LANDMARKER_MODEL, model.name).apply()
    }

    fun resolveAccelerationMode(mode: AccelerationMode) {
        // A delayed status callback must not turn an explicit CPU choice into an
        // automatically resolved preference that gets reset on the next update.
        if (_gameSettings.value.accelerationMode == mode) return
        if (mode == AccelerationMode.Gpu &&
            _gameSettings.value.accelerationMode != AccelerationMode.Auto
        ) {
            return
        }
        // A manual GPU choice stays manual; the delegate already falls back to CPU at runtime.
        if (_gameSettings.value.accelerationMode == AccelerationMode.Gpu &&
            !prefs.getBoolean(PREF_ACCELERATION_MODE_AUTO_RESOLVED, false)
        ) {
            return
        }
        _gameSettings.value = _gameSettings.value.copy(accelerationMode = mode)
        prefs.edit()
            .putString(PREF_ACCELERATION_MODE, mode.name)
            .putBoolean(PREF_ACCELERATION_MODE_AUTO_RESOLVED, true)
            .putInt(PREF_ACCELERATION_MODE_VERSION_CODE, BuildConfig.VERSION_CODE)
            .apply()
    }

    fun markOnboardingCompleted() {
        _onboardingCompleted.value = true
        prefs.edit().putBoolean(PREF_ONBOARDING_COMPLETED, true).apply()
    }

    fun markChallengeIntroCompleted() {
        _challengeIntroCompleted.value = true
        prefs.edit().putBoolean(PREF_CHALLENGE_INTRO_COMPLETED, true).apply()
    }

    fun updatePenaltiesEnabled(enabled: Boolean) {
        _gameSettings.value = _gameSettings.value.copy(penaltiesEnabled = enabled)
        prefs.edit().putBoolean("penalties_enabled", enabled).apply()
    }

    fun updateTtsVoiceMode(mode: TtsVoiceMode) {
        _gameSettings.value = _gameSettings.value.copy(ttsVoiceMode = mode)
        prefs.edit().putString(PREF_TTS_VOICE_MODE, mode.name).apply()
    }

    fun updateTtsPhraseTemplate(template: TtsPhraseTemplate, customText: String?) {
        val normalized = customText
            ?.takeIf { it.isNotBlank() }
            ?.takeIf {
                template != TtsPhraseTemplate.PenaltyAddedToTimer || it.contains("{minutes}")
            }
        _gameSettings.value = _gameSettings.value.copy(
            customTtsTemplates = if (normalized == null) {
                _gameSettings.value.customTtsTemplates - template
            } else {
                _gameSettings.value.customTtsTemplates + (template to normalized)
            }
        )

        prefs.edit().apply {
            if (normalized == null) {
                remove(PREF_TTS_TEMPLATE_PREFIX + template.name)
            } else {
                putString(PREF_TTS_TEMPLATE_PREFIX + template.name, normalized)
            }
            template.audioCueForLegacyMigration()?.let { cue ->
                remove(LEGACY_PREF_AUDIO_CUE_TTS_TEXT_PREFIX + cue.name)
            }
        }.apply()
    }

    fun updateCustomizeAudioEnabled(enabled: Boolean) {
        _gameSettings.value = _gameSettings.value.copy(customizeAudioEnabled = enabled)
        prefs.edit().putBoolean(PREF_CUSTOMIZE_AUDIO_ENABLED, enabled).apply()
    }

    fun updateIntifaceWebSocketUrl(value: String) {
        val normalized = value.trim()
        val oldValue = _gameSettings.value.intifaceWebSocketUrl

        _gameSettings.value = _gameSettings.value.copy(intifaceWebSocketUrl = normalized)
        prefs.edit().putString(PREF_INTIFACE_WEBSOCKET_URL, normalized).apply()

        if (normalized != oldValue) {
            intifaceController.clearTransientMessages()
            intifaceController.disconnect()
        }
    }

    fun updateIntifaceConnectionEnabled(enabled: Boolean) {
        val effective = enabled && intifaceController.state.value.isSupported
        _gameSettings.value = _gameSettings.value.copy(intifaceConnectionEnabled = effective)
        prefs.edit().putBoolean(PREF_INTIFACE_CONNECTION_ENABLED, effective).apply()

        if (!effective) {
            disconnectIntiface()
        }
    }

    fun updateIntifaceBackgroundMode(mode: IntifaceBackgroundMode) {
        _gameSettings.value = _gameSettings.value.copy(intifaceBackgroundMode = mode)
        prefs.edit().putString(PREF_INTIFACE_BACKGROUND_MODE, mode.name).apply()
        restartIntifaceBackgroundIfNeeded()
    }

    fun updateIntifaceBackgroundVibration(settings: IntifaceVibrationSettings) {
        val normalized = settings.normalized()
        _gameSettings.value = _gameSettings.value.copy(intifaceBackgroundVibration = normalized)
        putDoublePref(PREF_INTIFACE_BACKGROUND_STRENGTH, normalized.strength)
        prefs.edit().putString(PREF_INTIFACE_BACKGROUND_PATTERN, normalized.pattern.name).apply()
        putDoublePref(PREF_INTIFACE_BACKGROUND_PULSE_LENGTH, normalized.pulseLengthSeconds)
        putDoublePref(PREF_INTIFACE_BACKGROUND_PULSE_PAUSE, normalized.pulsePauseSeconds)
        restartIntifaceBackgroundIfNeeded()
    }

    fun updateIntifaceViolationMode(mode: IntifaceViolationMode) {
        _gameSettings.value = _gameSettings.value.copy(intifaceViolationMode = mode)
        prefs.edit().putString(PREF_INTIFACE_VIOLATION_MODE, mode.name).apply()
    }

    fun updateIntifaceViolationVibration(settings: IntifaceVibrationSettings) {
        val normalized = settings.normalized()
        _gameSettings.value = _gameSettings.value.copy(intifaceViolationVibration = normalized)
        putDoublePref(PREF_INTIFACE_VIOLATION_STRENGTH, normalized.strength)
        prefs.edit().putString(PREF_INTIFACE_VIOLATION_PATTERN, normalized.pattern.name).apply()
        putDoublePref(PREF_INTIFACE_VIOLATION_DURATION, normalized.durationSeconds)
        putDoublePref(PREF_INTIFACE_VIOLATION_PULSE_LENGTH, normalized.pulseLengthSeconds)
        putDoublePref(PREF_INTIFACE_VIOLATION_PULSE_PAUSE, normalized.pulsePauseSeconds)
    }

    fun updateIntifaceViolationPauseSeconds(value: Double) {
        val normalized = value.coerceIn(0.05, 60.0)
        _gameSettings.value = _gameSettings.value.copy(intifaceViolationPauseSeconds = normalized)
        putDoublePref(PREF_INTIFACE_VIOLATION_PAUSE_SECONDS, normalized)
    }

    fun searchIntifaceDevices(url: String) {
        if (!_gameSettings.value.intifaceConnectionEnabled) return
        if (!hasIntifaceLocalNetworkPermission()) {
            handleMissingIntifaceLocalNetworkPermission()
            return
        }
        if (intifaceController.state.value.isBusy) return

        val normalized = url.trim()
        updateIntifaceWebSocketUrl(normalized)

        viewModelScope.launch {
            intifaceController.searchDevices(normalized)
        }
    }

    fun selectIntifaceDevice(device: IntifaceDeviceInfo) {
        viewModelScope.launch {
            intifaceDeviceSelectionMutex.withLock {
                intifaceController.selectDevice(device)
                val selected = intifaceController.state.value.selectedDevice ?: return@withLock
                if (selected.index != device.index) return@withLock
                prefs.edit()
                    .putString(PREF_INTIFACE_SELECTED_DEVICE_NAME, selected.name)
                    .putString(PREF_INTIFACE_SELECTED_DEVICE_DISPLAY_NAME, selected.displayName)
                    .putLong(PREF_INTIFACE_SELECTED_DEVICE_INDEX, selected.index)
                    .apply()
                _gameSettings.value = _gameSettings.value.copy(
                    intifaceSelectedDeviceName = selected.name,
                    intifaceSelectedDeviceDisplayName = selected.displayName,
                    intifaceSelectedDeviceIndex = selected.index
                )
                restartIntifaceBackgroundIfNeeded()
            }
        }
    }

    fun autoConnectIntifaceIfRemembered() {
        val settings = _gameSettings.value
        if (!settings.intifaceConnectionEnabled) return
        if (!hasIntifaceLocalNetworkPermission()) {
            handleMissingIntifaceLocalNetworkPermission()
            return
        }
        if (settings.intifaceSelectedDeviceName.isBlank() && settings.intifaceSelectedDeviceDisplayName.isBlank()) return
        if (!intifaceController.state.value.isSupported) return
        if (intifaceController.state.value.isBusy) return

        val rememberedDevice = com.incident201.poseguard.intiface.IntifaceRememberedDevice(
            name = settings.intifaceSelectedDeviceName,
            displayName = settings.intifaceSelectedDeviceDisplayName,
            index = settings.intifaceSelectedDeviceIndex
        )

        viewModelScope.launch {
            intifaceController.connectToRememberedDevice(settings.intifaceWebSocketUrl, rememberedDevice)
            restartIntifaceBackgroundIfNeeded()
        }
    }


    fun testIntifaceVibration() {
        if (!_gameSettings.value.intifaceConnectionEnabled) return
        if (!hasIntifaceLocalNetworkPermission()) {
            handleMissingIntifaceLocalNetworkPermission()
            return
        }
        viewModelScope.launch { intifaceController.testVibration() }
    }

    fun disconnectIntiface() {
        stopIntifaceSessionSignals(disconnect = true)
    }

    fun pauseIntifaceSessionSignals() {
        stopIntifaceSessionSignals(disconnect = false)
    }

    private fun IntifaceVibrationSettings.normalized() = copy(
        strength = strength.coerceIn(0.0, 1.0),
        durationSeconds = durationSeconds.coerceIn(0.05, 60.0),
        pulseLengthSeconds = pulseLengthSeconds.coerceIn(0.05, 10.0),
        pulsePauseSeconds = pulsePauseSeconds.coerceIn(0.05, 10.0)
    )

    private fun hasIntifaceLocalNetworkPermission(): Boolean =
        hasRequiredLocalNetworkPermission(getApplication<Application>().applicationContext)

    private fun handleMissingIntifaceLocalNetworkPermission() {
        nextIntifaceSignalGeneration()
        intifaceBackgroundJob?.cancel()
        intifaceBackgroundJob = null
        intifaceOverrideJob?.cancel()
        intifaceOverrideJob = null
        intifaceController.disconnect()
    }

    private fun isIntifaceRuntimeReady(
        settings: GameSettings = _gameSettings.value,
        state: IntifaceUiState = intifaceController.state.value
    ): Boolean {
        if (!hasIntifaceLocalNetworkPermission()) {
            runOnMain { handleMissingIntifaceLocalNetworkPermission() }
            return false
        }
        return state.isSupported && settings.intifaceConnectionEnabled &&
            state.isConnected && state.selectedDevice != null
    }

    /** Called under processingLock, including before either session phase or success. */
    private fun stopIfRequiredIntifaceUnavailable(state: IntifaceUiState = intifaceController.state.value): Boolean {
        if (_gameState.value !in setOf(GameState.WaitingForStabilization, GameState.StartingDelay, GameState.HoldingPose) ||
            _activeChallenge.value?.spec?.rules?.intifaceRequired != true || isIntifaceRuntimeReady(sessionRules, state)
        ) return false
        val reserved = synchronized(sessionTargetLock) {
            // A disconnect observed while completing success takes precedence.
            if (pendingTerminalResult == PendingTerminalResult.Success) pendingTerminalResult = null
            tryReserveSessionDefeat(includeStabilization = true)
        }
        if (!reserved) return false
        completeDefeatAfterReservation(
            reason = tr(R.string.challenge_intiface_unavailable),
            stoppedByTechnicalError = true,
            cause = SessionEndCause.IntifaceUnavailable
        )
        return true
    }

    private fun startIntifaceSessionSignals() {
        nextIntifaceSignalGeneration()
        intifaceBackgroundJob?.cancel()
        intifaceBackgroundJob = null
        intifaceOverrideJob?.cancel()
        intifaceOverrideJob = null
        restartIntifaceBackgroundIfNeeded()
    }

    private fun stopIntifaceSessionSignals(disconnect: Boolean = false) {
        val generation = nextIntifaceSignalGeneration()
        intifaceBackgroundJob?.cancel()
        intifaceBackgroundJob = null
        intifaceOverrideJob?.cancel()
        intifaceOverrideJob = null
        if (disconnect) {
            intifaceController.disconnect()
            return
        }
        if (!shouldRunIntifaceBestEffortStop()) {
            return
        }
        viewModelScope.launch {
            stopIntifaceIfSignalCurrent(generation)
        }
    }

    private fun shouldRunIntifaceBestEffortStop(): Boolean {
        if (!hasIntifaceLocalNetworkPermission()) {
            handleMissingIntifaceLocalNetworkPermission()
            return false
        }
        val state = intifaceController.state.value
        val settings = _gameSettings.value
        return state.isSupported &&
            settings.intifaceConnectionEnabled &&
            state.isConnected &&
            state.selectedDevice != null
    }

    private fun nextIntifaceSignalGeneration(): Long =
        intifaceSignalGeneration.incrementAndGet()

    private suspend fun stopIntifaceIfSignalCurrent(generation: Long) {
        intifaceSignalCommandMutex.withLock {
            if (intifaceSignalGeneration.get() == generation &&
                shouldRunIntifaceBestEffortStop()
            ) {
                runCatching { intifaceController.stopVibration() }
            }
        }
    }

    private suspend fun setIntifaceStrengthIfSignalCurrent(
        generation: Long,
        strength: Double
    ): Boolean = intifaceSignalCommandMutex.withLock {
        if (intifaceSignalGeneration.get() != generation || !isIntifaceRuntimeReady()) {
            return@withLock false
        }
        intifaceController.setVibrationStrength(strength)
        true
    }

    private fun restartIntifaceBackgroundIfNeeded() {
        intifaceBackgroundJob?.cancel()
        intifaceBackgroundJob = null
        if (intifaceOverrideJob?.isActive == true) return
        val generation = nextIntifaceSignalGeneration()
        val settings = sessionRules
        if (_gameState.value != GameState.HoldingPose ||
            settings.intifaceBackgroundMode != IntifaceBackgroundMode.Vibration ||
            !isIntifaceRuntimeReady(settings)
        ) {
            if (shouldRunIntifaceBestEffortStop()) {
                viewModelScope.launch {
                    stopIntifaceIfSignalCurrent(generation)
                }
            }
            return
        }
        intifaceBackgroundJob = viewModelScope.launch {
            try {
                runIntifaceVibrationPattern(
                    settings.intifaceBackgroundVibration,
                    RunMode.Background,
                    generation
                )
            } finally {
                runCatching { stopIntifaceIfSignalCurrent(generation) }
            }
        }
    }

    private fun triggerIntifaceViolationEffect(
        resumeBackgroundAfter: Boolean = true,
        requireHoldingPose: Boolean = true
    ) {
        val settings = sessionRules
        if (settings.intifaceViolationMode == IntifaceViolationMode.Off ||
            !isIntifaceRuntimeReady(settings)
        ) {
            return
        }
        if (requireHoldingPose) {
            synchronized(sessionTargetLock) {
                if (_gameState.value != GameState.HoldingPose ||
                    pendingTerminalResult != null
                ) {
                    return
                }
                startIntifaceOverrideJob(settings, resumeBackgroundAfter)
            }
            return
        }
        startIntifaceOverrideJob(settings, resumeBackgroundAfter)
    }

    private fun startIntifaceOverrideJob(
        settings: GameSettings,
        resumeBackgroundAfter: Boolean
    ) {
        val oldJob = intifaceOverrideJob
        val generation = nextIntifaceSignalGeneration()
        intifaceBackgroundJob?.cancel()
        intifaceBackgroundJob = null
        val newJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                when (settings.intifaceViolationMode) {
                    IntifaceViolationMode.Off -> Unit
                    IntifaceViolationMode.Pause -> {
                        stopIntifaceIfSignalCurrent(generation)
                        delay((settings.intifaceViolationPauseSeconds * 1000).toLong())
                    }
                    IntifaceViolationMode.Vibration ->
                        runIntifaceVibrationPattern(
                            settings.intifaceViolationVibration,
                            RunMode.Violation,
                            generation
                        )
                }
            } finally {
                val currentJob = coroutineContext[Job]
                if (intifaceOverrideJob === currentJob &&
                    intifaceSignalGeneration.get() == generation
                ) {
                    runCatching { stopIntifaceIfSignalCurrent(generation) }
                    if (intifaceOverrideJob === currentJob &&
                        intifaceSignalGeneration.get() == generation
                    ) {
                        intifaceOverrideJob = null
                        if (resumeBackgroundAfter) {
                            restartIntifaceBackgroundIfNeeded()
                        }
                    }
                }
            }
        }
        intifaceOverrideJob = newJob
        oldJob?.cancel()
        newJob.start()
    }

    private suspend fun runIntifaceVibrationPattern(
        settings: IntifaceVibrationSettings,
        mode: RunMode,
        generation: Long
    ) {
        val normalized = settings.normalized()
        if (normalized.pattern == IntifaceVibrationPattern.Constant) {
            if (mode == RunMode.Background) {
                while (_gameState.value == GameState.HoldingPose && isIntifaceRuntimeReady()) {
                    if (!setIntifaceStrengthIfSignalCurrent(generation, normalized.strength)) return
                    delay(500)
                }
            } else {
                if (!setIntifaceStrengthIfSignalCurrent(generation, normalized.strength)) return
                val effectDurationMs = (normalized.durationSeconds * 1000).toLong()
                delay(effectDurationMs)
                setIntifaceStrengthIfSignalCurrent(generation, 0.0)
            }
            return
        }
        if (mode == RunMode.Violation) {
            val effectDurationMs = (normalized.durationSeconds * 1000).toLong()
            val deadlineMs = SystemClock.elapsedRealtime() + effectDurationMs
            while (true) {
                var remainingMs = deadlineMs - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) break
                if (!setIntifaceStrengthIfSignalCurrent(generation, normalized.strength)) return
                delay(
                    (normalized.pulseLengthSeconds * 1000).toLong()
                        .coerceAtMost(remainingMs)
                )
                remainingMs = deadlineMs - SystemClock.elapsedRealtime()
                if (!setIntifaceStrengthIfSignalCurrent(generation, 0.0)) return
                if (remainingMs <= 0L) break
                delay(
                    (normalized.pulsePauseSeconds * 1000).toLong()
                        .coerceAtMost(remainingMs)
                )
            }
            runCatching { setIntifaceStrengthIfSignalCurrent(generation, 0.0) }
            return
        }
        do {
            if (!setIntifaceStrengthIfSignalCurrent(generation, normalized.strength)) return
            delay((normalized.pulseLengthSeconds * 1000).toLong())
            if (!setIntifaceStrengthIfSignalCurrent(generation, 0.0)) return
            delay((normalized.pulsePauseSeconds * 1000).toLong())
        } while (_gameState.value == GameState.HoldingPose && isIntifaceRuntimeReady())
    }

    fun updateAudioCueSettings(cue: AudioCue, settings: AudioCueSettings) {
        _gameSettings.value = _gameSettings.value.copy(
            audioCueSettings = _gameSettings.value.audioCueSettings + (cue to settings)
        )
        prefs.edit()
            .putString(PREF_AUDIO_CUE_MODE_PREFIX + cue.name, settings.mode.name)
            .apply {
                if (settings.audioFileUri == null) {
                    remove(PREF_AUDIO_CUE_URI_PREFIX + cue.name)
                } else {
                    putString(PREF_AUDIO_CUE_URI_PREFIX + cue.name, settings.audioFileUri)
                }
            }
            .putInt(PREF_AUDIO_CUE_PCM_FREQUENCY_PREFIX + cue.name, settings.pcmSettings.frequencyHz)
            .putFloat(PREF_AUDIO_CUE_PCM_DURATION_PREFIX + cue.name, settings.pcmSettings.durationSeconds)
            .putString(PREF_AUDIO_CUE_PCM_CHANNEL_PREFIX + cue.name, settings.pcmSettings.channel.name)
            .putInt(PREF_AUDIO_CUE_PCM_AMPLITUDE_PREFIX + cue.name, settings.pcmSettings.amplitudePercent)
            .putInt(PREF_AUDIO_CUE_PCM_FADE_IN_PREFIX + cue.name, settings.pcmSettings.fadeInMs)
            .putInt(PREF_AUDIO_CUE_PCM_FADE_OUT_PREFIX + cue.name, settings.pcmSettings.fadeOutMs)
            .putString(PREF_AUDIO_CUE_PCM_PATTERN_PREFIX + cue.name, settings.pcmSettings.pattern.name)
            .apply()
    }

    fun updateTimelapseRecordingEnabled(enabled: Boolean) {
        _gameSettings.value = _gameSettings.value.copy(timelapseRecordingEnabled = enabled)
        prefs.edit().putBoolean("timelapse_recording_enabled", enabled).apply()
    }

    fun updateDebugModeEnabled(enabled: Boolean) {
        _gameSettings.value = _gameSettings.value.copy(debugModeEnabled = enabled)
        prefs.edit().putBoolean(PREF_DEBUG_MODE_ENABLED, enabled).apply()
    }

    fun resetPoseInputContinuity() {
        if (_gameState.value !in setOf(GameState.Idle, GameState.Failed, GameState.Success)) return
        synchronized(processingLock) {
            processingGeneration += 1
            poseIdentityStabilizer.reset()
            poseSmoother.reset()
            poseOcclusionGuard.reset()
            resetPoseDropoutHoldState()
            clearCameraFrameCache()
        }
    }

    fun updateFirstViolationPenaltyMinutes(value: Int) = updatePenaltyMinutes("penalty_1_min", value) { copy(firstViolationPenaltyMinutes = it) }
    fun updateSecondViolationPenaltyMinutes(value: Int) = updatePenaltyMinutes("penalty_2_min", value) { copy(secondViolationPenaltyMinutes = it) }
    fun updateThirdViolationPenaltyMinutes(value: Int) = updatePenaltyMinutes("penalty_3_min", value) { copy(thirdViolationPenaltyMinutes = it) }
    fun updateSubsequentViolationPenaltyMinutes(value: Int) = updatePenaltyMinutes("penalty_subsequent_min", value) { copy(subsequentViolationPenaltyMinutes = it) }

    private fun updatePenaltyMinutes(prefKey: String, value: Int, apply: GameSettings.(Int) -> GameSettings) {
        val normalized = value.coerceIn(0, 9999)
        _gameSettings.value = _gameSettings.value.apply(normalized)
        prefs.edit().putInt(prefKey, normalized).apply()
    }

    fun updateSelectedDurationSeconds(seconds: Int) {
        val normalizedSeconds = seconds.coerceAtLeast(1)
        _selectedDurationSeconds.value = normalizedSeconds
        prefs.edit().putInt(PREF_SELECTED_DURATION_SECONDS, normalizedSeconds).apply()

        if (
            _timerMode.value == TimerMode.Exact &&
            (_gameState.value == GameState.Idle ||
                _gameState.value == GameState.Failed ||
                _gameState.value == GameState.Success)
        ) {
            _timerSeconds.value = normalizedSeconds
        }
    }

    fun updateTimerMode(mode: TimerMode) {
        _timerMode.value = mode
        prefs.edit().putString(PREF_TIMER_MODE, mode.name).apply()
        if (_gameState.value == GameState.Idle ||
            _gameState.value == GameState.Failed ||
            _gameState.value == GameState.Success
        ) {
            _timerSeconds.value = if (mode == TimerMode.Exact) _selectedDurationSeconds.value else 0
        }
    }

    fun updateRandomDurationRangeSeconds(minSeconds: Int, maxSeconds: Int) {
        val normalizedMin = minSeconds.coerceAtLeast(1)
        val normalizedMax = maxSeconds.coerceAtLeast(normalizedMin)
        _randomMinDurationSeconds.value = normalizedMin
        _randomMaxDurationSeconds.value = normalizedMax
        prefs.edit()
            .putInt(PREF_RANDOM_MIN_DURATION_SECONDS, normalizedMin)
            .putInt(PREF_RANDOM_MAX_DURATION_SECONDS, normalizedMax)
            .apply()
    }

    fun updateSelectedDurationMinutes(minutes: Int) {
        updateSelectedDurationSeconds(minutes * 60)
    }

    fun registerCameraFrame(frame: CameraFrame, timestampMs: Long) {
        if (!frame.retain()) return
        if (isCleared) {
            frame.close()
            return
        }
        val generation = synchronized(processingLock) { processingGeneration }
        val pendingFrame = PendingPoseFrame(frame, generation)
        val removedFrames = mutableListOf<CameraFrame>()
        synchronized(frameLock) {
            if (isCleared) {
                frame.close()
                return
            }
            val previous = pendingFrames.put(timestampMs, pendingFrame)
            previous?.let { removedFrames.add(it.frame) }

            while (pendingFrames.size > 20) {
                val oldestTimestamp = pendingFrames.keys.first()
                val removed = pendingFrames.remove(oldestTimestamp)
                removed?.let { removedFrames.add(it.frame) }
            }

            val minTs = timestampMs - 3000
            val iterator = pendingFrames.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key < minTs) {
                    val removed = entry.value
                    iterator.remove()
                    removedFrames.add(removed.frame)
                }
            }
        }
        removedFrames.forEach(CameraFrame::close)
    }

    fun dropCameraFrame(timestampMs: Long) {
        val frame = synchronized(frameLock) { pendingFrames.remove(timestampMs) }
        frame?.frame?.close()
    }

    fun clearCameraFrameCache() {
        val frames = synchronized(frameLock) {
            val values = pendingFrames.values.map { it.frame }
            pendingFrames.clear()
            latestAnalyzedFrame = null
            values
        }
        frames.forEach(CameraFrame::close)
    }


    fun buildPoseDebugSnapshotJson(): String? {
        val frame = synchronized(frameLock) { latestAnalyzedFrame } ?: return null
        val landmarks = JSONArray()
        MovementTracker.TRAINING_POSE_LANDMARK_INDICES.forEach { index ->
            val point = frame.pose.landmark(index)
            landmarks.put(
                JSONObject()
                    .put("index", index)
                    .put("position", index)
                    .put("name", MovementTracker.poseLandmarkName(index))
                    .put("x", point?.x ?: JSONObject.NULL)
                    .put("y", point?.y ?: JSONObject.NULL)
                    .put("z", point?.z ?: JSONObject.NULL)
                    .put("visibility", point?.visibility ?: JSONObject.NULL)
            )
        }
        return JSONObject()
            .put("timestampMs", frame.timestampMs)
            .put("imageWidth", frame.imageWidth)
            .put("imageHeight", frame.imageHeight)
            .put("landmarks", landmarks)
            .toString(2)
    }

    fun processMediaPipeResults(rawPose: PoseLandmarks, timestamp: Long, imageWidth: Int, imageHeight: Int) {
        if (isCleared) return
        try {
            mediaPipeResultExecutor.execute {
                processMediaPipeResultsInternal(rawPose, timestamp, imageWidth, imageHeight)
            }
        } catch (e: RejectedExecutionException) {
            Log.w(tag, "Dropping MediaPipe result after executor shutdown", e)
        }
    }

    private fun processMediaPipeResultsInternal(rawPose: PoseLandmarks, timestamp: Long, imageWidth: Int, imageHeight: Int) {
        if (isCleared) return
        // Evicted/unregistered results have no generation and cannot be trusted after a reset.
        val matchedFrame = synchronized(frameLock) { pendingFrames.remove(timestamp) } ?: return
        val matchedBitmap = matchedFrame.frame.bitmap
        val stabilizedFrame = synchronized(processingLock) {
            if (matchedFrame.processingGeneration != processingGeneration ||
                timestamp <= lastProcessedPoseTimestampMs
            ) {
                null
            } else {
                lastProcessedPoseTimestampMs = timestamp
                val rawPoseMissing = rawPose.allLandmarks.size < 33
                if (rawPoseMissing) {
                    rawPoseMissingFrames += 1
                } else {
                    rawPoseOkFrames += 1
                }
                val dropoutHoldPose = if (rawPoseMissing) poseForDropoutHold(timestamp) else null
                if (dropoutHoldPose != null) {
                    Triple(
                        processingGeneration,
                        dropoutHoldPose,
                        buildPoseDropoutDebugText(
                            frames = consecutivePoseDropoutFrames,
                            ageMs = lastUsablePoseTimestampMs?.let { timestamp - it },
                            holding = true
                        )
                    )
                } else if (rawPoseMissing) {
                    consecutivePoseDropoutFrames += 1
                    poseIdentityStabilizer.reset()
                    poseSmoother.reset()
                    Triple(
                        processingGeneration,
                        rawPose,
                        buildPoseDropoutDebugText(
                            frames = consecutivePoseDropoutFrames,
                            ageMs = null,
                            holding = false
                        )
                    )
                } else {
                    consecutivePoseDropoutFrames = 0
                    val identityResult = poseIdentityStabilizer.stabilize(rawPose, timestamp)
                    val smoothedPose = poseSmoother.smooth(identityResult.pose, timestamp)
                    if (identityResult.accepted && smoothedPose.allLandmarks.size >= 33) {
                        lastUsablePoseForDropoutHold = smoothedPose
                        lastUsablePoseTimestampMs = timestamp
                    }
                    val identityDebugText = buildIdentityDebugText(identityResult)
                    val shouldCollectOcclusionCalibration =
                        _gameState.value == GameState.StartingDelay &&
                            _startDelayRemainingSeconds.value in 1..poseOcclusionCalibrationSeconds
                    if (shouldCollectOcclusionCalibration) {
                        poseOcclusionGuard.addCalibrationFrame(smoothedPose, timestamp)
                    }
                    Triple(processingGeneration, smoothedPose, identityDebugText)
                }
            }
        }
        if (stabilizedFrame == null) {
            matchedFrame.frame.close()
            return
        }
        val (frameGeneration, pose, identityDebugText) = stabilizedFrame
        if (isCleared) {
            matchedFrame.frame.close()
            return
        }
        Log.v(tag, "MediaPipe frame ts=$timestamp size=${imageWidth}x$imageHeight landmarks=${pose.allLandmarks.size}")
        val nextOverlayState = try {
            buildOverlayState(matchedBitmap, pose, timestamp, identityDebugText)
        } finally {
            matchedFrame.frame.close()
        }
        synchronized(processingLock) {
            if (processingGeneration != frameGeneration) return
            synchronized(frameLock) {
                latestAnalyzedFrame = AnalyzedPoseFrame(
                    pose = pose,
                    timestampMs = timestamp,
                    imageWidth = nextOverlayState.imageWidth,
                    imageHeight = nextOverlayState.imageHeight,
                    face = nextOverlayState.face
                )
            }
            // One clock reading per frame keeps penalty and outage decisions reproducible.
            val now = sessionTiming.nowMs()
            if (stopIfRequiredIntifaceUnavailable()) return
            if (updateFaceCheckAvailability(nextOverlayState.face.status, now)) return
            val isHoldingPose = _gameState.value == GameState.HoldingPose
            if (!isHoldingPose) {
                _poseOverlayState.value = nextOverlayState.copy(frozenLandmarkIndices = emptySet())
                return
            }

            val recorder = challengeRecorder
            val guardedPose = poseOcclusionGuard.applyForTracking(pose, timestamp)
            val trackingPose = if (recorder != null) guardedPose.quantizedForTrace() else guardedPose
            recorder?.frame(timestamp, now, nextOverlayState.face.status, trackingPose)
            _poseOverlayState.value = nextOverlayState.copy(
                frozenLandmarkIndices = poseOcclusionGuard.activeFrozenIndices()
            )

            if (!pose.hasEnoughKeypoints()) {
                resetMovementGaugeState()
                val trackingResult = movementTracker.trackFrame(trackingPose, timestamp)
                if (handleMovementViolation(trackingResult.violation, now)) return
                processFaceRule(nextOverlayState.face.status, now)
                return
            }

            val trackingResult = movementTracker.trackFrame(trackingPose, timestamp)
            _movementGaugeState.value = MovementGaugeState(
                active = trackingResult.metrics.active,
                driftNormalizedScore = trackingResult.metrics.driftNormalizedScore,
                driftThresholdFactor = trackingResult.metrics.driftThresholdFactor,
                motionNormalizedScore = trackingResult.metrics.motionNormalizedScore,
                motionThresholdFactor = trackingResult.metrics.motionThresholdFactor
            )

            if (handleMovementViolation(trackingResult.violation, now)) return

            processFaceRule(nextOverlayState.face.status, now)
        }
    }

    private fun handleMovementViolation(violation: MovementTracker.Violation, nowMs: Long): Boolean {
        val type = violation.toRuleViolationType() ?: return false
        return handleRuleViolation(type, nowMs)
    }

    private fun updateFaceCheckAvailability(status: FaceDetectionStatus, nowMs: Long): Boolean {
        if (sessionRules.faceCheckMode == FaceCheckMode.Disabled ||
            _gameState.value != GameState.HoldingPose
        ) {
            faceCheckUnavailableSinceMs = null
            return false
        }
        if (stopIfFaceCheckTimedOut(nowMs)) return true
        when (status) {
            FaceDetectionStatus.Error, FaceDetectionStatus.NotProcessed -> {
                if (faceCheckUnavailableSinceMs == null) {
                    faceCheckUnavailableSinceMs = nowMs
                    Log.w(tag, "face_check_unavailable status=$status timeoutMs=$FACE_CHECK_UNAVAILABLE_TIMEOUT_MS")
                }
            }
            FaceDetectionStatus.FaceVisible, FaceDetectionStatus.FaceNotVisible -> {
                faceCheckUnavailableSinceMs?.let {
                    Log.i(tag, "face_check_recovered elapsedMs=${nowMs - it}")
                }
                faceCheckUnavailableSinceMs = null
            }
        }
        return stopIfFaceCheckTimedOut(nowMs)
    }

    private fun stopIfFaceCheckTimedOut(nowMs: Long = sessionTiming.nowMs()): Boolean {
        if (sessionRules.faceCheckMode == FaceCheckMode.Disabled ||
            _gameState.value != GameState.HoldingPose
        ) {
            faceCheckUnavailableSinceMs = null
            return false
        }
        val since = faceCheckUnavailableSinceMs ?: return false
        if (nowMs - since < FACE_CHECK_UNAVAILABLE_TIMEOUT_MS) return false
        if (!tryReserveSessionDefeat()) return false
        Log.e(tag, "face_check_timeout elapsedMs=${nowMs - since}; session stopped without penalty")
        completeDefeatAfterReservation(
            reason = tr(R.string.face_check_unavailable),
            stoppedByTechnicalError = true,
            cause = SessionEndCause.FaceCheckUnavailable
        )
        return true
    }

    private fun processFaceRule(status: FaceDetectionStatus, nowMs: Long) {
        if (ruleEngine.onFaceStatus(sessionRules.faceCheckMode, status)) {
            handleRuleViolation(RuleViolationType.FaceNotMatchingMode, nowMs)
        }
    }

    private fun handleRuleViolation(type: RuleViolationType, nowMs: Long): Boolean {
        val rules = sessionRules
        val config = SessionRuleConfig.from(rules)
        if (ruleEngine.isRateLimited(nowMs, config)) {
            return false
        }

        var terminalDefeatReason: String? = null
        synchronized(sessionTargetLock) {
            if (!canAcceptRuleViolationLocked()) {
                return false
            }

            val decision = ruleEngine.register(type, nowMs, config)
            _violationCount.value = ruleEngine.violationCount
            _ruleViolationCounts.value = ruleEngine.counts
            when (decision) {
                is SessionRuleEngine.Decision.Defeat -> {
                    challengeRecorder?.violation(
                        TraceViolationEvent(nowMs, type.toTraceType(), decision.violationIndex, 0, defeat = true)
                    )
                    pendingTerminalResult = PendingTerminalResult.Defeat
                    terminalDefeatReason = when (type) {
                        RuleViolationType.Drift -> tr(R.string.defeat_drift)
                        RuleViolationType.Motion -> tr(R.string.defeat_motion)
                        RuleViolationType.PersonDisappeared -> tr(R.string.defeat_disappeared)
                        RuleViolationType.FaceNotMatchingMode ->
                            if (rules.faceCheckMode == FaceCheckMode.FaceToCamera) {
                                tr(R.string.defeat_face_not_to_camera)
                            } else {
                                tr(R.string.defeat_face_to_camera)
                            }
                    }
                }
                is SessionRuleEngine.Decision.Penalty -> {
                    challengeRecorder?.violation(
                        TraceViolationEvent(nowMs, type.toTraceType(), decision.violationIndex, decision.minutes, defeat = false)
                    )
                    runOnMain { triggerIntifaceViolationEffect() }
                    applyPenalty(type, decision.minutes)
                    return false
                }
                SessionRuleEngine.Decision.Ignored -> return false
            }
        }

        runOnMain {
            triggerIntifaceViolationEffect(
                resumeBackgroundAfter = false,
                requireHoldingPose = false
            )
        }
        completeDefeatAfterReservation(
            terminalDefeatReason ?: return true,
            preserveIntifaceOverride = true,
            cause = SessionEndCause.TooManyViolations
        )
        return true
    }

    private fun RuleViolationType.toTraceType(): TraceViolationType = when (this) {
        RuleViolationType.Drift -> TraceViolationType.Drift
        RuleViolationType.Motion -> TraceViolationType.Motion
        RuleViolationType.PersonDisappeared -> TraceViolationType.PersonDisappeared
        RuleViolationType.FaceNotMatchingMode -> TraceViolationType.Face
    }

    private fun canAcceptRuleViolationLocked(): Boolean =
        _gameState.value == GameState.HoldingPose && pendingTerminalResult == null

    private fun resetRuleCounters() {
        ruleEngine.reset()
        _violationCount.value = 0
        _ruleViolationCounts.value = RuleViolationCounts()
    }

    private fun applyPenalty(type: RuleViolationType, minutes: Int) {
        val sec = minutes * 60
        val rules = sessionRules
        val penaltyApplied = rules.penaltiesEnabled && sec > 0
        if (penaltyApplied) {
            synchronized(sessionTargetLock) {
                if (_gameState.value == GameState.HoldingPose &&
                    pendingTerminalResult == null
                ) {
                    sessionClock.addPenalty(sec)
                    updateSessionTimerLocked()
                }
            }
        }

        if (type == RuleViolationType.FaceNotMatchingMode) {
            val statusText = if (penaltyApplied) {
                tr(R.string.face_rule_violated_with_penalty, minutes)
            } else {
                tr(R.string.face_rule_violated)
            }
            val prefixTemplate = if (rules.faceCheckMode == FaceCheckMode.FaceToCamera) {
                TtsPhraseTemplate.FaceTurnedAway
            } else {
                TtsPhraseTemplate.FaceLookedAtCamera
            }
            _statusMessage.value = statusText
            val cue = if (rules.faceCheckMode == FaceCheckMode.FaceToCamera) {
                AudioCue.FaceTurnedAway
            } else {
                AudioCue.FaceLookedAtCamera
            }
            val cueText = if (penaltyApplied) {
                "${ttsText(prefixTemplate)}. ${ttsText(TtsPhraseTemplate.PenaltyAddedToTimer, minutes)}"
            } else {
                ttsText(prefixTemplate)
            }
            playAudioCue(cue, cueText)
            return
        }

        val (cue, voiceTemplate) = when (type) {
            RuleViolationType.Motion -> AudioCue.MotionViolation to TtsPhraseTemplate.MotionViolation
            RuleViolationType.Drift -> AudioCue.DriftViolation to TtsPhraseTemplate.DriftViolation
            else -> AudioCue.ViolationRecorded to TtsPhraseTemplate.ViolationRecorded
        }

        if (penaltyApplied) {
            _statusMessage.value = tr(R.string.violation_recorded_with_penalty, minutes)
            playAudioCue(
                cue,
                "${ttsText(voiceTemplate)}. ${ttsText(TtsPhraseTemplate.PenaltyAddedToTimer, minutes)}"
            )
        } else {
            _statusMessage.value = tr(R.string.violation_recorded)
            playAudioCue(cue, ttsText(voiceTemplate))
        }
    }

    private fun buildOverlayState(
        bitmap: Bitmap,
        pose: PoseLandmarks,
        timestamp: Long,
        identityDebugText: String
    ): PoseOverlayState {
        val settings = sessionRules
        if (settings.faceCheckMode == FaceCheckMode.Disabled && !settings.debugModeEnabled) {
            return buildOverlayStateWithoutFace(pose, bitmap.width, bitmap.height, identityDebugText)
        }
        val cropRect = PoseFrameCropper.calculateCropRect(bitmap.width, bitmap.height, pose)
        val faceOverlayState = if (cropRect != null) {
            val faceCandidateRect = FaceCandidateCropper.calculateFaceCandidateRect(bitmap.width, bitmap.height, pose, cropRect)
            if (faceCandidateRect == null) FaceOverlayState(status = FaceDetectionStatus.NotProcessed, debugMessage = "face=NotProcessed no face candidate crop")
            else {
                var faceCropBitmap: Bitmap? = null
                try {
                    faceCropBitmap = Bitmap.createBitmap(bitmap, faceCandidateRect.left, faceCandidateRect.top, faceCandidateRect.width, faceCandidateRect.height)
                    val faceResult = faceDetectorService.detectOnCrop(faceCropBitmap)
                    val inputRect = PoseOverlayRect(faceCandidateRect.left / bitmap.width.toFloat(), faceCandidateRect.top / bitmap.height.toFloat(), faceCandidateRect.right / bitmap.width.toFloat(), faceCandidateRect.bottom / bitmap.height.toFloat())
                    if (faceResult.status == FaceDetectionStatus.FaceVisible && faceResult.boundingBox != null) {
                        val box = faceResult.boundingBox
                        FaceOverlayState(
                            status = FaceDetectionStatus.FaceVisible,
                            faceRect = PoseOverlayRect(
                                ((faceCandidateRect.left + box.leftPx) / bitmap.width).coerceIn(0f, 1f),
                                ((faceCandidateRect.top + box.topPx) / bitmap.height).coerceIn(0f, 1f),
                                ((faceCandidateRect.left + box.rightPx) / bitmap.width).coerceIn(0f, 1f),
                                ((faceCandidateRect.top + box.bottomPx) / bitmap.height).coerceIn(0f, 1f)
                            ),
                            keypoints = faceResult.keypoints.map { FaceOverlayPoint(((faceCandidateRect.left + it.x * faceCandidateRect.width) / bitmap.width).coerceIn(0f, 1f), ((faceCandidateRect.top + it.y * faceCandidateRect.height) / bitmap.height).coerceIn(0f, 1f)) },
                            score = faceResult.score,
                            detectorInputRect = inputRect,
                            detectorInputWidth = faceCropBitmap.width,
                            detectorInputHeight = faceCropBitmap.height,
                            debugMessage = "face=FaceVisible input=${faceCropBitmap.width}x${faceCropBitmap.height} score=${faceResult.score}"
                        )
                    } else {
                        FaceOverlayState(status = faceResult.status, score = faceResult.score, detectorInputRect = inputRect, detectorInputWidth = faceCropBitmap.width, detectorInputHeight = faceCropBitmap.height, debugMessage = "face=${faceResult.status} input=${faceCropBitmap.width}x${faceCropBitmap.height}")
                    }
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to prepare face crop", t)
                    FaceOverlayState(status = FaceDetectionStatus.Error, debugMessage = "face=Error input=0x0")
                } finally {
                    if (faceCropBitmap !== bitmap) faceCropBitmap?.recycleIfNeeded()
                }
            }
        } else FaceOverlayState(status = FaceDetectionStatus.NotProcessed, debugMessage = "face=NotProcessed no body crop")

        val normalizedRect = cropRect?.let { PoseOverlayRect(it.left.toFloat() / bitmap.width, it.top.toFloat() / bitmap.height, it.right.toFloat() / bitmap.width, it.bottom.toFloat() / bitmap.height) }
        return PoseOverlayState(bitmap.width, bitmap.height, pose.allLandmarks, normalizedRect, faceOverlayState, identityDebugText = identityDebugText)
    }

    private fun buildOverlayStateWithoutFace(
        pose: PoseLandmarks,
        imageWidth: Int,
        imageHeight: Int,
        identityDebugText: String
    ): PoseOverlayState {
        val safeWidth = imageWidth.coerceAtLeast(0)
        val safeHeight = imageHeight.coerceAtLeast(0)
        val normalizedRect = if (safeWidth > 0 && safeHeight > 0) {
            PoseFrameCropper.calculateCropRect(safeWidth, safeHeight, pose)?.let {
                PoseOverlayRect(
                    it.left.toFloat() / safeWidth,
                    it.top.toFloat() / safeHeight,
                    it.right.toFloat() / safeWidth,
                    it.bottom.toFloat() / safeHeight
                )
            }
        } else {
            null
        }
        val faceOverlayState = FaceOverlayState(
            status = FaceDetectionStatus.NotProcessed,
            debugMessage = "face=NotProcessed disabled"
        )
        return PoseOverlayState(safeWidth, safeHeight, pose.allLandmarks, normalizedRect, faceOverlayState, identityDebugText = identityDebugText)
    }

    private fun resetPoseDropoutHoldState() {
        lastUsablePoseForDropoutHold = null
        lastUsablePoseTimestampMs = null
        consecutivePoseDropoutFrames = 0
        rawPoseOkFrames = 0
        rawPoseMissingFrames = 0
    }

    private fun poseForDropoutHold(timestampMs: Long): PoseLandmarks? {
        val heldPose = lastUsablePoseForDropoutHold ?: return null
        val heldTimestamp = lastUsablePoseTimestampMs ?: return null
        val nextDropoutFrames = consecutivePoseDropoutFrames + 1
        val ageMs = timestampMs - heldTimestamp
        if (nextDropoutFrames > MAX_POSE_DROPOUT_HOLD_FRAMES || ageMs > MAX_POSE_DROPOUT_HOLD_MS) {
            return null
        }
        consecutivePoseDropoutFrames = nextDropoutFrames
        return heldPose
    }

    private fun buildPoseDropoutDebugText(frames: Int, ageMs: Long?, holding: Boolean): String {
        val statusText = if (holding) "hold(no_pose)" else "rejected(no_pose)"
        val ageText = if (holding && ageMs != null) " age=${ageMs.coerceAtLeast(0L)}ms" else ""
        return "identity: $statusText frames=$frames$ageText rawOk=$rawPoseOkFrames rawMiss=$rawPoseMissingFrames"
    }

    private fun buildIdentityDebugText(identityResult: PoseIdentityStabilizationResult): String {
        val ambiguousText = if (identityResult.ambiguous) " ambiguous" else ""
        val acceptanceText = when {
            identityResult.outlier -> " outlier(${identityResult.outlierReason})"
            identityResult.accepted -> " accepted"
            else -> " rejected(${identityResult.rejectReason})"
        }
        val detailsText = if (identityResult.debugDetails.isBlank()) "" else " ${identityResult.debugDetails}"
        return String.format(
            Locale.US,
            "identity: %s%s%s d=%.3f s=%.3f%s",
            identityResult.transform.name,
            ambiguousText,
            acceptanceText,
            identityResult.directScore,
            identityResult.swappedScore,
            detailsText
        )
    }

    private suspend fun getFreshAnalyzedFrame(maxAgeMs: Long): AnalyzedPoseFrame? {
        val deadline = SystemClock.elapsedRealtime() + maxAgeMs
        while (SystemClock.elapsedRealtime() <= deadline) {
            val frame = synchronized(frameLock) { latestAnalyzedFrame }
            if (frame != null) {
                val ageMs = sessionTiming.nowMs() - frame.timestampMs
                if (ageMs in 0..maxAgeMs) {
                    return frame
                }
            }
            delay(40)
        }
        return null
    }

    private fun sessionElapsedSecondsForSummary(): Int {
        return synchronized(sessionTargetLock) {
            sessionClock.snapshot(sessionTiming.nowMs()).elapsedSeconds
        }
    }

    private fun tryReserveSessionSuccess(): Boolean = synchronized(sessionTargetLock) {
        if (_gameState.value != GameState.HoldingPose ||
            pendingTerminalResult != null
        ) {
            return@synchronized false
        }

        // If time expires during a brief outage, wait for recovery or the outage timeout.
        // Do not award success for a face rule that cannot currently be evaluated.
        val canComplete = updateSessionTimerLocked() && faceCheckUnavailableSinceMs == null

        if (canComplete) {
            pendingTerminalResult = PendingTerminalResult.Success
            true
        } else {
            false
        }
    }

    private fun tryReserveSessionDefeat(includeStabilization: Boolean = false): Boolean = synchronized(sessionTargetLock) {
        val canReserveDefeat = when (_gameState.value) {
            GameState.HoldingPose,
            GameState.StartingDelay -> true
            GameState.WaitingForStabilization -> includeStabilization
            else -> false
        }

        if (canReserveDefeat && pendingTerminalResult == null) {
            pendingTerminalResult = PendingTerminalResult.Defeat
            true
        } else {
            false
        }
    }

    private fun completeSessionSuccess() = synchronized(processingLock) {
        val canComplete = synchronized(sessionTargetLock) {
            pendingTerminalResult == PendingTerminalResult.Success &&
                _gameState.value == GameState.HoldingPose
        }

        if (!canComplete) {
            return@synchronized
        }
        val intifaceSnapshot = intifaceController.state.value
        if (stopIfRequiredIntifaceUnavailable(intifaceSnapshot)) return@synchronized

        _sessionSummary.value = SessionSummary(
            result = GameState.Success,
            initialTimerSeconds = sessionInitialTimerSeconds,
            actualTimerSeconds = sessionElapsedSecondsForSummary(),
            violationCounts = _ruleViolationCounts.value,
            settings = sessionSettingsSnapshot,
            showInitialTimer = _activeChallenge.value?.spec?.visibility?.showDuration != false
        )
        finishChallengeSession(SessionEndCause.Completed, intifaceSnapshot)
        _gameState.value = GameState.Success
        faceCheckUnavailableSinceMs = null
        stopIntifaceSessionSignals()
        resetMovementGaugeState()
        _statusMessage.value = tr(R.string.victory)
        playAudioCue(AudioCue.TimeIsUp, ttsText(TtsPhraseTemplate.TimeIsUp))
    }

    private fun updateSessionTimerLocked(): Boolean {
        val snapshot = sessionClock.snapshot(sessionTiming.nowMs())
        _timerSeconds.value = if (sessionShowsElapsed) {
            snapshot.elapsedSeconds
        } else {
            snapshot.remainingSeconds
        }
        return snapshot.finished
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_gameState.value == GameState.HoldingPose) {
                checkSessionProgress()
                delay(250)
            }
        }
    }

    private fun checkSessionProgress() = synchronized(processingLock) {
        if (_gameState.value != GameState.HoldingPose) return@synchronized
        if (stopIfRequiredIntifaceUnavailable()) return@synchronized
        if (stopIfFaceCheckTimedOut()) return@synchronized
        // A stalled camera/delegate must not award success without pose validation.
        val lastFrame = synchronized(frameLock) { latestAnalyzedFrame }
        if (lastFrame == null || sessionTiming.nowMs() - lastFrame.timestampMs > 5_000L) {
            triggerDefeat(tr(R.string.camera_no_frame))
            return@synchronized
        }
        if (tryReserveSessionSuccess()) completeSessionSuccess()
    }

    fun onCameraUnavailable() {
        when (_gameState.value) {
            GameState.StartingDelay, GameState.HoldingPose -> triggerDefeat(tr(R.string.camera_no_frame))
            GameState.WaitingForStabilization -> stopSession()
            else -> Unit
        }
        pauseIntifaceSessionSignals()
    }

    /** True only when a new attempt was accepted; callers may then discard the previous result's media. */
    fun startSession(): Boolean = synchronized(processingLock) {
        if (_gameState.value != GameState.Idle && _gameState.value != GameState.Failed && _gameState.value != GameState.Success) return@synchronized false

        val challenge = _activeChallenge.value?.spec
        val rules = sessionRules
        if (challenge != null && challenge.rules.intifaceRequired && !isIntifaceRuntimeReady(rules)) {
            _challengeMessage.value = ChallengeMessage.IntifaceRequired
            return@synchronized false
        }
        val attempt = if (challenge != null) {
            challengeRepository.beginAttempt(challenge) ?: run {
                _challengeMessage.value = ChallengeMessage.NoAttemptsLeft
                return@synchronized false
            }
        } else {
            0
        }

        _sessionSummary.value = null
        _challengeReport.value = null
        val timer = challenge?.rules?.timer
        sessionTimerMode = timer?.mode ?: _timerMode.value
        sessionInitialTimerSeconds = if (sessionTimerMode == TimerMode.Random) {
            val minSeconds = timer?.randomMinSeconds ?: _randomMinDurationSeconds.value
            val maxSeconds = (timer?.randomMaxSeconds ?: _randomMaxDurationSeconds.value).coerceAtLeast(minSeconds)
            Random.nextLong(minSeconds.toLong(), maxSeconds.toLong() + 1L).toInt()
        } else {
            timer?.exactSeconds ?: _selectedDurationSeconds.value
        }
        sessionShowsElapsed = sessionTimerMode == TimerMode.Random ||
            (challenge != null && !challenge.visibility.showDuration)
        sessionSettingsSnapshot = rules
        // Settings edited while a challenge waited may have retuned the engines.
        applySettingsToEngines(rules)
        _timerSeconds.value = if (sessionShowsElapsed) 0 else sessionInitialTimerSeconds
        _defeatReason.value = ""
        resetMovementGaugeState()
        _startDelayRemainingSeconds.value = 0
        synchronized(processingLock) {
            processingGeneration += 1
            movementTracker.reset()
            poseIdentityStabilizer.reset()
            poseSmoother.reset()
            poseOcclusionGuard.reset()
            resetPoseDropoutHoldState()
            resetRuleCounters()
            faceCheckUnavailableSinceMs = null
            challengeRecorder = challenge?.let {
                ChallengeSessionRecorder(
                    spec = it,
                    attempt = attempt,
                    appVersion = BuildConfig.VERSION_NAME,
                    startedAtMonoMs = sessionTiming.nowMs(),
                    startedAtWallMs = System.currentTimeMillis()
                )
            }
            synchronized(sessionTargetLock) {
                sessionClock.reset(sessionInitialTimerSeconds)
                pendingTerminalResult = null
            }
        }
        startDelayJob?.cancel()
        timerJob?.cancel()
        stabilizationFallbackJob?.cancel()

        stabilizationStableSinceMs = null
        stabilizationCompleted = false
        _gameState.value = GameState.WaitingForStabilization
        _statusMessage.value = tr(R.string.place_device_still)
        playAudioCue(AudioCue.PlaceDeviceStill, ttsText(TtsPhraseTemplate.PlaceDeviceStill))
        sensorManager.unregisterListener(this)

        val gyroscopeRegistered = gyroscopeSensor?.let { sensor ->
            runCatching {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            }.onFailure { error ->
                Log.w(tag, "Unable to register gyroscope listener; using timed stabilization", error)
            }.getOrDefault(false)
        } ?: false
        if (!gyroscopeRegistered) {
            stabilizationFallbackJob = viewModelScope.launch {
                delay(stabilizationDurationMs)
                completeDeviceStabilization()
            }
        }
        true
    }

    private fun startPoseCountdownAfterDeviceStabilized() {
        playAudioCue(AudioCue.TakePosition, ttsText(TtsPhraseTemplate.TakePosition))
        synchronized(processingLock) {
            poseOcclusionGuard.reset()
        }
        _gameState.value = GameState.StartingDelay
        startDelayJob?.cancel()
        startDelayJob = viewModelScope.launch {
            for (i in startDelaySeconds downTo 1) {
                _startDelayRemainingSeconds.value = i
                _statusMessage.value = tr(R.string.start_in_pose, i)
                delay(1000)
            }
            _startDelayRemainingSeconds.value = 0
            val analyzedFrame = getFreshAnalyzedFrame(1200)
            synchronized(processingLock) {
                if (_gameState.value != GameState.StartingDelay || stopIfRequiredIntifaceUnavailable()) return@launch
            }
            val initialPose = analyzedFrame?.pose
            if (analyzedFrame == null) { triggerDefeat(tr(R.string.camera_no_frame)); return@launch }
            if (initialPose == null || !initialPose.hasEnoughKeypoints()) {
                triggerDefeat(tr(R.string.camera_no_body), cause = SessionEndCause.CameraNoBody)
                return@launch
            }
            _timerSeconds.value = if (sessionShowsElapsed) 0 else sessionInitialTimerSeconds
            synchronized(processingLock) {
                if (_gameState.value != GameState.StartingDelay || stopIfRequiredIntifaceUnavailable()) return@launch
                val clockStartedAtMs = sessionTiming.nowMs()
                synchronized(sessionTargetLock) {
                    sessionClock.start(clockStartedAtMs)
                }
                processingGeneration += 1
                movementTracker.reset()
                resetRuleCounters()
                poseOcclusionGuard.finishCalibration(
                    referencePose = initialPose,
                    referenceTimestampMs = analyzedFrame.timestampMs
                )
                val recorder = challengeRecorder
                val guardedInitialPose = poseOcclusionGuard.buildReferencePose(initialPose)
                    .let { if (recorder != null) it.quantizedForTrace() else it }
                movementTracker.startTracking(guardedInitialPose)
                recorder?.startTrace(
                    referenceTimestampMs = analyzedFrame.timestampMs,
                    reference = guardedInitialPose,
                    clockStartedAtMs = clockStartedAtMs,
                    initialFaceStatus = analyzedFrame.face.status,
                    imageWidth = analyzedFrame.imageWidth,
                    imageHeight = analyzedFrame.imageHeight
                )
                _gameState.value = GameState.HoldingPose
                updateFaceCheckAvailability(analyzedFrame.face.status, clockStartedAtMs)
                _statusMessage.value = tr(R.string.time_started_hold_position)
                startTimerLoop()
                startIntifaceSessionSignals()
                playAudioCue(
                    AudioCue.TimeStartedHoldPosition,
                    ttsText(TtsPhraseTemplate.TimeStartedHoldPosition)
                )
            }
        }
    }

    private fun completeDeviceStabilization() = synchronized(processingLock) {
        if (stopIfRequiredIntifaceUnavailable()) return@synchronized
        if (_gameState.value != GameState.WaitingForStabilization || stabilizationCompleted) return@synchronized
        stabilizationCompleted = true
        stabilizationFallbackJob?.cancel()
        stabilizationFallbackJob = null
        sensorManager.unregisterListener(this)
        stabilizationStableSinceMs = null
        startPoseCountdownAfterDeviceStabilized()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        if (_gameState.value != GameState.WaitingForStabilization) return

        val magnitude = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
        )

        val now = sessionTiming.nowMs()
        if (magnitude <= gyroscopeStillThresholdRadPerSec) {
            val stableSince = stabilizationStableSinceMs ?: now.also { stabilizationStableSinceMs = it }
            if (now - stableSince >= stabilizationDurationMs) {
                viewModelScope.launch {
                    completeDeviceStabilization()
                }
            }
        } else {
            stabilizationStableSinceMs = null
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun resetMovementGaugeState() { _movementGaugeState.value = MovementGaugeState() }

    fun triggerDefeat(reason: String, preserveIntifaceOverride: Boolean = false) =
        triggerDefeat(reason, preserveIntifaceOverride, SessionEndCause.CameraNoFrame)

    private fun triggerDefeat(
        reason: String,
        preserveIntifaceOverride: Boolean = false,
        cause: SessionEndCause
    ) {
        synchronized(processingLock) {
            if (!tryReserveSessionDefeat(includeStabilization = cause == SessionEndCause.Surrendered)) {
                return
            }
            completeDefeatAfterReservation(reason, preserveIntifaceOverride, cause = cause)
        }
    }

    private fun completeDefeatAfterReservation(
        reason: String,
        preserveIntifaceOverride: Boolean = false,
        stoppedByTechnicalError: Boolean = false,
        cause: SessionEndCause
    ) {
        runOnMain {
            if (preserveIntifaceOverride) {
                intifaceBackgroundJob?.cancel()
                intifaceBackgroundJob = null
            } else {
                stopIntifaceSessionSignals()
            }

            startDelayJob?.cancel()
            timerJob?.cancel()
            stabilizationFallbackJob?.cancel()
            stabilizationFallbackJob = null
            sensorManager.unregisterListener(this)
            stabilizationStableSinceMs = null
            stabilizationCompleted = false
        }
        _startDelayRemainingSeconds.value = 0
        synchronized(processingLock) {
            processingGeneration += 1
            faceCheckUnavailableSinceMs = null
            poseIdentityStabilizer.reset()
            poseSmoother.reset()
            movementTracker.reset()
            poseOcclusionGuard.reset()
            resetPoseDropoutHoldState()
        }
        resetMovementGaugeState()
        _sessionSummary.value = SessionSummary(
            result = GameState.Failed,
            initialTimerSeconds = sessionInitialTimerSeconds,
            actualTimerSeconds = sessionElapsedSecondsForSummary(),
            violationCounts = _ruleViolationCounts.value,
            settings = sessionSettingsSnapshot,
            defeatReason = reason,
            stoppedByTechnicalError = stoppedByTechnicalError,
            showInitialTimer = _activeChallenge.value?.spec?.visibility?.showDuration != false
        )
        finishChallengeSession(cause)
        _gameState.value = GameState.Failed
        _defeatReason.value = reason
        _statusMessage.value = tr(if (stoppedByTechnicalError) R.string.session_stopped else R.string.check_failed)
        if (!stoppedByTechnicalError) {
            playAudioCue(AudioCue.DefeatTryAgain, ttsText(TtsPhraseTemplate.DefeatTryAgain))
        }
    }

    private fun defaultTtsTemplateText(template: TtsPhraseTemplate): String = when (template) {
        TtsPhraseTemplate.PlaceDeviceStill -> tr(R.string.place_device_still)
        TtsPhraseTemplate.TakePosition -> tr(R.string.take_position)
        TtsPhraseTemplate.TimeStartedHoldPosition -> tr(R.string.time_started_hold_position)
        TtsPhraseTemplate.TimeIsUp -> tr(R.string.time_is_up)
        TtsPhraseTemplate.DefeatTryAgain -> tr(R.string.defeat_try_again)
        TtsPhraseTemplate.MotionViolation -> tr(R.string.motion_violation_voice)
        TtsPhraseTemplate.DriftViolation -> tr(R.string.drift_violation_voice)
        TtsPhraseTemplate.ViolationRecorded -> tr(R.string.violation_recorded)
        TtsPhraseTemplate.FaceTurnedAway -> tr(R.string.you_turned_away)
        TtsPhraseTemplate.FaceLookedAtCamera -> tr(R.string.you_looked_at_camera)
        TtsPhraseTemplate.PenaltyAddedToTimer -> tr(R.string.penalty_added_to_timer_template)
    }

    private fun ttsText(template: TtsPhraseTemplate): String {
        val rules = sessionRules
        return rules.customTtsTemplates[template]
            ?.takeIf { rules.customizeAudioEnabled && it.isNotBlank() }
            ?: defaultTtsTemplateText(template)
    }

    private fun ttsText(template: TtsPhraseTemplate, minutes: Int): String =
        ttsText(template).replace("{minutes}", minutes.toString())

    private fun playAudioCue(cue: AudioCue, ttsText: String) {
        _audioCueEvents.tryEmit(AudioCueEvent(cue, ttsText))
    }

    /** Builds the challenge report once a challenge session has a result, and keeps it on disk. */
    private fun finishChallengeSession(
        cause: SessionEndCause,
        intifaceSnapshot: IntifaceUiState = intifaceController.state.value
    ) = synchronized(processingLock) {
        val recorder = challengeRecorder ?: return@synchronized
        challengeRecorder = null
        val endedAtMs = sessionTiming.nowMs()
        val report = recorder.finish(
            endCause = cause,
            endedAtMonoMs = endedAtMs,
            endedAtWallMs = System.currentTimeMillis(),
            initialTimerSeconds = sessionInitialTimerSeconds,
            actualTimerSeconds = synchronized(sessionTargetLock) { sessionClock.snapshot(endedAtMs).elapsedSeconds },
            counts = _ruleViolationCounts.value,
            intifaceConnected = cause != SessionEndCause.IntifaceUnavailable &&
                intifaceSnapshot.isConnected && intifaceSnapshot.selectedDevice != null
        )
        _challengeReport.value = report
        val spec = _activeChallenge.value?.spec
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { challengeRepository.saveOutgoingReport(report, spec) }.onFailure {
                Log.e(tag, "Unable to store challenge report", it)
                _challengeMessage.value = ChallengeMessage.ReportSaveFailed
            }
        }
    }

    /** Starts using [challengeId]'s rules for the next sessions. Returns false when it is unknown. */
    suspend fun acceptChallenge(challengeId: Blob): Boolean {
        val challenge = withContext(Dispatchers.IO) {
            challengeRepository.runnableChallenge(challengeId)?.let { spec ->
                ActiveChallenge(spec, challengeRepository.audioFiles(spec))
            }
        } ?: return false
        synchronized(processingLock) {
            if (_gameState.value !in setOf(GameState.Idle, GameState.Failed, GameState.Success)) return false
            _activeChallenge.value = challenge
            if (_gameState.value == GameState.Idle) updateIdleTimerDisplay()
        }
        return true
    }

    private val _challengeDraft = MutableStateFlow(ChallengeDraft(authorName = challengeRepository.executorName))
    /** The challenge open in the editor; kept until it is created or reset. */
    val challengeDraft: StateFlow<ChallengeDraft> = _challengeDraft.asStateFlow()

    fun updateChallengeDraft(transform: (ChallengeDraft) -> ChallengeDraft) {
        _challengeDraft.update(transform)
    }

    /** Starts a new draft with default rules. */
    fun resetChallengeDraft() {
        _challengeDraft.value = ChallengeDraft(authorName = challengeRepository.executorName)
    }

    /**
     * Replaces the draft's rules with the user's current settings and duration, reading the
     * audio files picked in the settings. Files that are unreadable or too large are dropped
     * and those cues fall back to the spoken phrase.
     */
    suspend fun copySettingsIntoDraft() {
        val settings = _gameSettings.value
        val timer = TimerSpec(
            mode = _timerMode.value,
            exactSeconds = _selectedDurationSeconds.value,
            randomMinSeconds = _randomMinDurationSeconds.value,
            randomMaxSeconds = _randomMaxDurationSeconds.value
        )
        val clips = withContext(Dispatchers.IO) {
            if (!settings.customizeAudioEnabled) return@withContext emptyMap()
            var total = 0
            settings.audioCueSettings.mapNotNull { (cue, cueSettings) ->
                val uri = cueSettings.audioFileUri?.takeIf { cueSettings.mode == AudioCueMode.AudioFile }
                    ?: return@mapNotNull null
                val clip = readAudioClip(Uri.parse(uri)) ?: return@mapNotNull null
                if (total + clip.data.size > AudioClip.MAX_TOTAL_BYTES) return@mapNotNull null
                total += clip.data.size
                cue to clip
            }.toMap()
        }
        val rules = ChallengeRules.from(settings, timer, clips)
        _challengeDraft.update { it.copy(rules = rules.copy(intifaceRequired = it.rules.intifaceRequired && rules.usesIntiface)) }
    }

    /** Embeds the audio file at [uri] for [cue] in the draft. */
    suspend fun attachDraftAudio(cue: AudioCue, uri: Uri): AudioAttachResult {
        val clip = withContext(Dispatchers.IO) { readAudioClip(uri) }
            ?: return if (withContext(Dispatchers.IO) { audioSize(uri) } > AudioClip.MAX_BYTES) AudioAttachResult.TooLarge
            else AudioAttachResult.Unreadable
        var result = AudioAttachResult.Attached
        _challengeDraft.update { draft ->
            result = AudioAttachResult.Attached
            val others = draft.rules.audioClips - cue
            if (others.values.sumOf { it.data.size } + clip.data.size > AudioClip.MAX_TOTAL_BYTES) {
                result = AudioAttachResult.TotalTooLarge
                return@update draft
            }
            val cueSettings = draft.rules.audioCueSettings[cue] ?: AudioCueSettings()
            draft.copy(
                rules = draft.rules.copy(
                    audioCueSettings = draft.rules.audioCueSettings + (cue to cueSettings.copy(mode = AudioCueMode.AudioFile)),
                    audioClips = others + (cue to clip)
                )
            )
        }
        return result
    }

    private fun readAudioClip(uri: Uri): AudioClip? = runCatching {
        val resolver = getApplication<Application>().contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBounded(AudioClip.MAX_BYTES) }
            ?.takeIf { it.isNotEmpty() } ?: return null
        AudioClip(resolver.getType(uri)?.take(AudioClip.MAX_MIME_LENGTH) ?: "audio/*", Blob(bytes))
    }.getOrNull()

    private fun audioSize(uri: Uri): Long = runCatching {
        getApplication<Application>().contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull() ?: -1L

    /** Creates a challenge from the draft and starts a new draft. */
    suspend fun createChallenge(draft: ChallengeDraft): AuthoredChallenge = withContext(Dispatchers.IO) {
        challengeRepository.executorName = draft.authorName
        challengeRepository.createChallenge(
            title = draft.title,
            description = draft.description,
            authorName = draft.authorName,
            rules = draft.rules.copy(intifaceRequired = draft.rules.intifaceRequired && draft.rules.usesIntiface),
            visibility = draft.visibility,
            attemptsAllowed = draft.attemptsAllowed
        )
    }.also { resetChallengeDraft() }

    /** Returns to the user's own rules. */
    fun leaveChallenge() = synchronized(processingLock) {
        if (_gameState.value !in setOf(GameState.Idle, GameState.Failed, GameState.Success)) return@synchronized
        _activeChallenge.value = null
        applySettingsToEngines(_gameSettings.value)
        if (_gameState.value == GameState.Idle) updateIdleTimerDisplay()
    }

    fun consumeChallengeMessage() {
        _challengeMessage.value = null
    }

    private val _importResult = MutableStateFlow<ImportResult?>(null)
    /** Outcome of the last opened challenge file, report file or pasted code. */
    val importResult: StateFlow<ImportResult?> = _importResult.asStateFlow()

    fun importChallengeFromUri(uri: Uri) = importFromUri(uri, expectedKind = null)

    fun importTaskFromUri(uri: Uri) = importFromUri(uri, ChallengeFileKind.Task)

    fun importReportFromUri(uri: Uri) = importFromUri(uri, ChallengeFileKind.Report)

    private fun importFromUri(uri: Uri, expectedKind: ChallengeFileKind?) {
        viewModelScope.launch {
            _importResult.value = withContext(Dispatchers.IO) {
                val bytes = runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        input.readBounded(ChallengeFiles.MAX_INFLATED_BYTES)
                    }
                }.getOrNull()
                if (bytes == null) ImportResult.Failed(ChallengeFileError.NotAChallengeFile)
                else challengeRepository.import(bytes, expectedKind = expectedKind)
            }
        }
    }

    fun importChallengeFromText(text: String) {
        viewModelScope.launch {
            _importResult.value = withContext(Dispatchers.IO) {
                challengeRepository.import(text.toByteArray(Charsets.UTF_8), expectedKind = ChallengeFileKind.Task)
            }
        }
    }

    fun consumeImportResult() {
        _importResult.value = null
    }

    private fun updateIdleTimerDisplay() {
        val challenge = _activeChallenge.value?.spec
        _timerSeconds.value = when {
            challenge == null -> if (_timerMode.value == TimerMode.Exact) _selectedDurationSeconds.value else 0
            challenge.rules.timer.mode == TimerMode.Exact && challenge.visibility.showDuration -> challenge.rules.timer.exactSeconds
            else -> 0
        }
    }

    fun dismissFinalScreen() = synchronized(processingLock) {
        if (_gameState.value == GameState.Success || _gameState.value == GameState.Failed) {
            _challengeReport.value = null
            stopSession()
        }
    }

    fun stopSession() = synchronized(processingLock) {
        // Stopping a running challenge is a surrender: it ends with a result and a report.
        if (challengeRecorder != null && _gameState.value in setOf(
                GameState.WaitingForStabilization,
                GameState.StartingDelay,
                GameState.HoldingPose
            )
        ) {
            triggerDefeat(tr(R.string.challenge_surrendered), cause = SessionEndCause.Surrendered)
            return@synchronized
        }
        challengeRecorder = null
        faceCheckUnavailableSinceMs = null
        startDelayJob?.cancel()
        timerJob?.cancel()
        stabilizationFallbackJob?.cancel()
        stabilizationFallbackJob = null
        sensorManager.unregisterListener(this)
        stabilizationStableSinceMs = null
        stabilizationCompleted = false
        _startDelayRemainingSeconds.value = 0
        synchronized(processingLock) {
            processingGeneration += 1
            poseIdentityStabilizer.reset()
            poseSmoother.reset()
            movementTracker.reset()
            poseOcclusionGuard.reset()
            resetPoseDropoutHoldState()
            resetRuleCounters()
        }
        synchronized(sessionTargetLock) {
            pendingTerminalResult = null
        }
        _sessionSummary.value = null
        resetMovementGaugeState()
        _gameState.value = GameState.Idle
        _statusMessage.value = tr(R.string.status_initial)
        _defeatReason.value = ""
        updateIdleTimerDisplay()
        stopIntifaceSessionSignals()
    }
    override fun onCleared() {
        isCleared = true
        nextIntifaceSignalGeneration()
        intifaceBackgroundJob?.cancel()
        intifaceOverrideJob?.cancel()
        intifaceController.disconnect()
        stabilizationFallbackJob?.cancel()
        stabilizationFallbackJob = null
        sensorManager.unregisterListener(this)
        stabilizationStableSinceMs = null
        stabilizationCompleted = false
        synchronized(processingLock) {
            processingGeneration += 1
            faceCheckUnavailableSinceMs = null
            poseIdentityStabilizer.reset()
            poseSmoother.reset()
            movementTracker.reset()
            poseOcclusionGuard.reset()
            resetPoseDropoutHoldState()
        }
        synchronized(sessionTargetLock) {
            pendingTerminalResult = null
        }
        clearCameraFrameCache()
        // A native face call may still be running. Close on its owner executor rather
        // than blocking the UI waiting for the detector's synchronized native call.
        mediaPipeResultExecutor.execute {
            runCatching { faceDetectorService.close() }
                .onFailure { Log.w(tag, "Failed to close face detector", it) }
        }
        mediaPipeResultExecutor.shutdown()
        super.onCleared()
    }
}

private fun Bitmap.recycleIfNeeded() {
    if (!isRecycled) recycle()
}

/** Returns null for streams longer than [maxBytes], so a huge shared file cannot exhaust memory. */
private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        out.write(buffer, 0, n)
        if (out.size() > maxBytes) return null
    }
}
