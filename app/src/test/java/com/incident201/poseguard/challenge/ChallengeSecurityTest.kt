package com.incident201.poseguard.challenge

import com.incident201.poseguard.challenge.crypto.EncryptedChallengeFileSealer
import com.incident201.poseguard.challenge.crypto.Envelope
import com.incident201.poseguard.viewmodel.RuleViolationCounts
import org.junit.Assert.*
import org.junit.Test

class ChallengeSecurityTest {
    private val sealer = EncryptedChallengeFileSealer(ByteArray(32) { 42 })
    private val keys = sealer.createAuthorKeys()
    private val files = ChallengeFiles(sealer, ChallengeDomain.Dev)
    private val spec = ChallengeSpec(Blob(ByteArray(16) { 1 }), "Hidden", "", "Author", 0,
        ChallengeRules(), ChallengeVisibility(), 3, Blob(ByteArray(32) { 2 }), keys.publicKey)
    private val report = ChallengeReport(Blob(ByteArray(16) { 3 }), spec.id, 1, "test", 0, 1000,
        0, 1000, "", "", SessionEndCause.Surrendered, 180, 0, RuleViolationCounts(), emptyList(), false, null)
    private val authored = AuthoredChallenge(spec, keys.privateKey)

    @Test fun hkdfMatchesRfc5869CaseOne() {
        val result = Envelope.derive(ByteArray(22) { 0x0b }, ByteArray(13) { it.toByte() }, ByteArray(10) { (0xf0 + it).toByte() })
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf", Blob(result).toHex())
    }

    @Test fun independentInstancesExchangeEncryptedTasksAndReports() {
        val recipient = ChallengeFiles(EncryptedChallengeFileSealer(ByteArray(32) { 42 }), ChallengeDomain.Dev)
        assertEquals(ChallengeFileResult.Ok(spec), recipient.decodeTask(files.encodeTask(spec)))
        val file = recipient.encodeReport(report, spec)
        val result = files.decodeReport(file) { authored } as ChallengeFileResult.Ok
        assertEquals(report, result.value.first)
        assertFalse(files.encodeTask(spec).contentEquals(files.encodeTask(spec)))
        assertFalse(file.contentEquals(recipient.encodeReport(report, spec)))
    }

    @Test fun everySingleByteMutationOfTaskAndReportIsRejected() {
        val task = files.encodeTask(spec)
        for (i in task.indices) {
            val edited = task.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertTrue("Task byte $i", files.decodeTask(edited) is ChallengeFileResult.Failed)
        }
        val file = files.encodeReport(report, spec)
        for (i in file.indices) {
            val edited = file.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertTrue("Report byte $i", files.decodeReport(edited) { id -> authored.takeIf { it.spec.id == id } } is ChallengeFileResult.Failed)
        }
    }

    @Test fun reportsRequireAuthorsPrivateKeyAndTaskSecret() {
        val file = files.encodeReport(report, spec)
        val wrongKeys = sealer.createAuthorKeys()
        assertTrue(files.decodeReport(file) { AuthoredChallenge(spec, wrongKeys.privateKey) } is ChallengeFileResult.Failed)
        assertTrue(files.decodeReport(file) { AuthoredChallenge(spec.copy(taskSecret = Blob(ByteArray(32))), keys.privateKey) } is ChallengeFileResult.Failed)
        assertTrue(files.decodeReport(file) { AuthoredChallenge(spec.copy(authorPublicKey = wrongKeys.publicKey), keys.privateKey) } is ChallengeFileResult.Failed)
    }

    @Test fun wrongAppKeyDomainAndPlaintextCannotBeAccepted() {
        val other = ChallengeFiles(EncryptedChallengeFileSealer(ByteArray(32)), ChallengeDomain.Dev)
        assertTrue(other.decodeTask(files.encodeTask(spec)) is ChallengeFileResult.Failed)
        assertTrue(other.decodeReport(files.encodeReport(report, spec)) { authored } is ChallengeFileResult.Failed)
        val release = ChallengeFiles(sealer, ChallengeDomain.Release)
        assertEquals(ChallengeFileResult.Failed(ChallengeFileError.WrongDomain), release.decodeTask(files.encodeTask(spec)))
        val task = files.encodeTask(spec)
        val plaintext = task.copyOfRange(0, 21) + ChallengeFiles.deflate(com.incident201.poseguard.challenge.codec.ChallengeCodec.encode(spec))
        assertTrue(files.decodeTask(plaintext) is ChallengeFileResult.Failed)
        assertEquals(ChallengeFileResult.Failed(ChallengeFileError.UnsupportedVersion), files.decodeTask(task.copyOf().also { it[3] = 1 }))
    }

    @Test fun truncationAndExtraBytesAreRejected() {
        val task = files.encodeTask(spec)
        for (size in task.indices) assertTrue(files.decodeTask(task.copyOf(size)) is ChallengeFileResult.Failed)
        val file = files.encodeReport(report, spec)
        for (size in file.indices) assertTrue(files.decodeReport(file.copyOf(size)) { authored } is ChallengeFileResult.Failed)
        assertTrue(files.decodeTask(task + byteArrayOf(0)) is ChallengeFileResult.Failed)
        assertTrue(files.decodeReport(file + byteArrayOf(0)) { authored } is ChallengeFileResult.Failed)
    }
}
