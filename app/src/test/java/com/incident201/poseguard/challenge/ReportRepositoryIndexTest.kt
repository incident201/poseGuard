package com.incident201.poseguard.challenge

import com.incident201.poseguard.challenge.crypto.EncryptedChallengeFileSealer
import com.incident201.poseguard.tracker.FaceDetectionStatus
import com.incident201.poseguard.viewmodel.RuleViolationCounts
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportRepositoryIndexTest {
    private val context = RuntimeEnvironment.getApplication()
    private val sealer = CountingSealer()
    private val repository = repository()
    private val root = File(context.filesDir, "test_report_index")
    private val index = File(root, "report_index_Dev")

    private fun repository() = ChallengeRepository(context, sealer, storageName = "test_report_index")
    private fun task(title: String = "Index") = repository.createChallenge(title, "", "Author",
        ChallengeRules(), ChallengeVisibility(), 0)

    private fun report(spec: ChallengeSpec, id: Int, trace: SessionTrace? = null) = ChallengeReport(
        Blob(ByteArray(16).apply { this[0] = id.toByte() }), spec.id, id + 1, "test",
        1000, 2000L + id, 1000, 2000L + id, "Executor $id", "comment",
        SessionEndCause.Surrendered, 180, 1, RuleViolationCounts(), emptyList(), false, trace)

    private fun import(report: ChallengeReport, task: AuthoredChallenge) {
        assertTrue(repository.import(repository.files.encodeReport(report, task.spec), 10000L + report.attempt)
            is ImportResult.ReportImported)
    }

    @After fun cleanup() { repository.deleteStorage() }

    @Test fun listsUseSmallPersistentIndexesAndOnlyOpenTheSelectedRecording() {
        val a = task()
        val b = task("Other")
        val first = report(a.spec, 1)
        val second = report(b.spec, 2)
        import(first, a)
        import(second, b)
        repository.saveOutgoingReport(first, a.spec)
        repository.saveOutgoingReport(second, b.spec)
        sealer.opened.clear()
        val restarted = repository()
        assertEquals(listOf(second.reportId, first.reportId), restarted.incomingReports().map { it.report.reportId })
        assertEquals(listOf(second.reportId, first.reportId), restarted.outgoingReports().map { it.report.reportId })
        assertEquals(listOf(first.reportId), restarted.incomingReports(a.spec.id).map { it.report.reportId })
        assertTrue(sealer.opened.isEmpty())
        assertTrue(index.listFiles()!!.all { it.length() < 1024 })
        assertEquals(first, restarted.incomingReport(first.reportId)!!.report)
        assertEquals(listOf(first.reportId), sealer.opened)
        assertEquals(second, restarted.outgoingReport(second.reportId)!!.report)
        assertNull(restarted.outgoingReport(Blob(ByteArray(16) { 100 })))
        val sent = restarted.reportFile(second.reportId, "New name", "New comment")!!
        val imported = restarted.import(sent) as ImportResult.ReportImported
        assertEquals("New comment", imported.report.report.executorComment)
        // A duplicate import must not replace an existing card with unsaved data.
        assertEquals("Executor 2", restarted.incomingReports().first().report.executorName)
    }

    @Test fun missingCorruptAndStaleIndexesAreRebuiltAndDeletedWithReports() {
        val a = task()
        val first = report(a.spec, 1)
        val second = report(a.spec, 2)
        import(first, a)
        import(second, a)
        repository.saveOutgoingReport(first, a.spec)
        repository.saveOutgoingReport(second, a.spec)
        index.deleteRecursively() // Reports saved by previous versions.
        sealer.opened.clear()
        assertEquals(2, repository.incomingReports().size)
        assertEquals(2, repository.outgoingReports().size)
        assertEquals(2, sealer.opened.size)
        sealer.opened.clear()
        File(index, first.reportId.toHex() + ".pgreport.bin").writeBytes(byteArrayOf(0))
        assertEquals(2, repository.incomingReports().size)
        assertEquals(listOf(first.reportId), sealer.opened)
        val changed = second.copy(executorName = "Changed report")
        File(root, "reports_in/${second.reportId.toHex()}.pgreport")
            .writeBytes(repository.files.encodeReport(changed, a.spec))
        assertEquals("Changed report", repository.incomingReports().first().report.executorName)
        repository.deleteIncoming(first.reportId)
        assertFalse(File(index, first.reportId.toHex() + ".pgreport.bin").exists())
        repository.deleteOutgoing(first.reportId)
        assertNotNull(repository.reportFile(second.reportId, "", ""))
        repository.deleteAuthored(a.spec.id)
        assertTrue(repository.incomingReports().isEmpty())
        assertTrue(index.listFiles()!!.none { it.name.endsWith(".pgreport.bin") })
        // Export context remains until the final outgoing report is removed.
        assertNotNull(repository.reportFile(second.reportId, "", ""))
        repository.deleteOutgoing(second.reportId)
        assertTrue(repository.outgoingReports().isEmpty())
        assertTrue(index.listFiles().isNullOrEmpty())
        assertFalse(File(root, "report_context/${a.spec.id.toHex()}.bin").exists())
    }

    @Test fun cacheWriteFailureDoesNotDiscardReportsAndOpeningStillAuthenticates() {
        val a = task()
        index.writeText("Cannot create a directory here")
        val r = report(a.spec, 1)
        import(r, a)
        repository.saveOutgoingReport(r, a.spec)
        assertEquals(1, repository.incomingReports().size)
        assertEquals(1, repository.outgoingReports().size)
        index.delete()
        repository.incomingReports() // Build the cache.
        sealer.rejectReports = true
        assertEquals(1, repository.incomingReports().size)
        assertNull(repository.incomingReport(r.reportId)) // A card never bypasses authentication.
    }

    @Test fun largeHistoryAndLegacyMigrationDoNotRetainAllTraces() {
        val a = task()
        val frames = 8 * 1024 * 1024 / 54
        val data = ByteArray(frames * 54)
        for (i in data.indices step 54) { data[i] = 100; data[i + 2] = 5 }
        val pose = TracePose(IntArray(17) { 30000 }, IntArray(17) { 30000 }, IntArray(17) { 255 })
        val trace = SessionTrace(1000, pose, 1000, FaceDetectionStatus.FaceVisible, data, frames, false)
        repeat(40) { import(report(a.spec, it, trace), a) }
        assertEquals(40, repository.incomingReports().size)
        index.deleteRecursively()
        sealer.opened.clear()
        // The former implementation exhausted a 256 MiB heap with this same history.
        val cards = repository().incomingReports()
        assertEquals(40, cards.size)
        assertEquals(40, sealer.opened.size)
        sealer.opened.clear()
        assertEquals(cards, repository().incomingReports())
        assertTrue(sealer.opened.isEmpty())
        val selected = repository.incomingReport(cards.first().report.reportId)!!.report
        assertArrayEquals(data, selected.trace!!.frameData)
    }

    private class CountingSealer : ChallengeFileSealer {
        private val delegate = EncryptedChallengeFileSealer(ByteArray(32) { 5 })
        val opened = mutableListOf<Blob>()
        var rejectReports = false
        override fun createAuthorKeys() = delegate.createAuthorKeys()
        override fun seal(payload: ByteArray, context: SealContext) = delegate.seal(payload, context)
        override fun open(sealed: ByteArray, context: SealContext): ByteArray {
            if (context.kind == ChallengeFileKind.Report) {
                opened += requireNotNull(context.reportId)
                if (rejectReports) throw SealException("Unavailable author key")
            }
            return delegate.open(sealed, context)
        }
    }
}
