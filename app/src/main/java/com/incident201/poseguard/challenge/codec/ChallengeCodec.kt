package com.incident201.poseguard.challenge.codec

import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.audio.AudioCueMode
import com.incident201.poseguard.audio.AudioCueSettings
import com.incident201.poseguard.audio.PcmChannel
import com.incident201.poseguard.audio.PcmPattern
import com.incident201.poseguard.audio.PcmSignalSettings
import com.incident201.poseguard.audio.TtsPhraseTemplate
import com.incident201.poseguard.challenge.AudioClip
import com.incident201.poseguard.challenge.Blob
import com.incident201.poseguard.challenge.ChallengeRules
import com.incident201.poseguard.challenge.ChallengeSpec
import com.incident201.poseguard.challenge.ChallengeVisibility
import com.incident201.poseguard.challenge.TimerSpec
import com.incident201.poseguard.intiface.IntifaceBackgroundMode
import com.incident201.poseguard.intiface.IntifaceVibrationPattern
import com.incident201.poseguard.intiface.IntifaceVibrationSettings
import com.incident201.poseguard.intiface.IntifaceViolationMode
import com.incident201.poseguard.viewmodel.FaceCheckMode
import com.incident201.poseguard.viewmodel.TimerMode

/** TLV layout of [ChallengeSpec]. Tags are part of the file format: never reuse or renumber them. */
object ChallengeCodec {
    private const val ID = 1
    private const val TITLE = 2
    private const val DESCRIPTION = 3
    private const val AUTHOR = 4
    private const val CREATED_AT = 5
    private const val RULES = 6
    // 7: per-group visibility of early builds, no longer read.
    private const val ATTEMPTS = 8
    private const val SECRET = 9
    private const val PUBLIC_KEY = 10
    private const val SHOW_DURATION = 11
    private const val SHOW_PENALTIES = 12

    private const val R_TIMER = 1
    private const val R_MAX_VIOLATIONS = 2
    private const val R_PENALTY_INTERVAL = 3
    private const val R_PENALTIES_ENABLED = 4
    private const val R_PENALTY_1 = 5
    private const val R_PENALTY_2 = 6
    private const val R_PENALTY_3 = 7
    private const val R_PENALTY_N = 8
    private const val R_DRIFT = 9
    private const val R_MOTION = 10
    private const val R_WRIST = 11
    private const val R_FACE_MODE = 12
    private const val R_FACE_CONFIDENCE = 13
    private const val R_CUSTOM_AUDIO = 14
    private const val R_TTS_TEMPLATE = 15
    private const val R_AUDIO_CUE = 16
    private const val R_BACKGROUND_MODE = 17
    private const val R_BACKGROUND_VIBRATION = 18
    private const val R_VIOLATION_MODE = 19
    private const val R_VIOLATION_VIBRATION = 20
    private const val R_VIOLATION_PAUSE = 21
    private const val R_INTIFACE_REQUIRED = 22

    fun encode(spec: ChallengeSpec): ByteArray = TlvWriter().apply {
        bytes(ID, spec.id.bytes)
        string(TITLE, spec.title)
        string(DESCRIPTION, spec.description)
        string(AUTHOR, spec.authorName)
        long(CREATED_AT, spec.createdAtMs)
        message(RULES) { writeRules(spec.rules) }
        bool(SHOW_DURATION, spec.visibility.showDuration)
        bool(SHOW_PENALTIES, spec.visibility.showPenalties)
        int(ATTEMPTS, spec.attemptsAllowed)
        bytes(SECRET, spec.taskSecret.bytes)
        bytes(PUBLIC_KEY, spec.authorPublicKey.bytes)
    }.toByteArray()

    fun decode(data: ByteArray): ChallengeSpec {
        val r = TlvReader(data)
        val id = r.requireBytes(ID)
        val secret = r.requireBytes(SECRET)
        val publicKey = r.requireBytes(PUBLIC_KEY)
        try {
            com.incident201.poseguard.challenge.crypto.Envelope.publicKey(publicKey)
        } catch (e: Exception) {
            throw TlvFormatException("Invalid author key")
        }
        if (id.size != ChallengeSpec.ID_SIZE || secret.size != ChallengeSpec.SECRET_SIZE) {
            throw TlvFormatException("Bad challenge identity")
        }
        val visibility = ChallengeVisibility(
            showDuration = r.bool(SHOW_DURATION) ?: true,
            showPenalties = r.bool(SHOW_PENALTIES) ?: true
        )
        return ChallengeSpec(
            id = Blob(id),
            title = r.string(TITLE).orEmpty().take(ChallengeSpec.MAX_TITLE_LENGTH),
            description = r.string(DESCRIPTION).orEmpty().take(ChallengeSpec.MAX_DESCRIPTION_LENGTH),
            authorName = r.string(AUTHOR).orEmpty().take(ChallengeSpec.MAX_AUTHOR_LENGTH),
            createdAtMs = r.long(CREATED_AT) ?: 0L,
            rules = readRules(r.message(RULES) ?: throw TlvFormatException("Missing rules")),
            visibility = visibility,
            attemptsAllowed = (r.int(ATTEMPTS) ?: 0).coerceIn(0, 99),
            taskSecret = Blob(secret),
            authorPublicKey = Blob(publicKey)
        )
    }

