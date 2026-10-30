package com.incident201.poseguard.challenge

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.incident201.poseguard.BuildConfig
import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.challenge.codec.ChallengeCodec
import com.incident201.poseguard.challenge.codec.ReportCodec
import com.incident201.poseguard.challenge.codec.TlvReader
import com.incident201.poseguard.challenge.codec.TlvWriter
import java.io.DataInputStream
import java.io.File
import java.security.SecureRandom

/** A challenge received from someone else, with this device's attempt counter. */
data class ReceivedChallenge(val spec: ChallengeSpec, val attemptsUsed: Int, val receivedAtMs: Long) {
    val attemptsLeft: Int? get() = if (spec.attemptsAllowed > 0) (spec.attemptsAllowed - attemptsUsed).coerceAtLeast(0) else null
}

/** A report this device produced and may still send. */
data class OutgoingReport(val report: ChallengeReport, val challengeTitle: String)

/** A report received for one of the author's own challenges. */
data class IncomingReport(val report: ChallengeReport, val challenge: AuthoredChallenge, val receivedAtMs: Long)

sealed class ImportResult {
    data class TaskImported(val challenge: ReceivedChallenge, val alreadyKnown: Boolean) : ImportResult()
    data class OwnTask(val challenge: AuthoredChallenge) : ImportResult()
    data class ReportImported(val report: IncomingReport, val alreadyKnown: Boolean) : ImportResult()
    data class Failed(val error: ChallengeFileError) : ImportResult()
}

/**
 * File storage under filesDir/challenges. Files are written by this app only and are
 * protected by the app sandbox; the author's private key part lives in authored/.
 * All methods do blocking I/O: call them off the main thread.
 */
