package com.incident201.poseguard.scenario

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.incident201.poseguard.challenge.ReplayReportVerifier
import com.incident201.poseguard.challenge.VerdictKind
import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.audio.AudioCueMode
import com.incident201.poseguard.audio.AudioCueSettings
import com.incident201.poseguard.challenge.AudioClip
import com.incident201.poseguard.challenge.AuthoredChallenge
import com.incident201.poseguard.challenge.Blob
import com.incident201.poseguard.challenge.ChallengeRepository
import com.incident201.poseguard.challenge.ChallengeFiles
import com.incident201.poseguard.challenge.ChallengeRules
import com.incident201.poseguard.challenge.ImportResult
import com.incident201.poseguard.challenge.ChallengeVisibility
import com.incident201.poseguard.challenge.SessionEndCause
import com.incident201.poseguard.challenge.TimerSpec
import com.incident201.poseguard.challenge.TraceViolationType
import com.incident201.poseguard.tracker.PoseLandmarks
import com.incident201.poseguard.viewmodel.GameState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Challenge sessions on the real ViewModel: rules override, trace recording and reports. */
@RunWith(AndroidJUnit4::class)
class ChallengeScenarioTest : SessionFixture() {
    private val created = mutableListOf<AuthoredChallenge>()

    private fun challenge(
        rules: ChallengeRules,
        visibility: ChallengeVisibility = ChallengeVisibility()
    ): AuthoredChallenge = model.challengeRepository.createChallenge(
        title = "Scenario",
        description = "",
        authorName = "Test",
        rules = rules,
        visibility = visibility,
        attemptsAllowed = 0
    ).also {
        created += it
        assertTrue(runBlocking { model.acceptChallenge(it.spec.id) })
    }

    @After fun removeChallengeFiles() {
        val repository = model.challengeRepository
        created.forEach { authored ->
            repository.outgoingReports().filter { it.report.challengeId == authored.spec.id }
                .forEach { repository.deleteOutgoing(it.report.reportId) }
            repository.deleteAuthored(authored.spec.id)
        }
    }

    @Test fun completedChallengeProducesAReportWithATraceThatTheAuthorCanOpen() {
        onMain { model.updateMaxViolations(99) }
        challenge(ChallengeRules(timer = TimerSpec(exactSeconds = 1), maxViolations = 3))
        start()
        repeat(5) { frame() }
        frame(advanceMs = 1_100)
        awaitCondition("Success") { model.gameState.value == GameState.Success }

        val report = model.challengeReport.value
        assertNotNull(report)
        report!!
        assertEquals(SessionEndCause.Completed, report.endCause)
        assertEquals(1, report.initialTimerSeconds)
        assertEquals(1, report.attempt)
        val trace = report.trace!!
        assertTrue(trace.frameCount >= 6)
        assertEquals(trace.frameCount, trace.frames().size)
        assertTrue(trace.frames().all { it.pose != null })
        // The user's own limit is untouched by the challenge.
        assertEquals(99, model.gameSettings.value.maxViolations)
        assertEquals(3, model.sessionSummary.value!!.settings.maxViolations)

        awaitCondition("stored report") { model.challengeRepository.outgoingReports().any { it.report.reportId == report.reportId } }
        val file = model.challengeRepository.reportFile(report, "Executor", "done")!!
        val imported = model.challengeRepository.import(file)
        assertTrue(imported is ImportResult.ReportImported)
        val incoming = (imported as ImportResult.ReportImported).report
        assertEquals("Executor", incoming.report.executorName)
        assertEquals(trace.frameCount, incoming.report.trace!!.frameCount)
        assertEquals(VerdictKind.Confirmed, ReplayReportVerifier.verify(incoming.report, incoming.challenge.spec).kind)
        model.challengeRepository.deleteIncoming(report.reportId)
    }

    @Test fun violationsAreRecordedAsEventsUpToTheDefeat() {
        challenge(ChallengeRules(timer = TimerSpec(exactSeconds = 60), maxViolations = 2, minimumPenaltyIntervalSeconds = 0))
        start()
        disappearUntilViolationCount(1)
        disappearUntilViolationCount(2)
        awaitCondition("defeat") { model.gameState.value == GameState.Failed }

        val report = model.challengeReport.value!!
        assertEquals(SessionEndCause.TooManyViolations, report.endCause)
        assertEquals(listOf(false, true), report.events.map { it.defeat })
        assertTrue(report.events.all { it.type == TraceViolationType.PersonDisappeared })
        assertEquals(2, report.counts.disappeared)
        assertEquals(VerdictKind.Confirmed, ReplayReportVerifier.verify(report, created.last().spec).kind)
        assertTrue(report.trace!!.frames().any { it.pose == null })
    }

