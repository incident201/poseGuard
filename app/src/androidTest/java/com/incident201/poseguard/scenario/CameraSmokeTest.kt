package com.incident201.poseguard.scenario

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import com.incident201.poseguard.R
import com.incident201.poseguard.viewmodel.GameState
import com.incident201.poseguard.MainActivity
import com.incident201.poseguard.viewmodel.GameViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class CameraSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun awaitUi(description: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        // Advance Compose frames while waiting for asynchronous CameraX/MediaPipe state updates.
        try { compose.waitUntil(timeoutMillis = timeoutMs, condition = condition) }
        catch (failure: Throwable) { throw AssertionError("Timed out waiting for $description", failure) }
    }
    @Test fun cameraAndRealMediaPipeProduceFramesAfterActivityRecreation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("game_settings", Context.MODE_PRIVATE)
        PreferencesSnapshot(prefs).use {
            check(prefs.edit().clear()
                .putBoolean("onboarding_completed", true)
                .putBoolean("timelapse_recording_enabled", false)
                .putString("acceleration_mode", "Cpu")
                .putString("pose_landmarker_model", "Full")
                .commit())
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                app.packageName, Manifest.permission.CAMERA
            )
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: GameViewModel
                scenario.onActivity { model = ViewModelProvider(it)[GameViewModel::class.java] }
                awaitUi("real camera and MediaPipe result", 30_000) {
                    model.poseOverlayState.value.imageWidth > 0
                }
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.recreate()
                scenario.onActivity { model = ViewModelProvider(it)[GameViewModel::class.java] }
                awaitUi("camera resumed after recreation", 30_000) {
                    model.poseOverlayState.value.imageWidth > 0
                }
                assertEquals(com.incident201.poseguard.viewmodel.GameState.Idle, model.gameState.value)
            }
        }
    }

    @Test fun actualCameraSessionFinalizesTimelapseAndDismissalDeletesIt() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("game_settings", Context.MODE_PRIVATE)
        PreferencesSnapshot(prefs).use {
            check(prefs.edit().clear().putBoolean("onboarding_completed", true)
                .putBoolean("timelapse_recording_enabled", true).putString("acceleration_mode", "Cpu")
                .putString("pose_landmarker_model", "Full").commit())
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.CAMERA)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: GameViewModel
                scenario.onActivity { model = ViewModelProvider(it)[GameViewModel::class.java] }
                awaitUi("real camera", 30_000) { model.poseOverlayState.value.imageWidth > 0 }
                onMain { model.startSession() }
                awaitUi("stationary phone starts countdown", 30_000) { model.gameState.value == GameState.StartingDelay }
                awaitUi("countdown completed", 20_000) { model.gameState.value in setOf(GameState.HoldingPose, GameState.Failed) }
                if (model.gameState.value == GameState.HoldingPose) onMain { model.onCameraUnavailable() }
                assertEquals(GameState.Failed, model.gameState.value)
                val saveLabel = app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration)
                    .apply { setLocale(model.gameSettings.value.language.locale) }).getString(R.string.final_save_timelapse)
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(androidx.compose.ui.test.hasText(saveLabel)).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(saveLabel).assertIsEnabled()
                val file = timelapseFiles(app).single()
                assertPlayableTimelapse(file, minSamples = 3)
                onMain { model.dismissFinalScreen() }
                awaitUi("final video removed on dismissal") { !file.exists() }
                assertEquals(GameState.Idle, model.gameState.value)
            }
        }
    }
}
