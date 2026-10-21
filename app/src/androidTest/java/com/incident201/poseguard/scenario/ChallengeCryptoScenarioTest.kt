package com.incident201.poseguard.scenario

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.incident201.poseguard.challenge.*
import com.incident201.poseguard.challenge.crypto.AndroidChallengeFileSealer
import com.incident201.poseguard.viewmodel.RuleViolationCounts
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec

/** Runs against the real Android crypto providers and Keystore; no camera or user data. */
@RunWith(AndroidJUnit4::class)
class ChallengeCryptoScenarioTest {
    @Test fun wrappedKeysSurviveNewInstancesAndAuthenticateReports() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val author = AndroidChallengeFileSealer(context)
        val keys = author.createAuthorKeys()
        assertTrue(runCatching { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keys.privateKey.bytes)) }.isFailure)
        val spec = ChallengeSpec(Blob(ByteArray(16) { 7 }), "Crypto test", "", "", 0,
            ChallengeRules(), ChallengeVisibility(), 1, Blob(ByteArray(32) { 9 }), keys.publicKey)
        val authorFiles = ChallengeFiles(author, ChallengeDomain.Dev)
        val executorFiles = ChallengeFiles(AndroidChallengeFileSealer(context), ChallengeDomain.Dev)
        assertEquals(ChallengeFileResult.Ok(spec), executorFiles.decodeTask(authorFiles.encodeTask(spec)))
        val report = ChallengeReport(Blob(ByteArray(16) { 4 }), spec.id, 1, "test", 0, 1, 0, 1,
            "Executor", "", SessionEndCause.Surrendered, 180, 0, RuleViolationCounts(), emptyList(), false, null)
        val file = executorFiles.encodeReport(report, spec)
        val reopened = ChallengeFiles(AndroidChallengeFileSealer(context), ChallengeDomain.Dev)
        val result = reopened.decodeReport(file) { AuthoredChallenge(spec, keys.privateKey) }
        assertEquals(report, (result as ChallengeFileResult.Ok).value.first)
        val edited = file.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(reopened.decodeReport(edited) { AuthoredChallenge(spec, keys.privateKey) } is ChallengeFileResult.Failed)
        val other = author.createAuthorKeys()
        assertTrue(reopened.decodeReport(file) { AuthoredChallenge(spec, other.privateKey) } is ChallengeFileResult.Failed)
    }
}