    @Test fun stoppingAChallengeIsASurrenderWithAReport() {
        challenge(ChallengeRules(timer = TimerSpec(exactSeconds = 60)))
        start()
        frame()
        onMain { model.stopSession() }
        assertEquals(GameState.Failed, model.gameState.value)
        assertEquals(SessionEndCause.Surrendered, model.challengeReport.value!!.endCause)
        assertEquals(VerdictKind.Confirmed, ReplayReportVerifier.verify(model.challengeReport.value!!, created.last().spec).kind)

        onMain { model.dismissFinalScreen() }
        assertEquals(GameState.Idle, model.gameState.value)
        assertNull(model.challengeReport.value)
    }

    @Test fun hiddenDurationCountsUpInsteadOfRevealingTheTarget() {
        challenge(
            ChallengeRules(timer = TimerSpec(exactSeconds = 600)),
            visibility = ChallengeVisibility(showDuration = false)
        )
        start()
        frame(advanceMs = 2_100)
        awaitCondition("elapsed timer") { model.timerSeconds.value in 1..10 }
    }

    @Test fun leavingTheChallengeRestoresTheUsersRules() {
        onMain { model.updateMaxViolations(1) }
        challenge(ChallengeRules(timer = TimerSpec(exactSeconds = 60), maxViolations = 5))
        onMain { model.leaveChallenge() }
        start()
        frame(PoseLandmarks(), 50)
        disappearUntilViolation()
        assertEquals(GameState.Failed, model.gameState.value)
        assertNull(model.challengeReport.value)
    }

    @Test fun embeddedAudioIsUnpackedAndPlayedFromAPrivateFile() {
        val clip = AudioClip("audio/ogg", Blob(ByteArray(2_000) { it.toByte() }))
        val rules = ChallengeRules(
            timer = TimerSpec(exactSeconds = 60),
            audioCueSettings = AudioCue.entries.associateWith { AudioCueSettings() } +
                (AudioCue.TimeIsUp to AudioCueSettings(mode = AudioCueMode.AudioFile)),
            audioClips = mapOf(AudioCue.TimeIsUp to clip)
        )
        val authored = challenge(rules)
        assertEquals(clip, model.challengeRepository.authoredChallenge(authored.spec.id)!!.spec.rules.audioClips[AudioCue.TimeIsUp])
        awaitCondition("effective audio") {
            model.effectiveSettings.value.audioCueSettings[AudioCue.TimeIsUp]?.audioFileUri != null
        }
        val cue = model.effectiveSettings.value.audioCueSettings[AudioCue.TimeIsUp]!!
        assertEquals(AudioCueMode.AudioFile, cue.mode)
        assertTrue(model.effectiveSettings.value.customizeAudioEnabled)
        val file = File(android.net.Uri.parse(cue.audioFileUri).path!!)
        assertTrue(clip.data.bytes.contentEquals(file.readBytes()))

        model.challengeRepository.deleteAuthored(authored.spec.id)
        created.remove(authored)
        assertTrue(!file.exists())
        onMain { model.leaveChallenge() }
    }

    @Test fun aTaskFromAnotherAuthorIsDoneHereAndVerifiedByTheAuthor() {
        val author = ChallengeRepository(app, storageName = "test_scenario_author")
        try {
            val authored = author.createChallenge(
                "From a friend", "", "Friend",
                ChallengeRules(timer = TimerSpec(exactSeconds = 1), maxViolations = 3),
                ChallengeVisibility(showDuration = false), 2
            )
            val imported = model.challengeRepository.import(ChallengeFiles.toTextCode(author.taskFile(authored.spec)).toByteArray())
            assertTrue("$imported", imported is ImportResult.TaskImported)
            try {
                assertTrue(runBlocking { model.acceptChallenge(authored.spec.id) })
                start()
                repeat(5) { frame() }
                frame(advanceMs = 1_100)
                awaitCondition("Success") { model.gameState.value == GameState.Success }
                val report = model.challengeReport.value!!
                assertEquals(1, report.attempt)
                awaitCondition("stored report") { model.challengeRepository.outgoingReports().any { it.report.reportId == report.reportId } }
                val file = model.challengeRepository.reportFile(report, "Executor", "")!!

                val opened = author.import(file)
                assertTrue("$opened", opened is ImportResult.ReportImported)
                val incoming = (opened as ImportResult.ReportImported).report
                assertEquals(VerdictKind.Confirmed, ReplayReportVerifier.verify(incoming.report, incoming.challenge.spec).kind)
                // The executor cannot open the report they send.
                assertTrue(model.challengeRepository.import(file) is ImportResult.Failed)
            } finally {
                model.challengeRepository.outgoingReports().filter { it.report.challengeId == authored.spec.id }
                    .forEach { model.challengeRepository.deleteOutgoing(it.report.reportId) }
                model.challengeRepository.deleteReceived(authored.spec.id)
                onMain { model.leaveChallenge() }
            }
        } finally {
            author.deleteStorage()
        }
    }
}