    private fun TlvWriter.writeRules(rules: ChallengeRules) {
        message(R_TIMER) {
            enum(1, rules.timer.mode)
            int(2, rules.timer.exactSeconds)
            int(3, rules.timer.randomMinSeconds)
            int(4, rules.timer.randomMaxSeconds)
        }
        int(R_MAX_VIOLATIONS, rules.maxViolations)
        int(R_PENALTY_INTERVAL, rules.minimumPenaltyIntervalSeconds)
        bool(R_PENALTIES_ENABLED, rules.penaltiesEnabled)
        int(R_PENALTY_1, rules.firstViolationPenaltyMinutes)
        int(R_PENALTY_2, rules.secondViolationPenaltyMinutes)
        int(R_PENALTY_3, rules.thirdViolationPenaltyMinutes)
        int(R_PENALTY_N, rules.subsequentViolationPenaltyMinutes)
        float(R_DRIFT, rules.driftThresholdFactor)
        float(R_MOTION, rules.motionThresholdFactor)
        float(R_WRIST, rules.wristDriftWeight)
        enum(R_FACE_MODE, rules.faceCheckMode)
        float(R_FACE_CONFIDENCE, rules.faceDetectionConfidence)
        bool(R_CUSTOM_AUDIO, rules.customizeAudioEnabled)
        rules.customTtsTemplates.forEach { (template, text) ->
            message(R_TTS_TEMPLATE) {
                enum(1, template)
                string(2, text)
            }
        }
        rules.audioCueSettings.forEach { (cue, settings) ->
            message(R_AUDIO_CUE) {
                enum(1, cue)
                enum(2, settings.mode)
                val pcm = settings.pcmSettings
                int(3, pcm.frequencyHz)
                float(4, pcm.durationSeconds)
                enum(5, pcm.channel)
                int(6, pcm.amplitudePercent)
                int(7, pcm.fadeInMs)
                int(8, pcm.fadeOutMs)
                enum(9, pcm.pattern)
                rules.audioClips[cue]?.let { clip ->
                    string(10, clip.mimeType)
                    bytes(11, clip.data.bytes)
                }
            }
        }
        enum(R_BACKGROUND_MODE, rules.intifaceBackgroundMode)
        message(R_BACKGROUND_VIBRATION) { writeVibration(rules.intifaceBackgroundVibration) }
        enum(R_VIOLATION_MODE, rules.intifaceViolationMode)
        message(R_VIOLATION_VIBRATION) { writeVibration(rules.intifaceViolationVibration) }
        double(R_VIOLATION_PAUSE, rules.intifaceViolationPauseSeconds)
        bool(R_INTIFACE_REQUIRED, rules.intifaceRequired)
    }

