package com.incident201.poseguard.scenario

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.incident201.poseguard.MainActivity
import com.incident201.poseguard.R
import com.incident201.poseguard.challenge.*
import com.incident201.poseguard.viewmodel.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real-screen regression coverage for challenge visibility and contextual back navigation. */
@RunWith(AndroidJUnit4::class)
class ChallengeNavigationScenarioTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var app: Application
    private lateinit var settings: PreferencesSnapshot
    private lateinit var repository: ChallengeRepository
    private lateinit var foreignAuthor: ChallengeRepository
    private val tasks = mutableListOf<ChallengeSpec>()
    private val received = mutableListOf<ChallengeSpec>()

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.CAMERA)
        val prefs = app.getSharedPreferences("game_settings", Context.MODE_PRIVATE)
        settings = PreferencesSnapshot(prefs)
        check(prefs.edit().clear().putBoolean("onboarding_completed", true)
            .putBoolean("challenge_intro_completed", true).putString("app_language", AppLanguage.English.name)
            .putBoolean("timelapse_recording_enabled", false).putString("acceleration_mode", "Cpu")
            .putString("pose_landmarker_model", "Full").commit())
        repository = ChallengeRepository(app)
        foreignAuthor = ChallengeRepository(app, storageName = "test_navigation_foreign")
    }

    @After fun cleanup() {
        (tasks + received).forEach { spec ->
            repository.incomingReports().filter { it.report.challengeId == spec.id }.forEach { repository.deleteIncoming(it.report.reportId) }
            repository.outgoingReports().filter { it.report.challengeId == spec.id }.forEach { repository.deleteOutgoing(it.report.reportId) }
            repository.deleteAuthored(spec.id)
            repository.deleteReceived(spec.id)
        }
        foreignAuthor.deleteStorage()
        settings.close()
    }

    private fun text(id: Int) = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
        setLocale(AppLanguage.English.locale)
    }).getString(id)

    private fun task(title: String, seconds: Int = 107, hidden: Boolean = false, limit: Int = 4): ChallengeSpec =
        repository.createChallenge(title, "", "Regression probe", ChallengeRules(timer = TimerSpec(exactSeconds = seconds), maxViolations = limit),
            ChallengeVisibility(showDuration = !hidden), 0).spec.also(tasks::add)

    private fun await(matcher: SemanticsMatcher) = compose.waitUntil(20_000) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }
    private fun hub() {
        compose.onNodeWithTag("challenges_button").performClick()
        await(hasText(text(R.string.challenges_title)))
    }
    private fun mineBrief(spec: ChallengeSpec) {
        compose.onNodeWithText(text(R.string.challenges_tab_mine)).performClick()
        await(hasText(spec.title))
        compose.onNodeWithText(spec.title).performClick()
        await(hasText(text(R.string.challenge_test_run)))
    }
    private fun receivedBrief(spec: ChallengeSpec) {
        compose.onNodeWithText(text(R.string.challenges_tab_received)).performClick()
        await(hasText(spec.title))
        compose.onNodeWithText(spec.title).performClick()
        await(hasText(text(R.string.challenge_accept)))
    }
    @Test fun zeroLimitCardSaysNoErrorsAllowed() {
        val spec = task("Regression zero limit UI", limit = 0)
        ActivityScenario.launch(MainActivity::class.java).use {
            await(hasTestTag("start_button")); hub(); mineBrief(spec)
            assertEquals("No errors allowed", text(R.string.challenge_errors_none))
            compose.onNodeWithText(text(R.string.challenge_errors_none)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun receivedHiddenDurationStaysHiddenAfterSwitchAndDelete() {
        val hidden = foreignAuthor.createChallenge("Regression hidden result", "", "Foreign author",
            ChallengeRules(timer = TimerSpec(exactSeconds = 107)), ChallengeVisibility(showDuration = false), 0).spec
        assertTrue(repository.import(foreignAuthor.taskFile(hidden)) is ImportResult.TaskImported)
        received += hidden
        val visible = task("Regression visible replacement", seconds = 211)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(hasTestTag("start_button"))
            lateinit var model: GameViewModel
            scenario.onActivity { model = ViewModelProvider(it)[GameViewModel::class.java] }
            assertTrue(runBlocking { model.acceptChallenge(hidden.id) })
            scenario.onActivity { model.startSession(); model.stopSession() }
            await(hasText("??:??:??"))
            val originalReport = model.challengeReport.value!!.reportId
            hub(); mineBrief(visible)
            compose.onNodeWithText(text(R.string.challenge_test_run)).performScrollTo().performClick()
            await(hasText("??:??:??"))
            assertTrue(compose.onAllNodes(hasText("00:01:47")).fetchSemanticsNodes().isEmpty())
            assertEquals(visible.id, model.activeChallenge.value!!.spec.id)
            assertEquals(originalReport, model.challengeReport.value!!.reportId)
            assertEquals(107, model.sessionSummary.value!!.initialTimerSeconds)

            // Re-selecting or deleting the task also preserves the old result's visibility.
            hub(); receivedBrief(hidden)
            compose.onNodeWithText(text(R.string.challenge_accept)).performScrollTo().performClick()
            await(hasText("??:??:??"))
            // Actual Delete action on the active task calls leaveChallenge().
            hub(); receivedBrief(hidden)
            compose.onNodeWithText(text(R.string.challenge_delete)).performScrollTo().performClick()
            await(hasText(text(R.string.challenges_title)))
            compose.onNodeWithContentDescription(text(R.string.back)).performClick()
            await(hasText("??:??:??"))
            assertTrue(compose.onAllNodes(hasText("00:01:47")).fetchSemanticsNodes().isEmpty())
            assertNull(model.activeChallenge.value)
            assertEquals(originalReport, model.challengeReport.value!!.reportId)
        }
    }

    @Test fun bothBackActionsReturnFromOwnBriefToMine() {
        val spec = task("Regression back targets")
        ActivityScenario.launch(MainActivity::class.java).use {
            await(hasTestTag("start_button")); hub(); mineBrief(spec)
            compose.onNodeWithContentDescription(text(R.string.back)).performClick()
            await(hasText(text(R.string.challenges_title)))
            compose.onNodeWithText(text(R.string.challenges_tab_mine)).assertIsSelected()
            mineBrief(spec)
            androidx.test.espresso.Espresso.pressBack()
            await(hasText(text(R.string.challenges_title)))
            compose.onNodeWithText(text(R.string.challenges_tab_mine)).assertIsSelected()
        }
    }

    @Test fun bothBackActionsReturnFromReportToParentBrief() {
        val spec = task("Regression report parent")
        val report = ChallengeSessionRecorder(spec, 1, "probe", 100, 1_000).finish(SessionEndCause.Surrendered,
            100, 1_000, 107, 0, RuleViolationCounts(), false)
        assertTrue(repository.import(repository.files.encodeReport(report.copy(executorName = "Regression report row"), spec)) is ImportResult.ReportImported)
        ActivityScenario.launch(MainActivity::class.java).use {
            await(hasTestTag("start_button")); hub(); mineBrief(spec)
            compose.onNodeWithText("Regression report row").performScrollTo().performClick()
            await(hasText(text(R.string.report_viewer_title)))
            await(hasText(spec.title))
            compose.onNodeWithContentDescription(text(R.string.back)).performClick()
            await(hasText(text(R.string.challenge_test_run)))
            compose.onNodeWithText(spec.title).assertExists()
            compose.onNodeWithText("Regression report row").performScrollTo().performClick()
            await(hasText(text(R.string.report_viewer_title)))
            await(hasText(spec.title))
            androidx.test.espresso.Espresso.pressBack()
            await(hasText(text(R.string.challenge_test_run)))
            compose.onNodeWithText(spec.title).assertExists()
        }
    }
}
