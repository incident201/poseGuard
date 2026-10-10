package com.incident201.poseguard

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.audio.AudioCueMode
import com.incident201.poseguard.audio.AudioCueSettings
import com.incident201.poseguard.audio.PcmChannel
import com.incident201.poseguard.audio.PcmPattern
import com.incident201.poseguard.audio.PcmSignalSettings
import com.incident201.poseguard.audio.TtsPhraseTemplate
import com.incident201.poseguard.audio.TtsVoiceMode
import com.incident201.poseguard.intiface.IntifaceBackgroundMode
import com.incident201.poseguard.intiface.IntifaceVibrationPattern
import com.incident201.poseguard.intiface.IntifaceVibrationSettings
import com.incident201.poseguard.intiface.IntifaceViolationMode
import com.incident201.poseguard.tracker.AccelerationMode
import com.incident201.poseguard.tracker.PoseLandmarkerModel
import com.incident201.poseguard.viewmodel.AppLanguage
import com.incident201.poseguard.viewmodel.FaceCheckMode
import com.incident201.poseguard.viewmodel.GameSettings
import com.incident201.poseguard.viewmodel.GameViewModel
import com.incident201.poseguard.viewmodel.TimerMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

/**
 * Settings are the contract with data already stored on users' phones: every field has to survive
 * a process restart, legacy values have to migrate, and a damaged value must not crash startup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsPersistenceTest {
    private lateinit var app: Application
    private val store = ViewModelStore()
    private var created = 0

    private val prefs get() = app.getSharedPreferences("game_settings", Context.MODE_PRIVATE)

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        check(prefs.edit().clear().commit())
    }

    @After fun tearDown() {
        store.clear()
        check(prefs.edit().clear().commit())
    }

    /** A new ViewModel over the same SharedPreferences stands in for a fresh app process. */
    private fun newModel(): GameViewModel = GameViewModel(app).also { store.put("model-${created++}", it) }

    @Test fun freshInstallUsesTheDeclaredDefaults() {
        val model = newModel()
        assertEquals(GameSettings(), model.gameSettings.value)
        assertEquals(180, model.selectedDurationSeconds.value)
        assertEquals(TimerMode.Exact, model.timerMode.value)
        assertFalse(model.onboardingCompleted.value)
    }

    @Test fun everySettingSurvivesProcessRecreation() {
        val first = newModel()
        val exempt = customize(first)

        val second = newModel()
        assertEquals(first.gameSettings.value, second.gameSettings.value)
        assertEquals(first.selectedDurationSeconds.value, second.selectedDurationSeconds.value)
        assertEquals(TimerMode.Random, second.timerMode.value)
        assertEquals(45, second.randomMinDurationSeconds.value)
        assertEquals(90, second.randomMaxDurationSeconds.value)
        assertTrue(second.onboardingCompleted.value)

        // A setting added to GameSettings without being exercised here is a setting nobody has proven persists.
        val defaults = GameSettings()
        val settings = second.gameSettings.value
        val unverified = settingFields()
            .filter { field -> field.get(settings) == field.get(defaults) }
            .map { it.name }
            .toSet() - exempt
        assertEquals("Extend customize() so these settings are verified to persist: $unverified", emptySet<String>(), unverified)
    }

    @Test fun legacyIntifaceUrlMigratesAndCustomUrlsAreKept() {
        prefs.edit().putString("intiface_websocket_url", "ws://10.0.2.2:12345/buttplug").commit()
        assertEquals("ws://127.0.0.1:12345", newModel().gameSettings.value.intifaceWebSocketUrl)
        assertEquals("ws://127.0.0.1:12345", prefs.getString("intiface_websocket_url", null))

        prefs.edit().putString("intiface_websocket_url", "  ws://192.168.1.20:12345/buttplug ").commit()
        assertEquals("ws://192.168.1.20:12345/buttplug", newModel().gameSettings.value.intifaceWebSocketUrl)
    }

    @Test fun unknownEnumValuesFallBackToDefaultsInsteadOfCrashing() {
        val editor = prefs.edit()
        listOf(
            "face_mode", "app_language", "tts_voice_mode", "acceleration_mode", "pose_landmarker_model",
            "timer_mode", "intiface_background_mode", "intiface_violation_mode",
            "intiface_background_pattern", "intiface_violation_pattern"
        ).forEach { editor.putString(it, "RemovedInSomeFutureVersion") }
        AudioCue.entries.forEach {
            editor.putString("audio_cue_mode_${it.name}", "Gone")
            editor.putString("audio_cue_pcm_channel_${it.name}", "Gone")
            editor.putString("audio_cue_pcm_pattern_${it.name}", "Gone")
        }
        check(editor.commit())

        val model = newModel()
        assertEquals(GameSettings(), model.gameSettings.value)
        assertEquals(TimerMode.Exact, model.timerMode.value)
    }

    @Test fun storedNumbersAreClampedToTheirDocumentedRanges() {
        check(
            prefs.edit()
                .putInt("penalty_interval_sec", 999)
                .putInt("max_violations", -5)
                .putInt("penalty_1_min", 1_000_000)
                .putFloat("face_conf", Float.NaN)
                .putFloat("wrist_drift_weight", 5f)
                .putFloat("occlusion_freeze_visibility_hard", -1f)
                .putInt("selected_duration_seconds", 0)
                .putInt("random_min_duration_seconds", 90)
                .putInt("random_max_duration_seconds", 10)
                .putInt("audio_cue_pcm_frequency_TimeIsUp", 5)
                .putFloat("audio_cue_pcm_duration_TimeIsUp", Float.POSITIVE_INFINITY)
                .putInt("audio_cue_pcm_amplitude_TimeIsUp", 500)
                .commit()
        )
        val model = newModel()
        val settings = model.gameSettings.value
        assertEquals(30, settings.minimumPenaltyIntervalSeconds)
        assertEquals(0, settings.maxViolations)
        assertEquals(9999, settings.firstViolationPenaltyMinutes)
        assertEquals(0.8f, settings.faceDetectionConfidence, 0f)
        assertEquals(1f, settings.wristDriftWeight, 0f)
        assertEquals(0f, settings.occlusionFreezeVisibilityHard, 0f)
        assertEquals(1, model.selectedDurationSeconds.value)
        assertEquals(90, model.randomMinDurationSeconds.value)
        assertEquals(90, model.randomMaxDurationSeconds.value)
        val pcm = settings.audioCueSettings.getValue(AudioCue.TimeIsUp).pcmSettings
        assertEquals(20, pcm.frequencyHz)
        assertEquals(PcmSignalSettings().durationSeconds, pcm.durationSeconds, 0f)
        assertEquals(100, pcm.amplitudePercent)
    }

    @Test fun legacySensitivityPresetsMigrateOnceAndOffPresetValuesSnapToDefaults() {
        check(prefs.edit().putFloat("pose_drift_factor_v2", 0.12f).putFloat("pose_motion_factor_v2", 0.06f).commit())
        var settings = newModel().gameSettings.value
        assertEquals(0.160f, settings.driftThresholdFactor, 0f)
        assertEquals(0.04f, settings.motionThresholdFactor, 0f)
        assertEquals(2, prefs.getInt("sensitivity_presets_version", -1))

        // After the migration 0.12 is simply the strictest preset and must be left alone.
        check(prefs.edit().putFloat("pose_drift_factor_v2", 0.12f).putFloat("pose_motion_factor_v2", 0.02f).commit())
        settings = newModel().gameSettings.value
        assertEquals(0.12f, settings.driftThresholdFactor, 0f)
        assertEquals(0.02f, settings.motionThresholdFactor, 0f)

        check(prefs.edit().putFloat("pose_drift_factor_v2", 0.173f).putFloat("pose_motion_factor_v2", 0.051f).commit())
        settings = newModel().gameSettings.value
        assertEquals(0.160f, settings.driftThresholdFactor, 0f)
        assertEquals(0.04f, settings.motionThresholdFactor, 0f)
    }

    @Test fun customPhrasesPreferCurrentKeysOverLegacyAndRejectUnusablePenaltyTemplate() {
        check(
            prefs.edit()
                .putString("audio_cue_tts_text_TakePosition", "legacy take position")
                .putString("audio_cue_tts_text_TimeIsUp", "legacy time is up")
                .putString("tts_template_TimeIsUp", "current time is up")
                .putString("tts_template_DefeatTryAgain", "   ")
                .commit()
        )
        val model = newModel()
        val templates = model.gameSettings.value.customTtsTemplates
        assertEquals("legacy take position", templates[TtsPhraseTemplate.TakePosition])
        assertEquals("current time is up", templates[TtsPhraseTemplate.TimeIsUp])
        assertFalse(TtsPhraseTemplate.DefeatTryAgain in templates)

        model.updateTtsPhraseTemplate(TtsPhraseTemplate.PenaltyAddedToTimer, "no placeholder here")
        assertFalse(TtsPhraseTemplate.PenaltyAddedToTimer in model.gameSettings.value.customTtsTemplates)
        model.updateTtsPhraseTemplate(TtsPhraseTemplate.PenaltyAddedToTimer, "plus {minutes} minutes")
        assertEquals("plus {minutes} minutes", newModel().gameSettings.value.customTtsTemplates[TtsPhraseTemplate.PenaltyAddedToTimer])

        model.updateTtsPhraseTemplate(TtsPhraseTemplate.TakePosition, null)
        assertFalse(TtsPhraseTemplate.TakePosition in newModel().gameSettings.value.customTtsTemplates)
    }

    @Test fun updatesNormalizeOutOfRangeInputBeforeStoringIt() {
        val model = newModel()
        model.updateMaxViolations(-3)
        model.updateMinimumPenaltyIntervalSeconds(500)
        model.updateFaceDetectionConfidence(0.1f)
        model.updateIntifaceViolationPauseSeconds(1_000.0)
        model.updateSelectedDurationSeconds(-10)
        model.updateRandomDurationRangeSeconds(120, 30)

        val reloaded = newModel()
        val settings = reloaded.gameSettings.value
        assertEquals(0, settings.maxViolations)
        assertEquals(30, settings.minimumPenaltyIntervalSeconds)
        assertEquals(0.5f, settings.faceDetectionConfidence, 0f)
        assertEquals(60.0, settings.intifaceViolationPauseSeconds, 0.0)
        assertEquals(1, reloaded.selectedDurationSeconds.value)
        assertEquals(120, reloaded.randomMinDurationSeconds.value)
        assertEquals(120, reloaded.randomMaxDurationSeconds.value)
    }

    private fun settingFields() = GameSettings::class.java.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && it.name != "\$stable" }
        .onEach { it.isAccessible = true }

    /**
     * Changes every setting to a value that differs from its default. Returns the settings that cannot
     * be changed without a live Intiface server and are therefore verified elsewhere.
     */
    private fun customize(model: GameViewModel): Set<String> {
        model.updateLanguage(AppLanguage.German)
        model.updateAccelerationMode(AccelerationMode.Cpu)
        model.updatePoseLandmarkerModel(PoseLandmarkerModel.Full)
        model.updateFaceCheckMode(FaceCheckMode.FaceAwayFromCamera)
        model.updateFaceDetectionConfidence(0.65f)
        model.updateDriftThresholdFactor(0.2f)
        model.updateMotionThresholdFactor(0.08f)
        model.updateMinimumPenaltyIntervalSeconds(9)
        model.updateMaxViolations(7)
        model.updatePenaltiesEnabled(false)
        model.updateFirstViolationPenaltyMinutes(2)
        model.updateSecondViolationPenaltyMinutes(4)
        model.updateThirdViolationPenaltyMinutes(5)
        model.updateSubsequentViolationPenaltyMinutes(6)
        model.updateTimelapseRecordingEnabled(false)
        model.updateOcclusionFreezeVisibilityAlways(0.03f)
        model.updateOcclusionFreezeVisibilityP10Always(0.04f)
        model.updateOcclusionFreezeVisibilityHard(0.06f)
        model.updateOcclusionFreezeVisibilitySoft(0.15f)
        model.updateOcclusionJitterFreezeThreshold(0.2f)
        model.updateWristDriftWeight(0.3f)
        model.updatePoseSmootherMinCutoff(1.5f)
        model.updatePoseSmootherBeta(0.05f)
        model.updatePoseSmootherDerivativeCutoff(2.5f)
        model.updateDebugModeEnabled(true)
        model.updateCustomizeAudioEnabled(true)
        model.updateTtsVoiceMode(TtsVoiceMode.SystemVoice)
        model.updateTtsPhraseTemplate(TtsPhraseTemplate.TimeIsUp, "Well done")
        model.updateAudioCueSettings(
            AudioCue.DriftViolation,
            AudioCueSettings(
                mode = AudioCueMode.Pcm,
                audioFileUri = "content://media/external/audio/media/42",
                pcmSettings = PcmSignalSettings(
                    frequencyHz = 880, durationSeconds = 0.4f, channel = PcmChannel.Left,
                    amplitudePercent = 55, fadeInMs = 20, fadeOutMs = 30, pattern = PcmPattern.DoubleBeep
                )
            )
        )
        model.updateIntifaceWebSocketUrl("ws://192.168.0.10:12345")
        model.updateIntifaceBackgroundMode(IntifaceBackgroundMode.Vibration)
        model.updateIntifaceBackgroundVibration(
            IntifaceVibrationSettings(0.35, IntifaceVibrationPattern.Pulse, 1.0, 0.3, 0.4)
        )
        model.updateIntifaceViolationMode(IntifaceViolationMode.Pause)
        model.updateIntifaceViolationVibration(
            IntifaceVibrationSettings(0.55, IntifaceVibrationPattern.Constant, 2.5, 0.2, 0.2)
        )
        model.updateIntifaceViolationPauseSeconds(2.5)
        model.updateTimerMode(TimerMode.Random)
        model.updateSelectedDurationSeconds(420)
        model.updateRandomDurationRangeSeconds(45, 90)
        model.markOnboardingCompleted()
        // Enabling the connection only persists on the online flavor; the offline stub reports "unsupported".
        model.updateIntifaceConnectionEnabled(true)
        // The selected device is written by the connection flow itself (IntifaceRestartScenarioTest, on device).
        return setOf("intifaceSelectedDeviceName", "intifaceSelectedDeviceDisplayName", "intifaceSelectedDeviceIndex") +
            if (model.gameSettings.value.intifaceConnectionEnabled) emptySet() else setOf("intifaceConnectionEnabled")
    }
}
