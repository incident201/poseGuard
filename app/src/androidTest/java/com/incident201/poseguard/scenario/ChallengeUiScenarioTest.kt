package com.incident201.poseguard.scenario

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.incident201.poseguard.challenge.Blob
import com.incident201.poseguard.challenge.ChallengeFileResult
import com.incident201.poseguard.challenge.ChallengeFiles
import com.incident201.poseguard.challenge.ChallengeRepository
import com.incident201.poseguard.challenge.ChallengeRules
import com.incident201.poseguard.challenge.ChallengeSpec
import com.incident201.poseguard.challenge.ChallengeVisibility
import com.incident201.poseguard.challenge.TimerSpec
import com.incident201.poseguard.ui.ChallengeShare
import com.incident201.poseguard.viewmodel.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Copy and paste of text codes through the real challenge screens and the system clipboard. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ChallengeUiScenarioTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val english = AppLanguage.English
    private lateinit var app: Application
    private lateinit var settings: PreferencesSnapshot
    private lateinit var appRepository: ChallengeRepository
    private lateinit var other: ChallengeRepository
    private val receivedIds = mutableListOf<Blob>()
    private val authoredIds = mutableListOf<Blob>()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.CAMERA)
        val prefs = app.getSharedPreferences("game_settings", Context.MODE_PRIVATE)
        settings = PreferencesSnapshot(prefs)
        check(prefs.edit().clear()
            .putBoolean("onboarding_completed", true)
            .putBoolean("challenge_intro_completed", true)
            .putString("app_language", english.name)
            .putBoolean("timelapse_recording_enabled", false)
            .putString("acceleration_mode", "Cpu")
            .putString("pose_landmarker_model", "Full")
            .commit())
        appRepository = ChallengeRepository(app)
        other = ChallengeRepository(app, storageName = "test_ui_other")
    }

    @After fun tearDown() {
        receivedIds.forEach(appRepository::deleteReceived)
        authoredIds.forEach(appRepository::deleteAuthored)
        other.deleteStorage()
        settings.close()
    }

    private fun text(resId: Int): String = app.createConfigurationContext(
        Configuration(app.resources.configuration).apply { setLocale(english.locale) }
    ).getString(resId)

    private fun awaitNode(description: String, matcher: SemanticsMatcher) {
        try { compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() } }
        catch (failure: Throwable) { throw AssertionError("Timed out waiting for $description", failure) }
    }

    private fun ChallengeRepository.create(title: String): ChallengeSpec = createChallenge(
        title, "", "Tester", ChallengeRules(timer = TimerSpec(exactSeconds = 60)), ChallengeVisibility(), 0
    ).spec

    private fun onChallengesHub(body: () -> Unit) = ActivityScenario.launch(MainActivity::class.java).use {
        awaitNode("main screen", hasText(text(R.string.start)))
        compose.onNodeWithContentDescription(text(R.string.challenges_title)).performClick()
        awaitNode("challenges hub", hasText(text(R.string.challenge_add_title)))
        body()
    }

    private fun guideNotSeenYet() = check(
        app.getSharedPreferences("game_settings", Context.MODE_PRIVATE).edit().putBoolean("challenge_intro_completed", false).commit()
    )

    private fun guideSeen() = app.getSharedPreferences("game_settings", Context.MODE_PRIVATE).getBoolean("challenge_intro_completed", false)

    private fun openChallengesExpectingTheGuide() {
        awaitNode("main screen", hasText(text(R.string.start)))
        compose.onNodeWithContentDescription(text(R.string.challenges_title)).performClick()
        awaitNode("first guide page", hasText(text(R.string.challenge_intro_title_1)))
    }

    @Test fun theGuideComesFirstOnceAndSkipLeadsToTheHub() {
        guideNotSeenYet()
        ActivityScenario.launch(MainActivity::class.java).use {
            openChallengesExpectingTheGuide()
            compose.onNodeWithText(text(R.string.guide_skip)).performClick()
            awaitNode("challenges hub", hasText(text(R.string.challenge_add_title)))
            assertTrue(guideSeen())
            compose.onNodeWithContentDescription(text(R.string.back)).performClick()
            awaitNode("main screen", hasText(text(R.string.start)))
            compose.onNodeWithContentDescription(text(R.string.challenges_title)).performClick()
            awaitNode("hub without the guide", hasText(text(R.string.challenge_add_title)))
            assertTrue(compose.onAllNodes(hasText(text(R.string.challenge_intro_title_1))).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun readingTheGuideToTheEndCanStartANewChallenge() {
        guideNotSeenYet()
        ActivityScenario.launch(MainActivity::class.java).use {
            openChallengesExpectingTheGuide()
            listOf(R.string.challenge_intro_title_2, R.string.challenge_intro_title_3, R.string.challenge_intro_title_4, R.string.challenge_intro_title_5)
                .forEach { title ->
                    compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
                    awaitNode("guide page", hasText(text(title)))
                }
            compose.onNodeWithText(text(R.string.challenge_create)).performClick()
            awaitNode("editor", hasText(text(R.string.challenge_editor_title)))
            assertTrue(guideSeen())
        }
    }

    @Test fun backOnTheGuideCountsAsSeen() {
        guideNotSeenYet()
        ActivityScenario.launch(MainActivity::class.java).use {
            openChallengesExpectingTheGuide()
            androidx.test.espresso.Espresso.pressBack()
            awaitNode("challenges hub", hasText(text(R.string.challenge_add_title)))
            assertTrue(guideSeen())
        }
    }

    @Test fun aTaskOpenedFromAMessengerFirstShowsTheGuideThenTheTask() {
        guideNotSeenYet()
        val spec = other.create("First contact").also { receivedIds += it.id }
        val code = ChallengeFiles.toTextCode(other.taskFile(spec))
        val share = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setClass(app, MainActivity::class.java)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, code)
        ActivityScenario.launch<MainActivity>(share).use {
            awaitNode("guide before the task", hasText(text(R.string.challenge_intro_title_1)))
            compose.onNodeWithText(text(R.string.guide_skip)).performClick()
            awaitNode("brief of the task", hasText(text(R.string.challenge_accept)))
            compose.onNodeWithText("First contact").assertExists()
            assertTrue(guideSeen())
        }
    }

    @Test fun theHelpButtonShowsTheGuideAgain() {
        onChallengesHub {
            compose.onNodeWithContentDescription(text(R.string.challenge_intro_help)).performClick()
            awaitNode("guide", hasText(text(R.string.challenge_intro_title_1)))
            compose.onNodeWithText(text(R.string.guide_skip)).performClick()
            awaitNode("challenges hub", hasText(text(R.string.challenge_add_title)))
        }
    }

    // Android lets only the focused app use the clipboard, so these run while the hub is shown.
    private fun copyToClipboard(text: String) {
        onMain { ChallengeShare.copyText(app, "test", text) }
        awaitCondition("clipboard written") { clipboard() == text }
    }

    private fun clipboard(): String? {
        var text: String? = null
        onMain { text = runCatching { ChallengeShare.pasteText(app) }.getOrNull() }
        return text
    }

    @Test fun pastingABareCodeImportsTheTask() = pasteImports { code -> code }

    @Test fun pastingAWholeChatMessageImportsTheTask() = pasteImports { code -> "Try this one:\n$code\nGood luck" }

    private fun pasteImports(clip: (String) -> String) {
        val spec = other.create("Pasted from a chat").also { receivedIds += it.id }
        onChallengesHub {
            copyToClipboard(clip(ChallengeFiles.toTextCode(other.taskFile(spec))))
            compose.onNodeWithText(text(R.string.challenge_add_code)).performClick()
            // The task opens on its brief.
            awaitNode("brief of the pasted task", hasText(text(R.string.challenge_accept)))
            awaitCondition("task in Received") { appRepository.receivedChallenge(spec.id)?.spec == spec }
        }
    }

    @Test fun theCopyButtonPutsAWorkingCodeOnTheClipboard() {
        val spec = appRepository.create("Copy me").also { authoredIds += it.id }
        onChallengesHub {
            copyToClipboard("nothing yet")
            compose.onNodeWithText(text(R.string.challenges_tab_mine)).performClick()
            awaitNode("own task in the list", hasText("Copy me"))
            compose.onNodeWithText("Copy me").performClick()
            awaitNode("share card", hasText(text(R.string.challenge_copy_code)))
            compose.onNodeWithText(text(R.string.challenge_copy_code)).performScrollTo().performClick()

            // The system "copied" preview holds the focus for a moment.
            awaitCondition("code on the clipboard", 10_000) { ChallengeFiles.fromTextCode(clipboard().orEmpty()) != null }
            val copied = ChallengeFiles.fromTextCode(clipboard().orEmpty())
            assertEquals(ChallengeFileResult.Ok(spec), appRepository.files.decodeTask(copied!!))

            // Back on the hub, "Code" pastes it: on the author's phone that is their own task.
            compose.onNodeWithContentDescription(text(R.string.back)).performClick()
            awaitNode("challenges hub", hasText(text(R.string.challenge_add_title)))
            compose.onNodeWithText(text(R.string.challenge_add_code)).performClick()
            awaitNode("brief of the own task", hasText(text(R.string.challenge_test_run)))
            assertTrue(appRepository.receivedChallenges().none { it.spec.id == spec.id })
        }
    }

    @Test fun aCutOffCodeIsNotImported() {
        val spec = other.create("Cut off").also { receivedIds += it.id }
        onChallengesHub {
            copyToClipboard(ChallengeFiles.toTextCode(other.taskFile(spec)).dropLast(7))
            compose.onNodeWithText(text(R.string.challenge_add_code)).performClick()
            compose.waitForIdle()
            Thread.sleep(1_000)
            assertNull(appRepository.receivedChallenge(spec.id))
            // Still on the hub: nothing was opened.
            awaitNode("challenges hub", hasText(text(R.string.challenge_add_title)))
        }
    }
}
