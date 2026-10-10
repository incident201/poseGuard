package com.incident201.poseguard.scenario

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.incident201.poseguard.MainActivity
import com.incident201.poseguard.R
import com.incident201.poseguard.viewmodel.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The user's path through the real MainActivity, driven only by what is on screen. These scenarios
 * touch no internal classes, so the same assertions hold for the R8-processed release build.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AppFlowScenarioTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var app: Application

    @Before fun grantCamera() {
        app = ApplicationProvider.getApplicationContext()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            app.packageName, Manifest.permission.CAMERA
        )
    }

    private fun text(language: AppLanguage, resId: Int): String = app.createConfigurationContext(
        Configuration(app.resources.configuration).apply { setLocale(language.locale) }
    ).getString(resId)

    private fun awaitNode(description: String, timeoutMs: Long = 15_000, matcher: androidx.compose.ui.test.SemanticsMatcher) {
        try { compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() } }
        catch (failure: Throwable) { throw AssertionError("Timed out waiting for $description", failure) }
    }

    private fun awaitNoNode(description: String, timeoutMs: Long = 15_000, matcher: androidx.compose.ui.test.SemanticsMatcher) {
        try { compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() } }
        catch (failure: Throwable) { throw AssertionError("Timed out waiting for $description to disappear", failure) }
    }

    private fun withSettings(configure: android.content.SharedPreferences.Editor.() -> Unit, body: (ActivityScenario<MainActivity>) -> Unit) {
        val prefs = app.getSharedPreferences("game_settings", Context.MODE_PRIVATE)
        PreferencesSnapshot(prefs).use {
            check(prefs.edit().clear()
                .putBoolean("timelapse_recording_enabled", false)
                .putString("acceleration_mode", "Cpu")
                .putString("pose_landmarker_model", "Full")
                .apply(configure)
                .commit())
            ActivityScenario.launch(MainActivity::class.java).use(body)
        }
    }

    @Test fun firstRunOnboardingAppliesTheChosenLanguageAndLeadsToTheMainScreen() {
        val german = AppLanguage.German
        withSettings({ }) {
            awaitNode("language slide", matcher = hasText("Deutsch"))
            compose.onNodeWithText("Deutsch").performScrollTo().performClick()
            compose.onNodeWithText(text(german, R.string.onboarding_next)).assertIsDisplayed()

            val done = hasText(text(german, R.string.onboarding_done))
            var pages = 0
            while (compose.onAllNodes(done).fetchSemanticsNodes().isEmpty()) {
                check(++pages <= 12) { "Onboarding never reached its last page" }
                compose.onNodeWithText(text(german, R.string.onboarding_next)).performClick()
                compose.waitForIdle()
            }
            assertTrue("Onboarding has the language slide and the tip slides", pages >= 4)
            compose.onNode(done).performClick()

            awaitNode("main screen", matcher = hasTestTag("start_button"))
            compose.onNodeWithTag("start_button").assertIsDisplayed()
            compose.onNode(hasTestTag("start_button") and hasText(text(german, R.string.start))).assertIsDisplayed()

            val prefs = app.getSharedPreferences("game_settings", Context.MODE_PRIVATE)
            assertEquals("German", prefs.getString("app_language", null))
            assertEquals(true, prefs.getBoolean("onboarding_completed", false))
        }
    }

    @Test fun completedOnboardingIsNotShownAgainAfterRecreation() {
        withSettings({ putBoolean("onboarding_completed", true) }) { scenario ->
            awaitNode("main screen", matcher = hasTestTag("start_button"))
            scenario.recreate()
            awaitNode("main screen after recreation", matcher = hasTestTag("start_button"))
            val prefs = app.getSharedPreferences("game_settings", Context.MODE_PRIVATE)
            assertEquals(true, prefs.getBoolean("onboarding_completed", false))
            assertTrue(compose.onAllNodes(hasText(text(AppLanguage.English, R.string.onboarding_next))).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun startAndStopReturnTheMainScreenToIdle() {
        withSettings({ putBoolean("onboarding_completed", true) }) {
            awaitNode("main screen", matcher = hasTestTag("start_button"))
            compose.onNodeWithTag("start_button").performClick()
            awaitNode("running session", matcher = hasTestTag("stop_on_button"))
            awaitNoNode("start button during the session", matcher = hasTestTag("start_button"))
            compose.onNodeWithTag("stop_on_button").performClick()
            awaitNode("idle main screen", matcher = hasTestTag("start_button"))
            awaitNoNode("stop button after stopping", matcher = hasTestTag("stop_on_button"))
        }
    }

    @Test fun aSettingChangedOnTheSettingsScreenIsStoredAndTheScreenCanBeLeft() {
        val english = AppLanguage.English
        withSettings({ putBoolean("onboarding_completed", true) }) {
            awaitNode("main screen", matcher = hasTestTag("start_button"))
            compose.onNodeWithContentDescription(text(english, R.string.settings)).performClick()
            awaitNode("settings screen", matcher = hasText(text(english, R.string.face_detection)))

            compose.onNodeWithText(text(english, R.string.face_to_camera)).performScrollTo().performClick()
            compose.waitForIdle()
            val prefs = app.getSharedPreferences("game_settings", Context.MODE_PRIVATE)
            assertEquals("FaceToCamera", prefs.getString("face_mode", null))

            compose.onNodeWithText(text(english, R.string.do_not_check)).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals("Disabled", prefs.getString("face_mode", null))

            compose.onNodeWithContentDescription(text(english, R.string.back)).performScrollTo().performClick()
            awaitNode("main screen after settings", matcher = hasTestTag("start_button"))
        }
    }
}
