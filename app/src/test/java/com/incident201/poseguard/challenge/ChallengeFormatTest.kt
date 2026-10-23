package com.incident201.poseguard.challenge

import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.audio.AudioCueMode
import com.incident201.poseguard.audio.AudioCueSettings
import com.incident201.poseguard.audio.TtsPhraseTemplate
import com.incident201.poseguard.challenge.codec.ChallengeCodec
import com.incident201.poseguard.challenge.codec.ReportCodec
import com.incident201.poseguard.challenge.codec.TlvReader
import com.incident201.poseguard.challenge.codec.TlvWriter
import com.incident201.poseguard.intiface.IntifaceViolationMode
import com.incident201.poseguard.tracker.FaceDetectionStatus
import com.incident201.poseguard.tracker.Point3D
import com.incident201.poseguard.tracker.PoseLandmarks
import com.incident201.poseguard.viewmodel.FaceCheckMode
import com.incident201.poseguard.viewmodel.GameSettings
import com.incident201.poseguard.viewmodel.RuleViolationCounts
import com.incident201.poseguard.viewmodel.TimerMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ChallengeFormatTest {
    private val sealer = com.incident201.poseguard.challenge.crypto.EncryptedChallengeFileSealer(ByteArray(32) { 7 })
    private val keys = sealer.createAuthorKeys()
    private val files = ChallengeFiles(sealer, ChallengeDomain.Dev)

    private fun spec(rules: ChallengeRules = ChallengeRules()) = ChallengeSpec(
        id = Blob(ByteArray(ChallengeSpec.ID_SIZE) { it.toByte() }),
        title = "Corner time",
        description = "Hold still.\nNo peeking.",
        authorName = "Author",
        createdAtMs = 1_700_000_000_000L,
        rules = rules,
        visibility = ChallengeVisibility(showDuration = false, showPenalties = true),
        attemptsAllowed = 3,
        taskSecret = Blob(ByteArray(ChallengeSpec.SECRET_SIZE) { (it * 7).toByte() }),
        authorPublicKey = keys.publicKey
    )

    private fun pose(seed: Int, jitter: Float = 0.001f): PoseLandmarks {
        val random = Random(seed)
        return PoseLandmarks.fromAllLandmarks(List(33) { i ->
            Point3D(0.3f + i * 0.01f + random.nextFloat() * jitter, 0.2f + i * 0.015f, -0.1f, 0.9f, 0.95f)
        })
    }

    @Test fun specSurvivesEncoding() {
        val rules = ChallengeRules(
            timer = TimerSpec(TimerMode.Random, 60, 600, 1200),
            maxViolations = 7,
            faceCheckMode = FaceCheckMode.FaceAwayFromCamera,
            customTtsTemplates = mapOf(TtsPhraseTemplate.TimeIsUp to "Готово, можно двигаться"),
            intifaceViolationMode = IntifaceViolationMode.Pause,
            intifaceRequired = true
        ).normalized()
        val original = spec(rules)
        assertEquals(original, ChallengeCodec.decode(ChallengeCodec.encode(original)))
    }

    @Test fun audioFileCuesWithoutAFileAreSpoken() {
        val settings = GameSettings(
            customizeAudioEnabled = true,
            audioCueSettings = AudioCue.entries.associateWith {
                AudioCueSettings(mode = AudioCueMode.AudioFile, audioFileUri = "content://media/1")
            }
        )
        val rules = ChallengeRules.from(settings, TimerSpec())
        assertTrue(rules.audioCueSettings.values.all { it.mode == AudioCueMode.UseTts && it.audioFileUri == null })
        assertTrue(rules.audioClips.isEmpty())
    }

    @Test fun embeddedAudioTravelsWithTheTask() {
        val clip = AudioClip("audio/ogg", Blob(ByteArray(5_000) { it.toByte() }))
        val rules = ChallengeRules(
            audioCueSettings = AudioCue.entries.associateWith { AudioCueSettings() } +
                (AudioCue.TimeIsUp to AudioCueSettings(mode = AudioCueMode.AudioFile)),
            audioClips = mapOf(AudioCue.TimeIsUp to clip, AudioCue.TakePosition to clip)
        ).normalized()
        // Only the cue that plays a file keeps its clip.
        assertEquals(mapOf(AudioCue.TimeIsUp to clip), rules.audioClips)
        assertTrue(rules.customizeAudioEnabled)
        val decoded = (files.decodeTask(files.encodeTask(spec(rules))) as ChallengeFileResult.Ok).value
        assertEquals(clip, decoded.rules.audioClips[AudioCue.TimeIsUp])

        val applied = decoded.rules.applyTo(GameSettings(), mapOf(AudioCue.TimeIsUp to "file:///clip"))
        assertEquals("file:///clip", applied.audioCueSettings[AudioCue.TimeIsUp]!!.audioFileUri)
        val withoutFile = decoded.rules.applyTo(GameSettings())
        assertEquals(AudioCueMode.UseTts, withoutFile.audioCueSettings[AudioCue.TimeIsUp]!!.mode)
    }

    @Test fun oversizedAudioIsDropped() {
        val big = AudioClip("audio/mpeg", Blob(ByteArray(AudioClip.MAX_BYTES + 1)))
        val rules = ChallengeRules(
            audioCueSettings = AudioCue.entries.associateWith { AudioCueSettings(mode = AudioCueMode.AudioFile) },
            audioClips = mapOf(AudioCue.TimeIsUp to big)
        ).normalized()
        assertTrue(rules.audioClips.isEmpty())
        assertTrue(rules.audioCueSettings.values.all { it.mode == AudioCueMode.UseTts })
    }

    @Test fun unknownTagsAreIgnored() {
        val encoded = ChallengeCodec.encode(spec())
        val extended = encoded + TlvWriter().apply { string(999, "from a newer version") }.toByteArray()
        assertEquals(spec(), ChallengeCodec.decode(extended))
        assertTrue(TlvReader(extended).has(999))
    }

    @Test fun taskFileAndTextCodeRoundTrip() {
        val file = files.encodeTask(spec())
        assertEquals(ChallengeFileKind.Task, files.kindOf(file))
        assertEquals(ChallengeFileResult.Ok(spec()), files.decodeTask(file))

        val code = ChallengeFiles.toTextCode(file)
        assertTrue(code.startsWith(ChallengeFiles.TEXT_CODE_PREFIX))
        val pasted = "Try this one: $code\nGood luck"
        assertArrayEquals(file, ChallengeFiles.fromTextCode(pasted))
    }

    @Test fun pastedTextCodeIsNotMistakenForABinaryFile() {
        val file = files.encodeTask(spec())
        val code = ChallengeFiles.toTextCode(file)
        // The code prefix begins with the task magic bytes.
        assertArrayEquals(file, ChallengeFiles.fileBytes(code.toByteArray(Charsets.UTF_8)))
        assertArrayEquals(file, ChallengeFiles.fileBytes("Try this one: $code".toByteArray(Charsets.UTF_8)))
        assertArrayEquals(file, ChallengeFiles.fileBytes(file))
        assertNull(ChallengeFiles.fileBytes("hello".toByteArray(Charsets.UTF_8)))
        assertEquals(ChallengeFileResult.Ok(spec()), files.decodeTask(ChallengeFiles.fileBytes(code.toByteArray(Charsets.UTF_8))!!))
    }

    @Test fun malformedFilesAreRejected() {
        val file = files.encodeTask(spec())
        assertEquals(ChallengeFileResult.Failed(ChallengeFileError.NotAChallengeFile), files.decodeTask("hello".toByteArray()))
        assertEquals(
            ChallengeFileResult.Failed(ChallengeFileError.WrongDomain),
            ChallengeFiles(sealer, ChallengeDomain.Release).decodeTask(file)
        )
        val truncated = file.copyOf(file.size - 5)
        assertEquals(ChallengeFileResult.Failed(ChallengeFileError.Damaged), files.decodeTask(truncated))
        val otherVersion = file.copyOf().also { it[3] = 9 }
        assertEquals(ChallengeFileResult.Failed(ChallengeFileError.UnsupportedVersion), files.decodeTask(otherVersion))
    }

    @Test fun quantizedPoseIsStableUnderRequantization() {
        val quantized = pose(1).quantizedForTrace()
        val trace = TracePose.from(quantized)!!
        assertEquals(trace, TracePose.from(quantized.quantizedForTrace()))
        val rebuilt = trace.toPoseLandmarks()
        TRACE_LANDMARK_INDICES.forEach { index ->
            assertEquals(quantized.allLandmarks[index].x, rebuilt.allLandmarks[index].x)
            assertEquals(quantized.allLandmarks[index].y, rebuilt.allLandmarks[index].y)
        }
        assertNull(TracePose.from(PoseLandmarks()))
    }

    @Test fun traceFramesRoundTrip() {
        val reference = TracePose.from(pose(0).quantizedForTrace())!!
        val writer = TraceFrameWriter(1_000L, reference, maxBytes = 1 shl 20)
        val expected = (1..200).map { i ->
            TraceFrame(
                timestampMs = 1_000L + i * 33L,
                evaluatedAtMs = 1_000L + i * 33L + (i % 7),
                faceStatus = FaceDetectionStatus.entries[i % 4],
                pose = if (i % 25 == 0) null else TracePose.from(pose(i).quantizedForTrace())
            )
        }
        expected.forEach(writer::append)
        val decoded = TraceFrameReader.decode(writer.toByteArray(), 1_000L, reference)
        assertEquals(expected.size, decoded.size)
        expected.zip(decoded).forEach { (e, d) ->
            assertEquals(e.timestampMs, d.timestampMs)
            assertEquals(e.evaluatedAtMs, d.evaluatedAtMs)
            assertEquals(e.faceStatus, d.faceStatus)
            assertEquals(e.pose, d.pose)
        }
    }

    @Test fun traceIndexGivesEveryFrameWithoutDecodingAllPoses() {
        val reference = TracePose.from(pose(0).quantizedForTrace())!!
        val writer = TraceFrameWriter(1_000L, reference, maxBytes = 1 shl 20)
        // Crosses several checkpoints, with dropouts on and right after them.
        val expected = (1..300).map { i ->
            TraceFrame(
                timestampMs = 1_000L + i * 33L,
                evaluatedAtMs = 1_000L + i * 33L + (i % 5),
                faceStatus = FaceDetectionStatus.FaceVisible,
                pose = if (i % TraceIndex.STRIDE in 0..1 || i % 17 == 0) null else TracePose.from(pose(i).quantizedForTrace())
            )
        }
        expected.forEach(writer::append)
        val index = TraceIndex.build(writer.toByteArray(), 1_000L, reference)
        assertEquals(expected.size, index.size)
        assertArrayEquals(expected.map { it.evaluatedAtMs }.toLongArray(), index.evaluatedAtMs)
        expected.indices.reversed().forEach { i -> assertEquals("frame $i", expected[i].pose, index.pose(i)) }
        assertEquals(0, TraceIndex.build(ByteArray(0), 1_000L, reference).size)
    }

    @Test fun traceStopsAtItsSizeLimit() {
        val reference = TracePose.from(pose(0).quantizedForTrace())!!
        val writer = TraceFrameWriter(0L, reference, maxBytes = 256)
        repeat(100) { i ->
            writer.append(TraceFrame(i * 33L, i * 33L, FaceDetectionStatus.NotProcessed, TracePose.from(pose(i, 0.05f))))
        }
        assertTrue(writer.truncated)
        assertEquals(writer.frameCount, TraceFrameReader.decode(writer.toByteArray(), 0L, reference).size)
    }

    @Test fun reportFileRoundTripAndLinksToItsChallenge() {
        val challenge = spec()
        val recorder = ChallengeSessionRecorder(challenge, attempt = 2, appVersion = "test", startedAtMonoMs = 10L, startedAtWallMs = 20L)
        val reference = pose(0).quantizedForTrace()
        recorder.startTrace(500L, reference, clockStartedAtMs = 510L, initialFaceStatus = FaceDetectionStatus.FaceVisible)
        repeat(30) { i -> recorder.frame(533L + i * 33L, 534L + i * 33L, FaceDetectionStatus.FaceVisible, pose(i).quantizedForTrace()) }
        recorder.violation(TraceViolationEvent(900L, TraceViolationType.Drift, 1, 1, defeat = false))
        val report = recorder.finish(
            endCause = SessionEndCause.Surrendered,
            endedAtMonoMs = 1_600L,
            endedAtWallMs = 1_610L,
            initialTimerSeconds = 180,
            actualTimerSeconds = 1,
            counts = RuleViolationCounts(drift = 1),
            intifaceConnected = false
        ).copy(executorName = "Executor", executorComment = "Tough one")

        val decodedTlv = ReportCodec.decode(ReportCodec.encode(report))
        assertEquals(report.copy(trace = null), decodedTlv.copy(trace = null))
        assertEquals(30, decodedTlv.trace!!.frames().size)

        val file = files.encodeReport(report, challenge)
        assertEquals(ChallengeFileKind.Report, files.kindOf(file))
        val authored = AuthoredChallenge(challenge, keys.privateKey)
        val opened = files.decodeReport(file) { id -> authored.takeIf { id == challenge.id } }
        assertTrue(opened is ChallengeFileResult.Ok)
        val (openedReport, openedChallenge) = (opened as ChallengeFileResult.Ok).value
        assertEquals(report.reportId, openedReport.reportId)
        assertEquals("Tough one", openedReport.executorComment)
        assertEquals(authored, openedChallenge)

        assertEquals(ChallengeFileResult.Failed(ChallengeFileError.UnknownChallenge), files.decodeReport(file) { null })
    }

    @Test fun sessionWithoutPoseHasNoTrace() {
        val recorder = ChallengeSessionRecorder(spec(), 1, "test", 0L, 0L)
        val report = recorder.finish(SessionEndCause.Surrendered, 5L, 5L, 180, 0, RuleViolationCounts(), false)
        assertNull(report.trace)
        assertNotNull(ReportCodec.decode(ReportCodec.encode(report)))
    }

    @Test fun challengeRulesOverrideOnlyTheAuthorsSettings() {
        val own = GameSettings(debugModeEnabled = true, maxViolations = 99, intifaceWebSocketUrl = "ws://own")
        val applied = ChallengeRules(maxViolations = 2).applyTo(own)
        assertEquals(2, applied.maxViolations)
        assertEquals("ws://own", applied.intifaceWebSocketUrl)
        assertEquals(false, applied.debugModeEnabled)
    }
}