class ChallengeRepository(
    context: Context,
    private val sealer: ChallengeFileSealer = com.incident201.poseguard.challenge.crypto.AndroidChallengeFileSealer(context),
    domain: ChallengeDomain = if (BuildConfig.DEBUG) ChallengeDomain.Dev else ChallengeDomain.Release,
    /** Tests give each simulated participant its own storage. */
    storageName: String = DEFAULT_STORAGE
) {
    private val root = File(context.filesDir, storageName)
    private val authoredDir = File(root, "authored")
    private val receivedDir = File(root, "received")
    private val outgoingDir = File(root, "reports_out")
    private val incomingDir = File(root, "reports_in")
    private val reportContextDir = File(root, "report_context")
    private val audioDir = File(root, "audio")
    private val prefs = context.getSharedPreferences(
        if (storageName == DEFAULT_STORAGE) "challenge_state" else "challenge_state_$storageName",
        Context.MODE_PRIVATE
    )
    private val random = SecureRandom()
    private val lock = Any()

    val files = ChallengeFiles(sealer, domain)

    var executorName: String
        get() = prefs.getString(PREF_EXECUTOR_NAME, "").orEmpty()
        set(value) = prefs.edit().putString(PREF_EXECUTOR_NAME, value.take(ChallengeSpec.MAX_AUTHOR_LENGTH)).apply()

    // Authored challenges

    fun createChallenge(
        title: String,
        description: String,
        authorName: String,
        rules: ChallengeRules,
        visibility: ChallengeVisibility,
        attemptsAllowed: Int,
        nowMs: Long = System.currentTimeMillis()
    ): AuthoredChallenge {
        val keys = sealer.createAuthorKeys()
        val spec = ChallengeSpec(
            id = Blob(randomBytes(ChallengeSpec.ID_SIZE)),
            title = title.trim().take(ChallengeSpec.MAX_TITLE_LENGTH),
            description = description.trim().take(ChallengeSpec.MAX_DESCRIPTION_LENGTH),
            authorName = authorName.trim().take(ChallengeSpec.MAX_AUTHOR_LENGTH),
            createdAtMs = nowMs,
            rules = rules.normalized(),
            visibility = visibility,
            attemptsAllowed = attemptsAllowed.coerceIn(0, 99),
            taskSecret = Blob(randomBytes(ChallengeSpec.SECRET_SIZE)),
            authorPublicKey = keys.publicKey
        )
        val authored = AuthoredChallenge(spec, keys.privateKey)
        synchronized(lock) {
            writeAtomically(File(authoredDir, spec.id.toHex() + ".bin"), TlvWriter().apply {
                bytes(1, ChallengeCodec.encode(spec))
                bytes(2, keys.privateKey.bytes)
            }.toByteArray())
        }
        return authored
    }

    fun authoredChallenges(): List<AuthoredChallenge> = synchronized(lock) {
        listFiles(authoredDir, ".bin").mapNotNull(::readAuthored).sortedByDescending { it.spec.createdAtMs }
    }

    fun authoredChallenge(id: Blob): AuthoredChallenge? = synchronized(lock) {
        readAuthored(File(authoredDir, id.toHex() + ".bin"))
    }

    fun deleteAuthored(id: Blob) = synchronized(lock) {
        preserveOutgoingContext(id)
        // Select by the container header, without needing the key we are deleting or
        // inflating every recording in the inbox. This also removes old orphan files.
        listFiles(incomingDir, ".pgreport").filter { file ->
            runCatching {
                val header = ByteArray(ChallengeFiles.REPORT_HEADER_SIZE)
                DataInputStream(file.inputStream()).use { it.readFully(header) }
                files.reportChallengeId(header) == id
            }.getOrDefault(false)
        }.forEach { it.delete() }
        File(authoredDir, id.toHex() + ".bin").delete()
        File(audioDir, id.toHex()).deleteRecursively()
    }

    fun taskFile(spec: ChallengeSpec): ByteArray = files.encodeTask(spec)

    private fun readAuthored(file: File): AuthoredChallenge? = runCatching {
        if (!file.isFile) return null
        val r = TlvReader(file.readBytes())
        AuthoredChallenge(ChallengeCodec.decode(r.requireBytes(1)), Blob(r.requireBytes(2)))
    }.getOrNull()

    // Import

    /** Accepts a task file, a report file or text with a task code. */
    fun import(data: ByteArray, nowMs: Long = System.currentTimeMillis()): ImportResult {
        val bytes = ChallengeFiles.fileBytes(data)
        return when (bytes?.let(files::kindOf)) {
            ChallengeFileKind.Task -> importTask(bytes, nowMs)
            ChallengeFileKind.Report -> importReport(bytes, nowMs)
            // A code cut off by a messenger is damaged, not something else.
            null -> ImportResult.Failed(
                if (ChallengeFiles.mentionsTextCode(data.toString(Charsets.UTF_8))) ChallengeFileError.Damaged
                else ChallengeFileError.NotAChallengeFile
            )
        }
    }

    private fun importTask(bytes: ByteArray, nowMs: Long): ImportResult {
        val spec = when (val result = files.decodeTask(bytes)) {
            is ChallengeFileResult.Ok -> result.value
            is ChallengeFileResult.Failed -> return ImportResult.Failed(result.error)
        }
        authoredChallenge(spec.id)?.let { return ImportResult.OwnTask(it) }
        synchronized(lock) {
            val file = File(receivedDir, spec.id.toHex() + ".pgtask")
            val known = file.isFile
            if (!known) {
                writeAtomically(file, bytes)
                prefs.edit().putLong(PREF_RECEIVED_AT + spec.id.toHex(), nowMs).apply()
            }
            return ImportResult.TaskImported(receivedChallenge(spec), alreadyKnown = known)
        }
    }

    private fun importReport(bytes: ByteArray, nowMs: Long): ImportResult {
        val (report, authored) = when (val result = files.decodeReport(bytes, ::authoredChallenge)) {
            is ChallengeFileResult.Ok -> result.value
            is ChallengeFileResult.Failed -> return ImportResult.Failed(result.error)
        }
        synchronized(lock) {
            val file = File(incomingDir, report.reportId.toHex() + ".pgreport")
            val known = file.isFile
            if (!known) {
                writeAtomically(file, bytes)
                prefs.edit().putLong(PREF_RECEIVED_AT + report.reportId.toHex(), nowMs).apply()
            }
            return ImportResult.ReportImported(IncomingReport(report, authored, receivedAt(report.reportId)), known)
        }
    }

    // Received challenges

    fun receivedChallenges(): List<ReceivedChallenge> = synchronized(lock) {
        listFiles(receivedDir, ".pgtask").mapNotNull { file ->
            (files.decodeTask(file.readBytes()) as? ChallengeFileResult.Ok)?.value?.let(::receivedChallenge)
        }.sortedByDescending { it.receivedAtMs }
    }

    fun receivedChallenge(id: Blob): ReceivedChallenge? = synchronized(lock) {
        val file = File(receivedDir, id.toHex() + ".pgtask")
        if (!file.isFile) return null
        (files.decodeTask(file.readBytes()) as? ChallengeFileResult.Ok)?.value?.let(::receivedChallenge)
    }

    fun deleteReceived(id: Blob) = synchronized(lock) {
        preserveOutgoingContext(id)
        File(receivedDir, id.toHex() + ".pgtask").delete()
        File(audioDir, id.toHex()).deleteRecursively()
    }

    /** Writes the challenge's embedded clips to private files and returns playable URIs. */
    fun audioFiles(spec: ChallengeSpec): Map<AudioCue, String> = synchronized(lock) {
        val dir = File(audioDir, spec.id.toHex())
        spec.rules.audioClips.mapNotNull { (cue, clip) ->
            val file = File(dir, cue.name)
            runCatching {
                if (!file.isFile || file.length() != clip.data.size.toLong()) writeAtomically(file, clip.data.bytes)
                cue to Uri.fromFile(file).toString()
            }.getOrNull()
        }.toMap()
    }

    /**
     * Counts an attempt when a session starts. Returns its number, or null when no attempts
     * are left. Attempts at an own challenge are not limited.
     */
    // commit(): the attempt must be on disk before the session starts, even if the app dies.
    @SuppressLint("ApplySharedPref")
    fun beginAttempt(spec: ChallengeSpec): Int? = synchronized(lock) {
        val key = PREF_ATTEMPTS + spec.id.toHex()
        val next = prefs.getInt(key, 0) + 1
        val own = File(authoredDir, spec.id.toHex() + ".bin").isFile
        if (!own && spec.attemptsAllowed > 0 && next > spec.attemptsAllowed) return null
        prefs.edit().putInt(key, next).commit()
        next
    }

    private fun receivedChallenge(spec: ChallengeSpec) = ReceivedChallenge(
        spec = spec,
        attemptsUsed = prefs.getInt(PREF_ATTEMPTS + spec.id.toHex(), 0),
        receivedAtMs = prefs.getLong(PREF_RECEIVED_AT + spec.id.toHex(), 0L)
    )

    /** The challenge an executor may run: a received one, or the author's own for a test run. */
    fun runnableChallenge(id: Blob): ChallengeSpec? =
        receivedChallenge(id)?.spec ?: authoredChallenge(id)?.spec

    // Reports

    fun saveOutgoingReport(report: ChallengeReport, spec: ChallengeSpec? = null) = synchronized(lock) {
        (spec ?: runnableChallenge(report.challengeId))?.let {
            require(it.id == report.challengeId)
            saveReportContext(it)
        }
        writeAtomically(File(outgoingDir, report.reportId.toHex() + ".report"), ReportCodec.encode(report))
    }

    fun outgoingReports(): List<OutgoingReport> = synchronized(lock) {
        listFiles(outgoingDir, ".report").mapNotNull { file ->
            val report = runCatching { ReportCodec.decode(file.readBytes()) }.getOrNull() ?: return@mapNotNull null
            OutgoingReport(report, (readReportContext(report.challengeId) ?: runnableChallenge(report.challengeId))?.title.orEmpty())
        }.sortedByDescending { it.report.endedAtWallMs }
    }

    fun deleteOutgoing(reportId: Blob) = synchronized(lock) {
        val file = File(outgoingDir, reportId.toHex() + ".report")
        val id = runCatching { ReportCodec.decode(file.readBytes()).challengeId }.getOrNull()
        if (file.delete() && id != null && !hasOutgoingReports(id)) {
            File(reportContextDir, id.toHex() + ".bin").delete()
        }
    }

    /** Sealed report file for the author, with the executor's name and comment. */
    fun reportFile(report: ChallengeReport, executorName: String, comment: String): ByteArray? = synchronized(lock) {
        val spec = readReportContext(report.challengeId) ?: runnableChallenge(report.challengeId) ?: return@synchronized null
        val named = report.copy(
            executorName = executorName.trim().take(ReportCodec.MAX_TEXT_LENGTH),
            executorComment = comment.trim().take(ReportCodec.MAX_TEXT_LENGTH)
        )
        files.encodeReport(named, spec)
    }

    /** Only the export context is retained, never another copy of embedded audio. */
    private fun saveReportContext(spec: ChallengeSpec) {
        writeAtomically(File(reportContextDir, spec.id.toHex() + ".bin"),
            ChallengeCodec.encode(spec.copy(rules = spec.rules.copy(audioClips = emptyMap()))))
    }

    private fun readReportContext(id: Blob): ChallengeSpec? = runCatching {
        ChallengeCodec.decode(File(reportContextDir, id.toHex() + ".bin").readBytes()).takeIf { it.id == id }
    }.getOrNull()

    /** Reports saved by older versions have no export context yet. */
    private fun preserveOutgoingContext(id: Blob) {
        if (readReportContext(id) != null) return
        if (hasOutgoingReports(id)) runnableChallenge(id)?.let(::saveReportContext)
    }

    private fun hasOutgoingReports(id: Blob): Boolean = listFiles(outgoingDir, ".report").any {
        runCatching { ReportCodec.decode(it.readBytes()).challengeId == id }.getOrDefault(false)
    }

    fun incomingReports(): List<IncomingReport> = synchronized(lock) {
        listFiles(incomingDir, ".pgreport").mapNotNull { file ->
            val result = files.decodeReport(file.readBytes(), ::authoredChallengeUnlocked)
            (result as? ChallengeFileResult.Ok)?.value?.let { (report, authored) ->
                IncomingReport(report, authored, receivedAt(report.reportId))
            }
        }.sortedByDescending { it.receivedAtMs }
    }

    /** One received report; opens only its own file. */
    fun incomingReport(reportId: Blob): IncomingReport? = synchronized(lock) {
        val file = File(incomingDir, reportId.toHex() + ".pgreport")
        if (!file.isFile) return@synchronized null
        val result = files.decodeReport(file.readBytes(), ::authoredChallengeUnlocked)
        (result as? ChallengeFileResult.Ok)?.value
            ?.takeIf { (report, _) -> report.reportId == reportId }
            ?.let { (report, authored) -> IncomingReport(report, authored, receivedAt(report.reportId)) }
    }

    fun deleteIncoming(reportId: Blob) = synchronized(lock) {
        File(incomingDir, reportId.toHex() + ".pgreport").delete()
    }

    private fun authoredChallengeUnlocked(id: Blob): AuthoredChallenge? = readAuthored(File(authoredDir, id.toHex() + ".bin"))

    private fun receivedAt(id: Blob): Long = prefs.getLong(PREF_RECEIVED_AT + id.toHex(), 0L)

    // Helpers

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private fun listFiles(dir: File, suffix: String): List<File> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(suffix) }?.toList().orEmpty()

    private fun writeAtomically(file: File, data: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("Unable to write ${file.name}")
        }
    }

    /** Removes everything this repository stored; for tests with their own [storageName]. */
    internal fun deleteStorage() = synchronized(lock) {
        root.deleteRecursively()
        prefs.edit().clear().commit()
    }

    private companion object {
        const val DEFAULT_STORAGE = "challenges"
        const val PREF_EXECUTOR_NAME = "executor_name"
        const val PREF_ATTEMPTS = "attempts_"
        const val PREF_RECEIVED_AT = "received_at_"
    }
}