    private fun readRules(r: TlvReader): ChallengeRules {
        val defaults = ChallengeRules()
        val timer = r.message(R_TIMER)?.let { t ->
            TimerSpec(
                mode = t.enum(1, TimerMode.Exact),
                exactSeconds = t.int(2) ?: defaults.timer.exactSeconds,
                randomMinSeconds = t.int(3) ?: defaults.timer.randomMinSeconds,
                randomMaxSeconds = t.int(4) ?: defaults.timer.randomMaxSeconds
            )
        } ?: defaults.timer
        val templates = r.repeated(R_TTS_TEMPLATE).mapNotNull { entry ->
            val t = TlvReader(entry)
            val name = t.string(1) ?: return@mapNotNull null
            val template = TtsPhraseTemplate.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            template to t.string(2).orEmpty()
        }.toMap()
        val clips = mutableMapOf<AudioCue, AudioClip>()
        val cues = r.repeated(R_AUDIO_CUE).mapNotNull { entry ->
            val c = TlvReader(entry)
            val name = c.string(1) ?: return@mapNotNull null
            val cue = AudioCue.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            val pcmDefaults = PcmSignalSettings()
            c.bytes(11)?.let { data -> clips[cue] = AudioClip(c.string(10).orEmpty(), Blob(data)) }
            cue to AudioCueSettings(
                mode = c.enum(2, AudioCueMode.UseTts),
                pcmSettings = PcmSignalSettings(
                    frequencyHz = (c.int(3) ?: pcmDefaults.frequencyHz).coerceIn(20, 20_000),
                    durationSeconds = (c.float(4)?.takeIf(Float::isFinite) ?: pcmDefaults.durationSeconds)
                        .coerceIn(0.05f, 10f),
                    channel = c.enum(5, PcmChannel.Both),
                    amplitudePercent = (c.int(6) ?: pcmDefaults.amplitudePercent).coerceIn(0, 100),
                    fadeInMs = (c.int(7) ?: 0).coerceIn(0, 5_000),
                    fadeOutMs = (c.int(8) ?: 0).coerceIn(0, 5_000),
                    pattern = c.enum(9, PcmPattern.SingleTone)
                )
            )
        }.toMap()
        return ChallengeRules(
            timer = timer,
            maxViolations = r.int(R_MAX_VIOLATIONS) ?: defaults.maxViolations,
            minimumPenaltyIntervalSeconds = r.int(R_PENALTY_INTERVAL) ?: defaults.minimumPenaltyIntervalSeconds,
            penaltiesEnabled = r.bool(R_PENALTIES_ENABLED) ?: defaults.penaltiesEnabled,
            firstViolationPenaltyMinutes = r.int(R_PENALTY_1) ?: defaults.firstViolationPenaltyMinutes,
            secondViolationPenaltyMinutes = r.int(R_PENALTY_2) ?: defaults.secondViolationPenaltyMinutes,
            thirdViolationPenaltyMinutes = r.int(R_PENALTY_3) ?: defaults.thirdViolationPenaltyMinutes,
            subsequentViolationPenaltyMinutes = r.int(R_PENALTY_N) ?: defaults.subsequentViolationPenaltyMinutes,
            driftThresholdFactor = r.float(R_DRIFT) ?: defaults.driftThresholdFactor,
            motionThresholdFactor = r.float(R_MOTION) ?: defaults.motionThresholdFactor,
            wristDriftWeight = r.float(R_WRIST) ?: defaults.wristDriftWeight,
            faceCheckMode = r.enum(R_FACE_MODE, FaceCheckMode.Disabled),
            faceDetectionConfidence = r.float(R_FACE_CONFIDENCE) ?: defaults.faceDetectionConfidence,
            customizeAudioEnabled = r.bool(R_CUSTOM_AUDIO) ?: false,
            customTtsTemplates = templates,
            audioCueSettings = defaults.audioCueSettings + cues,
            audioClips = clips,
            intifaceBackgroundMode = r.enum(R_BACKGROUND_MODE, IntifaceBackgroundMode.Off),
            intifaceBackgroundVibration = r.message(R_BACKGROUND_VIBRATION)?.readVibration()
                ?: defaults.intifaceBackgroundVibration,
            intifaceViolationMode = r.enum(R_VIOLATION_MODE, IntifaceViolationMode.Off),
            intifaceViolationVibration = r.message(R_VIOLATION_VIBRATION)?.readVibration()
                ?: defaults.intifaceViolationVibration,
            intifaceViolationPauseSeconds = r.double(R_VIOLATION_PAUSE) ?: defaults.intifaceViolationPauseSeconds,
            intifaceRequired = r.bool(R_INTIFACE_REQUIRED) ?: false
        ).normalized()
    }

    private fun TlvWriter.writeVibration(v: IntifaceVibrationSettings) {
        double(1, v.strength)
        enum(2, v.pattern)
        double(3, v.durationSeconds)
        double(4, v.pulseLengthSeconds)
        double(5, v.pulsePauseSeconds)
    }

    private fun TlvReader.readVibration(): IntifaceVibrationSettings {
        val d = IntifaceVibrationSettings()
        return IntifaceVibrationSettings(
            strength = double(1) ?: d.strength,
            pattern = enum(2, IntifaceVibrationPattern.Constant),
            durationSeconds = double(3) ?: d.durationSeconds,
            pulseLengthSeconds = double(4) ?: d.pulseLengthSeconds,
            pulsePauseSeconds = double(5) ?: d.pulsePauseSeconds
        )
    }
}
