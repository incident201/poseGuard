package com.incident201.poseguard.challenge

import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.audio.AudioCueMode
import com.incident201.poseguard.audio.AudioCueSettings
import com.incident201.poseguard.audio.TtsPhraseTemplate
import com.incident201.poseguard.intiface.IntifaceBackgroundMode
import com.incident201.poseguard.intiface.IntifaceVibrationSettings
import com.incident201.poseguard.intiface.IntifaceViolationMode
import com.incident201.poseguard.tracker.PoseSmoother
import com.incident201.poseguard.viewmodel.FaceCheckMode
import com.incident201.poseguard.viewmodel.GameSettings
import com.incident201.poseguard.viewmodel.TimerMode

/** Byte array with value semantics, so models that hold keys and ids stay comparable. */
class Blob(val bytes: ByteArray) {
    val size: Int get() = bytes.size
    fun toHex(): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    override fun equals(other: Any?): Boolean = other is Blob && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = "Blob(${toHex()})"

    companion object {
        fun fromHex(hex: String): Blob? {
            if (hex.length % 2 != 0) return null
            return runCatching {
                Blob(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
            }.getOrNull()
        }
    }
}

/**
 * What the executor sees before and during the session. Everything not listed here is shown
 * to the executor in short form (sensitivity level, face rule, whether custom sounds are used).
 */
data class ChallengeVisibility(
    val showDuration: Boolean = true,
    val showPenalties: Boolean = true
)

/** An audio file embedded in the challenge, so it plays on the executor's device. */
data class AudioClip(val mimeType: String, val data: Blob) {
    override fun toString(): String = "AudioClip($mimeType, ${data.size} bytes)"

