package com.incident201.poseguard.scenario

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.audio.TtsPhraseTemplate
import com.incident201.poseguard.challenge.*
import com.incident201.poseguard.viewmodel.GameState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Regression coverage for the challenge release review, using the real session pipeline. */
@RunWith(AndroidJUnit4::class)
class ChallengeReleaseScenarioTest : SessionFixture() {
    private val created = mutableListOf<ChallengeSpec>()

    private fun task(rules: ChallengeRules, visibility: ChallengeVisibility = ChallengeVisibility()): ChallengeSpec =
        model.challengeRepository.createChallenge("Release scenario", "", "Tester", rules, visibility, 0).spec.also {
            created += it
            assertTrue(runBlocking { model.acceptChallenge(it.id) })
        }

    @After fun cleanup() {
        model.challengeReport.value?.let { report ->
            awaitCondition("report saved") {
                model.challengeRepository.outgoingReports().any { it.report.reportId == report.reportId }
            }
        }
        created.forEach { spec ->
            model.challengeRepository.outgoingReports().filter { it.report.challengeId == spec.id }
                .forEach { model.challengeRepository.deleteOutgoing(it.report.reportId) }
            model.challengeRepository.deleteAuthored(spec.id)
        }
    }

    @Test fun zeroErrorsAllowedStillDefeatsOnFirstViolation() {
        task(ChallengeRules(timer = TimerSpec(exactSeconds = 60), maxViolations = 0, minimumPenaltyIntervalSeconds = 0))
        start()
        disappearUntilViolation()
        awaitCondition("defeat") { model.gameState.value == GameState.Failed }
        assertEquals(1, model.violationCount.value)
        assertEquals(SessionEndCause.TooManyViolations, model.challengeReport.value!!.endCause)
        assertTrue(model.challengeReport.value!!.events.single().defeat)
    }

    @Test fun surrenderedResultKeepsHiddenDurationAfterSwitchAndLeave() {
        task(ChallengeRules(timer = TimerSpec(exactSeconds = 107)), ChallengeVisibility(showDuration = false))
        onMain { model.startSession(); model.stopSession() }
        val summary = model.sessionSummary.value!!
        val report = model.challengeReport.value!!
        assertFalse(summary.showInitialTimer)
        val visible = task(ChallengeRules(timer = TimerSpec(exactSeconds = 211)))
        assertSame(summary, model.sessionSummary.value)
        assertSame(report, model.challengeReport.value)
        onMain { model.leaveChallenge() }
        assertFalse(model.sessionSummary.value!!.showInitialTimer)
        assertTrue(runBlocking { model.acceptChallenge(visible.id) })
        onMain { model.startSession(); model.stopSession() }
        assertTrue(model.sessionSummary.value!!.showInitialTimer)
    }

    @Test fun successfulResultKeepsHiddenDurationAfterSwitch() {
        task(ChallengeRules(timer = TimerSpec(exactSeconds = 1)), ChallengeVisibility(showDuration = false))
        start()
        repeat(5) { frame() }
        frame(advanceMs = 1_100)
        awaitCondition("success") { model.gameState.value == GameState.Success }
        val summary = model.sessionSummary.value!!
        assertFalse(summary.showInitialTimer)
        task(ChallengeRules())
        assertSame(summary, model.sessionSummary.value)
        assertFalse(model.sessionSummary.value!!.showInitialTimer)
    }

    @Test fun disabledSpeechUsesDefaultsAndDoesNotEnterCopiedRulesButCanBeReenabled() {
        val marker = "Custom release phrase"
        onMain {
            model.updateCustomizeAudioEnabled(true)
            model.updateTtsPhraseTemplate(TtsPhraseTemplate.PlaceDeviceStill, marker)
            model.updateCustomizeAudioEnabled(false)
            model.startSession()
        }
        awaitCondition("default cue") { cues.any { it.cue == AudioCue.PlaceDeviceStill } }
        assertNotEquals(marker, cues.last { it.cue == AudioCue.PlaceDeviceStill }.ttsText)
        onMain { model.stopSession() }
        runBlocking { model.copySettingsIntoDraft() }
        assertTrue(model.challengeDraft.value.rules.customTtsTemplates.isEmpty())
        assertFalse(model.challengeDraft.value.rules.customizeAudioEnabled)
        assertEquals(marker, model.gameSettings.value.customTtsTemplates[TtsPhraseTemplate.PlaceDeviceStill])
        cues.clear()
        onMain { model.updateCustomizeAudioEnabled(true); model.startSession() }
        awaitCondition("custom cue") { cues.any { it.cue == AudioCue.PlaceDeviceStill } }
        assertEquals(marker, cues.last { it.cue == AudioCue.PlaceDeviceStill }.ttsText)
        onMain { model.stopSession() }
        runBlocking { model.copySettingsIntoDraft() }
        assertEquals(marker, model.challengeDraft.value.rules.customTtsTemplates[TtsPhraseTemplate.PlaceDeviceStill])
        assertTrue(model.challengeDraft.value.rules.customizeAudioEnabled)
    }

    @Test fun speechOnlyChallengeUsesAuthorsPhraseWithExecutorsCustomizationDisabled() {
        val marker = "Author's release phrase"
        task(ChallengeRules(customTtsTemplates = mapOf(TtsPhraseTemplate.PlaceDeviceStill to marker)))
        assertFalse(model.gameSettings.value.customizeAudioEnabled)
        onMain { model.startSession() }
        awaitCondition("author cue") { cues.any { it.cue == AudioCue.PlaceDeviceStill } }
        assertEquals(marker, cues.single { it.cue == AudioCue.PlaceDeviceStill }.ttsText)
        onMain { model.stopSession() }
    }

    @Test fun cameraTimeoutReportIsUnverifiedAfterFileExchange() {
        val spec = task(ChallengeRules(timer = TimerSpec(exactSeconds = 60), maxViolations = 99))
        start()
        repeat(5) { frame() }
        Thread.sleep(5_100)
        now.addAndGet(5_100)
        awaitCondition("camera timeout") { model.gameState.value == GameState.Failed }
        val report = model.challengeReport.value!!
        assertEquals(SessionEndCause.CameraNoFrame, report.endCause)
        assertEquals(Verdict(VerdictKind.Unverified, listOf("camera_no_frame")), exchangedVerdict(report, spec))
    }

    @Test fun surrenderBeforeFirstHoldingFrameIsUnverifiedAfterFileExchange() {
        val spec = task(ChallengeRules(timer = TimerSpec(exactSeconds = 60)))
        start()
        onMain { model.stopSession() }
        val report = model.challengeReport.value!!
        assertEquals(0, report.trace!!.frameCount)
        assertEquals(Verdict(VerdictKind.Unverified, listOf("no_trace")), exchangedVerdict(report, spec))
    }

    private fun exchangedVerdict(report: ChallengeReport, spec: ChallengeSpec): Verdict {
        val bytes = model.challengeRepository.reportFile(report, "Tester", "")!!
        val incoming = model.challengeRepository.import(bytes) as ImportResult.ReportImported
        model.challengeRepository.deleteIncoming(report.reportId)
        return ReplayReportVerifier.verify(incoming.report.report, spec)
    }
}
