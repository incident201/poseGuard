package com.incident201.poseguard.challenge

import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.audio.AudioCueMode
import com.incident201.poseguard.audio.AudioCueSettings
import com.incident201.poseguard.challenge.crypto.EncryptedChallengeFileSealer
import com.incident201.poseguard.viewmodel.RuleViolationCounts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every way a challenge and its report travel between two people, through the same
 * [ChallengeRepository.import] entry point that files, intents, pasted codes and QR codes use.
 * Each participant has separate storage, as on two phones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChallengeExchangeTest {
    private val context = RuntimeEnvironment.getApplication()
    private val appKey = ByteArray(32) { 5 }
    private val repositories = mutableListOf<ChallengeRepository>()

    private fun participant(name: String, domain: ChallengeDomain = ChallengeDomain.Dev) =
        ChallengeRepository(context, EncryptedChallengeFileSealer(appKey), domain, storageName = "test_$name")
            .also(repositories::add)

    private val author = participant("author")
    private val executor = participant("executor")

    @After fun cleanUp() = repositories.forEach { it.deleteStorage() }

    private fun ChallengeRepository.create(
        rules: ChallengeRules = ChallengeRules(timer = TimerSpec(exactSeconds = 120)),
        attempts: Int = 0
    ) = createChallenge("Corner", "Stay still", "Author", rules, ChallengeVisibility(showDuration = false), attempts)

    private fun String.bytes() = toByteArray(Charsets.UTF_8)

    private fun ChallengeRepository.importTask(data: ByteArray): ReceivedChallenge {
        val result = import(data)
        assertTrue("expected a task, got $result", result is ImportResult.TaskImported)
        return (result as ImportResult.TaskImported).challenge
    }

    private fun failure(result: ImportResult): ChallengeFileError =
        (result as? ImportResult.Failed)?.error ?: throw AssertionError("expected a failure, got $result")

    /** The executor's side of a finished attempt: the report file they send back. */
    private fun ChallengeRepository.reportFor(spec: ChallengeSpec, name: String = "Executor", comment: String = "done"): ByteArray {
        val attempt = beginAttempt(spec)!!
        val report = ChallengeSessionRecorder(spec, attempt, "test", 1_000L, 1_700_000_000_000L)
            .finish(SessionEndCause.Completed, 121_000L, 1_700_000_120_000L, 120, 120, RuleViolationCounts(), false)
        saveOutgoingReport(report)
        return reportFile(report, name, comment)!!
    }

    // Task: every transport

    @Test fun taskTravelsAsAFile() {
        val authored = author.create()
        val received = executor.importTask(author.taskFile(authored.spec))
        assertEquals(authored.spec, received.spec)
        assertEquals(listOf(authored.spec.id), executor.receivedChallenges().map { it.spec.id })
    }

    @Test fun taskTravelsAsATextCode() {
        val authored = author.create()
        val code = ChallengeFiles.toTextCode(author.taskFile(authored.spec))
        assertEquals(authored.spec, executor.importTask(code.bytes()).spec)
    }

    @Test fun taskTravelsInsideAChatMessage() {
        val authored = author.create()
        val code = ChallengeFiles.toTextCode(author.taskFile(authored.spec))
        // As the share text and as messengers deliver it: surrounding words, line breaks, padding.
        val message = "Challenge “Corner” in PoseGuard:\n\n$code\n\nOpen it in the app."
        assertEquals(authored.spec, executor.importTask(message.bytes()).spec)
        assertEquals(authored.spec, executor.importTask("  \n$code  ".bytes()).spec)
    }

    @Test fun taskWithAudioTravelsAsAFileAndPlaysFromAPrivateCopy() {
        val clip = AudioClip("audio/ogg", Blob(ByteArray(AudioClip.MAX_BYTES) { (it % 251).toByte() }))
        val rules = ChallengeRules(
            audioCueSettings = AudioCue.entries.associateWith { AudioCueSettings() } +
                (AudioCue.TimeIsUp to AudioCueSettings(mode = AudioCueMode.AudioFile)),
            audioClips = mapOf(AudioCue.TimeIsUp to clip)
        )
        val authored = author.create(rules)
        val received = executor.importTask(author.taskFile(authored.spec))
        assertEquals(clip, received.spec.rules.audioClips[AudioCue.TimeIsUp])
        val uri = executor.audioFiles(received.spec)[AudioCue.TimeIsUp]!!
        assertTrue(clip.data.bytes.contentEquals(File(android.net.Uri.parse(uri).path!!).readBytes()))
    }

    @Test fun importingTheSameTaskAgainIsRecognized() {
        val file = author.taskFile(author.create().spec)
        assertEquals(false, (executor.import(file) as ImportResult.TaskImported).alreadyKnown)
        assertEquals(true, (executor.import(file) as ImportResult.TaskImported).alreadyKnown)
        assertEquals(true, (executor.import(ChallengeFiles.toTextCode(file).bytes()) as ImportResult.TaskImported).alreadyKnown)
        assertEquals(1, executor.receivedChallenges().size)
    }

    @Test fun theAuthorOpeningTheirOwnTaskGetsTheirChallenge() {
        val authored = author.create()
        val file = author.taskFile(authored.spec)
        for (data in listOf(file, ChallengeFiles.toTextCode(file).bytes())) {
            val result = author.import(data)
            assertTrue("$result", result is ImportResult.OwnTask)
            assertEquals(authored.spec.id, (result as ImportResult.OwnTask).challenge.spec.id)
        }
        assertTrue(author.receivedChallenges().isEmpty())
    }

    // Attempts

    @Test fun attemptsAreLimitedAndSurviveDeletingAndReimporting() {
        val authored = author.create(attempts = 1)
        val file = author.taskFile(authored.spec)
        val spec = executor.importTask(file).spec
        assertEquals(1, executor.beginAttempt(spec))
        assertNull(executor.beginAttempt(spec))
        executor.deleteReceived(spec.id)
        assertEquals(0, executor.importTask(file).attemptsLeft)
        assertNull(executor.beginAttempt(spec))
    }

    @Test fun theAuthorsOwnTestRunsAreUnlimited() {
        val authored = author.create(attempts = 1)
        repeat(3) { assertNotNull(author.beginAttempt(authored.spec)) }
    }

    // Report back to the author

    @Test fun reportTravelsBackToTheAuthor() {
        val authored = author.create()
        val spec = executor.importTask(author.taskFile(authored.spec)).spec
        val reportFile = executor.reportFor(spec, name = "Anna", comment = "easy")

        val result = author.import(reportFile)
        assertTrue("$result", result is ImportResult.ReportImported)
        val incoming = (result as ImportResult.ReportImported).report
        assertEquals(false, result.alreadyKnown)
        assertEquals("Anna", incoming.report.executorName)
        assertEquals("easy", incoming.report.executorComment)
        assertEquals(authored.spec.id, incoming.challenge.spec.id)
        assertEquals(listOf(incoming.report.reportId), author.incomingReports().map { it.report.reportId })
        assertEquals(1, executor.outgoingReports().size)

        assertEquals(true, (author.import(reportFile) as ImportResult.ReportImported).alreadyKnown)
        assertEquals(1, author.incomingReports().size)
        assertEquals("Anna", author.incomingReport(incoming.report.reportId)?.report?.executorName)
        assertNull(author.incomingReport(Blob(ByteArray(ChallengeSpec.ID_SIZE))))
    }

    @Test fun reportsOfSeveralExecutorsAreKeptApart() {
        val authored = author.create()
        val file = author.taskFile(authored.spec)
        val second = participant("second")
        val first = executor.reportFor(executor.importTask(file).spec, name = "A")
        val other = second.reportFor(second.importTask(file).spec, name = "B")
        author.import(first)
        author.import(other)
        assertEquals(setOf("A", "B"), author.incomingReports().map { it.report.executorName }.toSet())
    }

    @Test fun onlyTheAuthorCanOpenAReport() {
        val authored = author.create()
        val spec = executor.importTask(author.taskFile(authored.spec)).spec
        val reportFile = executor.reportFor(spec)
        // The executor, or anyone else, has no author key for this challenge.
        assertEquals(ChallengeFileError.UnknownChallenge, failure(executor.import(reportFile)))
        assertEquals(ChallengeFileError.UnknownChallenge, failure(participant("stranger").import(reportFile)))
    }

    @Test fun aReportOfAnotherChallengeDoesNotOpenForTheAuthor() {
        val other = participant("other_author")
        val foreign = other.create()
        val reportFile = executor.reportFor(executor.importTask(other.taskFile(foreign.spec)).spec)
        author.create()
        assertEquals(ChallengeFileError.UnknownChallenge, failure(author.import(reportFile)))
    }

    @Test fun aDeletedChallengeNoLongerAcceptsReports() {
        val authored = author.create()
        val reportFile = executor.reportFor(executor.importTask(author.taskFile(authored.spec)).spec)
        author.deleteAuthored(authored.spec.id)
        assertEquals(ChallengeFileError.UnknownChallenge, failure(author.import(reportFile)))
    }

    @Test fun deletingAReceivedTaskStillAllowsSendingItsReportsAfterRestart() {
        val authored = author.create(attempts = 1)
        val spec = executor.importTask(author.taskFile(authored.spec)).spec
        executor.reportFor(spec)
        val original = executor.outgoingReports().single().report
        executor.deleteReceived(spec.id)
        assertNull(executor.receivedChallenge(spec.id))

        val restarted = participant("executor")
        val outgoing = restarted.outgoingReports().single()
        assertEquals(spec.title, outgoing.challengeTitle)
        val exported = restarted.reportFile(outgoing.report.reportId, "Anna", "sent later")!!
        val incoming = (author.import(exported) as ImportResult.ReportImported).report.report
        assertEquals(original.reportId, incoming.reportId)
        assertEquals("Anna", incoming.executorName)
        assertEquals("sent later", incoming.executorComment)
        assertEquals(0, restarted.importTask(author.taskFile(spec)).attemptsLeft)
    }

    @Test fun deletingATaskPreservesExportContextForReportsFromOlderVersions() {
        val spec = executor.importTask(author.taskFile(author.create().spec)).spec
        executor.reportFor(spec)
        // Older releases stored only the raw .report file.
        File(context.filesDir, "test_executor/report_context").deleteRecursively()
        executor.deleteReceived(spec.id)
        val outgoing = executor.outgoingReports().single()
        assertEquals(spec.title, outgoing.challengeTitle)
        assertTrue(author.import(executor.reportFile(outgoing.report.reportId, "", "")!!) is ImportResult.ReportImported)
    }

    @Test fun deletingAnOwnTestTaskAlsoKeepsItsOutgoingReportsSendable() {
        val spec = author.create().spec
        author.reportFor(spec)
        author.deleteAuthored(spec.id)
        val outgoing = author.outgoingReports().single()
        assertEquals(spec.title, outgoing.challengeTitle)
        assertNotNull(author.reportFile(outgoing.report.reportId, "", ""))
    }

    @Test fun deletingAnAuthoredTaskRemovesItsReportFilesAndKeepsOtherTasksReports() {
        val first = author.create()
        val second = author.create()
        val firstFile = executor.reportFor(executor.importTask(author.taskFile(first.spec)).spec)
        val secondFile = executor.reportFor(executor.importTask(author.taskFile(second.spec)).spec)
        val firstReport = (author.import(firstFile) as ImportResult.ReportImported).report.report
        val secondReport = (author.import(secondFile) as ImportResult.ReportImported).report.report
        val inbox = File(context.filesDir, "test_author/reports_in")
        assertEquals(2, inbox.listFiles()!!.size)
        author.deleteAuthored(first.spec.id)
        assertEquals(listOf(secondReport.reportId), author.incomingReports().map { it.report.reportId })
        assertEquals(1, inbox.listFiles()!!.size)

        // Also clean files orphaned by the old deletion order, when the key is gone.
        val orphan = File(inbox, firstReport.reportId.toHex() + ".pgreport").apply { writeBytes(firstFile) }
        author.deleteAuthored(first.spec.id)
        assertTrue(!orphan.exists())
        assertNotNull(author.incomingReport(secondReport.reportId))
    }

    // Broken input

    @Test fun anythingThatIsNotAChallengeIsRejected() {
        for (data in listOf(ByteArray(0), "hello".bytes(), "PG".bytes(), "PGT".bytes(), ByteArray(100) { 7 }, "PGT2!".bytes())) {
            val error = failure(executor.import(data))
            assertTrue("$error for ${String(data)}", error == ChallengeFileError.NotAChallengeFile || error == ChallengeFileError.Damaged)
        }
        assertEquals(ChallengeFileError.NotAChallengeFile, failure(executor.import("hello".bytes())))
    }

    @Test fun damagedTasksAreRejectedWhicheverWayTheyArrive() {
        val file = author.taskFile(author.create().spec)
        val flipped = file.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        val truncated = file.copyOf(file.size - 5)
        for (broken in listOf(flipped, truncated)) {
            assertEquals(ChallengeFileError.Damaged, failure(executor.import(broken)))
            assertEquals(ChallengeFileError.Damaged, failure(executor.import(ChallengeFiles.toTextCode(broken).bytes())))
        }
        // A code cut off by a messenger, at every length: some are not even valid base64.
        val code = ChallengeFiles.toTextCode(file)
        for (cut in 1..8) {
            assertEquals("cut $cut", ChallengeFileError.Damaged, failure(executor.import(code.dropLast(cut).bytes())))
        }
        assertEquals(ChallengeFileError.Damaged, failure(executor.import("Look: PGT2:".bytes())))
        assertTrue(executor.receivedChallenges().isEmpty())
    }

    @Test fun damagedReportsAreRejected() {
        val authored = author.create()
        val reportFile = executor.reportFor(executor.importTask(author.taskFile(authored.spec)).spec)
        val flipped = reportFile.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        assertEquals(ChallengeFileError.Damaged, failure(author.import(flipped)))
        assertEquals(ChallengeFileError.Damaged, failure(author.import(reportFile.copyOf(reportFile.size - 5))))
        assertTrue(author.incomingReports().isEmpty())
    }

    @Test fun aFileOfANewerFormatAsksForAnUpdate() {
        val file = author.taskFile(author.create().spec).copyOf().also { it[3] = (ChallengeFiles.FORMAT_VERSION + 1).toByte() }
        assertEquals(ChallengeFileError.UnsupportedVersion, failure(executor.import(file)))
        assertEquals(ChallengeFileError.UnsupportedVersion, failure(executor.import(ChallengeFiles.toTextCode(file).bytes())))
    }

    @Test fun releaseAndDebugBuildsDoNotExchangeFiles() {
        val releaseAuthor = participant("release_author", ChallengeDomain.Release)
        val file = releaseAuthor.taskFile(releaseAuthor.create().spec)
        assertEquals(ChallengeFileError.WrongDomain, failure(executor.import(file)))
        assertEquals(ChallengeFileError.WrongDomain, failure(executor.import(ChallengeFiles.toTextCode(file).bytes())))
    }

    @Test fun aBuildWithAnotherAppKeyCannotOpenTasks() {
        val foreign = ChallengeRepository(context, EncryptedChallengeFileSealer(ByteArray(32) { 9 }), ChallengeDomain.Dev, "test_foreign")
            .also(repositories::add)
        val file = foreign.taskFile(foreign.create().spec)
        assertEquals(ChallengeFileError.Damaged, failure(executor.import(file)))
    }
}