    companion object {
        const val MAX_BYTES = 1024 * 1024
        const val MAX_TOTAL_BYTES = 4 * 1024 * 1024
        const val MAX_MIME_LENGTH = 100
    }
}

data class TimerSpec(
    val mode: TimerMode = TimerMode.Exact,
    val exactSeconds: Int = 180,
    val randomMinSeconds: Int = 180,
    val randomMaxSeconds: Int = 180
) {
    val minSeconds: Int get() = if (mode == TimerMode.Exact) exactSeconds else randomMinSeconds
    val maxSeconds: Int get() = if (mode == TimerMode.Exact) exactSeconds else randomMaxSeconds

    fun normalized(): TimerSpec {
        val exact = exactSeconds.coerceIn(1, MAX_CHALLENGE_SECONDS)
        val min = randomMinSeconds.coerceIn(1, MAX_CHALLENGE_SECONDS)
        return copy(
            exactSeconds = exact,
            randomMinSeconds = min,
            randomMaxSeconds = randomMaxSeconds.coerceIn(min, MAX_CHALLENGE_SECONDS)
        )
    }
}

const val MAX_CHALLENGE_SECONDS = 24 * 60 * 60

/** Everything the author decides. The executor's own device, voice and language settings stay theirs. */
data class ChallengeRules(
    val timer: TimerSpec = TimerSpec(),
    val maxViolations: Int = 4,
    val minimumPenaltyIntervalSeconds: Int = 5,
    val penaltiesEnabled: Boolean = true,
    val firstViolationPenaltyMinutes: Int = 1,
    val secondViolationPenaltyMinutes: Int = 3,
    val thirdViolationPenaltyMinutes: Int = 3,
    val subsequentViolationPenaltyMinutes: Int = 3,
    val driftThresholdFactor: Float = 0.160f,
    val motionThresholdFactor: Float = 0.04f,
    val wristDriftWeight: Float = 0.7f,
    val faceCheckMode: FaceCheckMode = FaceCheckMode.Disabled,
    val faceDetectionConfidence: Float = 0.8f,
    val customizeAudioEnabled: Boolean = false,
    val customTtsTemplates: Map<TtsPhraseTemplate, String> = emptyMap(),
    val audioCueSettings: Map<AudioCue, AudioCueSettings> = AudioCue.entries.associateWith { AudioCueSettings() },
    /** Files for the cues in [AudioCueMode.AudioFile] mode; a task with clips travels only as a file. */
    val audioClips: Map<AudioCue, AudioClip> = emptyMap(),
    val intifaceBackgroundMode: IntifaceBackgroundMode = IntifaceBackgroundMode.Off,
    val intifaceBackgroundVibration: IntifaceVibrationSettings = GameSettings().intifaceBackgroundVibration,
    val intifaceViolationMode: IntifaceViolationMode = IntifaceViolationMode.Off,
    val intifaceViolationVibration: IntifaceVibrationSettings = GameSettings().intifaceViolationVibration,
    val intifaceViolationPauseSeconds: Double = 1.0,
    val intifaceRequired: Boolean = false
) {
    val usesIntiface: Boolean
        get() = intifaceBackgroundMode != IntifaceBackgroundMode.Off ||
            intifaceViolationMode != IntifaceViolationMode.Off

    val usesCustomSounds: Boolean
        get() = audioCueSettings.values.any { it.mode != AudioCueMode.UseTts } || customTtsTemplates.isNotEmpty()

    /** Values the editor and the decoder both accept, so a file cannot carry out-of-range rules. */
    fun normalized(): ChallengeRules {
        val values = normalizedValues()
        val clips = portableClips()
        val cues = AudioCue.entries.associateWith { cue ->
            val cueSettings = (audioCueSettings[cue] ?: AudioCueSettings()).copy(audioFileUri = null)
            // A file cue without its file falls back to the spoken phrase.
            if (cueSettings.mode == AudioCueMode.AudioFile && cue !in clips) cueSettings.copy(mode = AudioCueMode.UseTts)
            else cueSettings
        }
        return values.copy(
            audioCueSettings = cues,
            audioClips = clips,
            customizeAudioEnabled = cues.values.any { it.mode != AudioCueMode.UseTts } || values.customTtsTemplates.isNotEmpty()
        )
    }

    private fun portableClips(): Map<AudioCue, AudioClip> {
        var total = 0
        return audioClips.filter { (cue, clip) ->
            val keep = audioCueSettings[cue]?.mode == AudioCueMode.AudioFile &&
                clip.data.size in 1..AudioClip.MAX_BYTES &&
                clip.mimeType.length <= AudioClip.MAX_MIME_LENGTH &&
                total + clip.data.size <= AudioClip.MAX_TOTAL_BYTES
            if (keep) total += clip.data.size
            keep
        }
    }

    private fun normalizedValues(): ChallengeRules = copy(
        timer = timer.normalized(),
        maxViolations = maxViolations.coerceIn(0, 9999),
        minimumPenaltyIntervalSeconds = minimumPenaltyIntervalSeconds.coerceIn(0, 30),
        firstViolationPenaltyMinutes = firstViolationPenaltyMinutes.coerceIn(0, 9999),
        secondViolationPenaltyMinutes = secondViolationPenaltyMinutes.coerceIn(0, 9999),
        thirdViolationPenaltyMinutes = thirdViolationPenaltyMinutes.coerceIn(0, 9999),
        subsequentViolationPenaltyMinutes = subsequentViolationPenaltyMinutes.coerceIn(0, 9999),
        driftThresholdFactor = driftThresholdFactor.finiteOr(0.160f).coerceIn(0.05f, 0.40f),
        motionThresholdFactor = motionThresholdFactor.finiteOr(0.04f).coerceIn(0.02f, 0.25f),
        wristDriftWeight = wristDriftWeight.finiteOr(0.7f).coerceIn(0f, 1f),
        faceDetectionConfidence = faceDetectionConfidence.finiteOr(0.8f).coerceIn(0.5f, 0.95f),
        customTtsTemplates = customTtsTemplates
            .mapValues { it.value.take(MAX_PHRASE_LENGTH) }
            .filter { (template, text) ->
                text.isNotBlank() && (template != TtsPhraseTemplate.PenaltyAddedToTimer || text.contains("{minutes}"))
            },
        intifaceBackgroundVibration = intifaceBackgroundVibration.normalizedForChallenge(),
        intifaceViolationVibration = intifaceViolationVibration.normalizedForChallenge(),
        intifaceViolationPauseSeconds = intifaceViolationPauseSeconds.finiteOr(1.0).coerceIn(0.05, 60.0)
    )

    companion object {
        const val MAX_PHRASE_LENGTH = 300

        /**
         * The editor's "copy from my settings": the rules a session with [settings] would use.
         * Audio files picked in the settings are passed in [clips] by the caller that read them;
         * tuning only visible in debug mode keeps its default.
         */
        fun from(
            settings: GameSettings,
            timer: TimerSpec,
            clips: Map<AudioCue, AudioClip> = emptyMap()
        ): ChallengeRules = ChallengeRules(
            timer = timer,
            maxViolations = settings.maxViolations,
            minimumPenaltyIntervalSeconds = settings.minimumPenaltyIntervalSeconds,
            penaltiesEnabled = settings.penaltiesEnabled,
            firstViolationPenaltyMinutes = settings.firstViolationPenaltyMinutes,
            secondViolationPenaltyMinutes = settings.secondViolationPenaltyMinutes,
            thirdViolationPenaltyMinutes = settings.thirdViolationPenaltyMinutes,
            subsequentViolationPenaltyMinutes = settings.subsequentViolationPenaltyMinutes,
            driftThresholdFactor = settings.driftThresholdFactor,
            motionThresholdFactor = settings.motionThresholdFactor,
            faceCheckMode = settings.faceCheckMode,
            customTtsTemplates = if (settings.customizeAudioEnabled) settings.customTtsTemplates else emptyMap(),
            audioCueSettings = if (settings.customizeAudioEnabled) settings.audioCueSettings
                else AudioCue.entries.associateWith { AudioCueSettings() },
            audioClips = clips,
            intifaceBackgroundMode = settings.intifaceBackgroundMode,
            intifaceBackgroundVibration = settings.intifaceBackgroundVibration,
            intifaceViolationMode = settings.intifaceViolationMode,
            intifaceViolationVibration = settings.intifaceViolationVibration,
            intifaceViolationPauseSeconds = settings.intifaceViolationPauseSeconds
        ).normalized()
    }
}

private fun Float.finiteOr(default: Float): Float = if (isFinite()) this else default
private fun Double.finiteOr(default: Double): Double = if (isFinite()) this else default

private fun IntifaceVibrationSettings.normalizedForChallenge() = copy(
    strength = strength.finiteOr(0.5).coerceIn(0.0, 1.0),
    durationSeconds = durationSeconds.finiteOr(1.0).coerceIn(0.05, 60.0),
    pulseLengthSeconds = pulseLengthSeconds.finiteOr(0.25).coerceIn(0.05, 10.0),
    pulsePauseSeconds = pulsePauseSeconds.finiteOr(0.25).coerceIn(0.05, 10.0)
)

data class ChallengeSpec(
    val id: Blob,
    val title: String,
    val description: String,
    val authorName: String,
    val createdAtMs: Long,
    val rules: ChallengeRules,
    val visibility: ChallengeVisibility,
    val attemptsAllowed: Int,
    /** Known only to the apps of the author and the executor; the report MAC is keyed with it. */
    val taskSecret: Blob,
    /** X.509-encoded P-256 key; reports are encrypted to it. */
    val authorPublicKey: Blob
) {
    companion object {
        const val ID_SIZE = 16
        const val SECRET_SIZE = 32
        const val MAX_TITLE_LENGTH = 80
        const val MAX_DESCRIPTION_LENGTH = 1000
        const val MAX_AUTHOR_LENGTH = 40
    }
}

/**
 * Session settings while a challenge runs: the author's rules over the executor's own settings.
 * Tuning that the challenge cannot verify is reset to defaults and debug tools are off.
 * [audioFiles] are playable URIs of the embedded clips; a cue without one is spoken instead.
 */
fun ChallengeRules.applyTo(base: GameSettings, audioFiles: Map<AudioCue, String> = emptyMap()): GameSettings {
    val defaults = GameSettings()
    return base.copy(
        faceCheckMode = faceCheckMode,
        faceDetectionConfidence = faceDetectionConfidence,
        driftThresholdFactor = driftThresholdFactor,
        motionThresholdFactor = motionThresholdFactor,
        minimumPenaltyIntervalSeconds = minimumPenaltyIntervalSeconds,
        maxViolations = maxViolations,
        penaltiesEnabled = penaltiesEnabled,
        firstViolationPenaltyMinutes = firstViolationPenaltyMinutes,
        secondViolationPenaltyMinutes = secondViolationPenaltyMinutes,
        thirdViolationPenaltyMinutes = thirdViolationPenaltyMinutes,
        subsequentViolationPenaltyMinutes = subsequentViolationPenaltyMinutes,
        occlusionFreezeVisibilityAlways = defaults.occlusionFreezeVisibilityAlways,
        occlusionFreezeVisibilityP10Always = defaults.occlusionFreezeVisibilityP10Always,
        occlusionFreezeVisibilityHard = defaults.occlusionFreezeVisibilityHard,
        occlusionFreezeVisibilitySoft = defaults.occlusionFreezeVisibilitySoft,
        occlusionJitterFreezeThreshold = defaults.occlusionJitterFreezeThreshold,
        wristDriftWeight = wristDriftWeight,
        poseSmootherMinCutoff = PoseSmoother.DEFAULT_MIN_CUTOFF,
        poseSmootherBeta = PoseSmoother.DEFAULT_BETA,
        poseSmootherDerivativeCutoff = PoseSmoother.DEFAULT_DERIVATIVE_CUTOFF,
        debugModeEnabled = false,
        customizeAudioEnabled = customizeAudioEnabled,
        customTtsTemplates = customTtsTemplates,
        audioCueSettings = audioCueSettings.mapValues { (cue, cueSettings) ->
            when {
                cueSettings.mode != AudioCueMode.AudioFile -> cueSettings
                audioFiles[cue] != null -> cueSettings.copy(audioFileUri = audioFiles[cue])
                else -> cueSettings.copy(mode = AudioCueMode.UseTts)
            }
        },
        intifaceBackgroundMode = intifaceBackgroundMode,
        intifaceBackgroundVibration = intifaceBackgroundVibration,
        intifaceViolationMode = intifaceViolationMode,
        intifaceViolationVibration = intifaceViolationVibration,
        intifaceViolationPauseSeconds = intifaceViolationPauseSeconds
    )
}
