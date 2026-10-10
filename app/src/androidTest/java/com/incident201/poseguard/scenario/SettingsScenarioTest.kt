package com.incident201.poseguard.scenario

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings are stored by enum constant name and read back with valueOf. This runs against the real
 * SharedPreferences and, in the release configuration, against R8-processed enums and data classes.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScenarioTest {
    private lateinit var app: Application
    private lateinit var snapshot: PreferencesSnapshot
    private val store = ViewModelStore()
    private var created = 0

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val prefs = app.getSharedPreferences("game_settings", Context.MODE_PRIVATE)
        snapshot = PreferencesSnapshot(prefs)
        check(prefs.edit().clear().commit())
    }

    @After fun tearDown() {
        onMain { store.clear() }
        snapshot.close()
    }

    private fun newModel(): GameViewModel {
        lateinit var model: GameViewModel
        onMain {
            model = GameViewModel(app)
            store.put("model-${created++}", model)
        }
        return model
    }

    @Test fun everyKindOfSettingSurvivesAProcessRestart() {
        val first = newModel()
        onMain {
            first.updateLanguage(AppLanguage.Russian)
            first.updateAccelerationMode(AccelerationMode.Cpu)
            first.updatePoseLandmarkerModel(PoseLandmarkerModel.Full)
            first.updateFaceCheckMode(FaceCheckMode.FaceToCamera)
            first.updateFaceDetectionConfidence(0.7f)
            first.updateDriftThresholdFactor(0.2f)
            first.updateMotionThresholdFactor(0.08f)
            first.updateMaxViolations(6)
            first.updateMinimumPenaltyIntervalSeconds(12)
            first.updatePenaltiesEnabled(false)
            first.updateFirstViolationPenaltyMinutes(7)
            first.updateTimelapseRecordingEnabled(false)
            first.updateDebugModeEnabled(true)
            first.updateCustomizeAudioEnabled(true)
            first.updateTtsVoiceMode(TtsVoiceMode.SystemVoice)
            first.updateTtsPhraseTemplate(TtsPhraseTemplate.PenaltyAddedToTimer, "Plus {minutes}")
            first.updateAudioCueSettings(
                AudioCue.TimeIsUp,
                AudioCueSettings(
                    mode = AudioCueMode.Pcm,
                    pcmSettings = PcmSignalSettings(660, 0.5f, PcmChannel.Right, 40, 10, 20, PcmPattern.DoubleBeep)
                )
            )
            first.updateIntifaceWebSocketUrl("ws://192.168.0.5:12345")
            first.updateIntifaceBackgroundMode(IntifaceBackgroundMode.Vibration)
            first.updateIntifaceBackgroundVibration(IntifaceVibrationSettings(0.4, IntifaceVibrationPattern.Pulse, 1.0, 0.3, 0.6))
            first.updateIntifaceViolationMode(IntifaceViolationMode.Pause)
            first.updateIntifaceViolationVibration(IntifaceVibrationSettings(0.9, IntifaceVibrationPattern.Constant, 3.0, 0.2, 0.2))
            first.updateIntifaceViolationPauseSeconds(2.0)
            first.updateTimerMode(TimerMode.Random)
            first.updateSelectedDurationSeconds(600)
            first.updateRandomDurationRangeSeconds(30, 75)
            first.markOnboardingCompleted()
        }
        assertNotEquals(GameSettings(), first.gameSettings.value)

        val second = newModel()
        assertEquals(first.gameSettings.value, second.gameSettings.value)
        assertEquals(TimerMode.Random, second.timerMode.value)
        assertEquals(600, second.selectedDurationSeconds.value)
        assertEquals(30, second.randomMinDurationSeconds.value)
        assertEquals(75, second.randomMaxDurationSeconds.value)
        assertEquals(true, second.onboardingCompleted.value)
    }

    @Test fun languageSelectionChangesTheTextOfStatusMessages() {
        val model = newModel()
        onMain { model.updateLanguage(AppLanguage.English) }
        val english = model.statusMessage.value
        AppLanguage.entries.filter { it != AppLanguage.English }.forEach { language ->
            onMain { model.updateLanguage(language) }
            assertNotEquals("$language still shows English text", english, model.statusMessage.value)
            assertEquals(language, newModel().gameSettings.value.language)
        }
    }
}
