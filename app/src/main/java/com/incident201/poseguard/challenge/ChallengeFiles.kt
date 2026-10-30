package com.incident201.poseguard.challenge

import com.incident201.poseguard.challenge.codec.ChallengeCodec
import com.incident201.poseguard.challenge.codec.ReportCodec
import com.incident201.poseguard.challenge.codec.TlvFormatException
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Debug builds exchange files only with debug builds. */
enum class ChallengeDomain(val code: Int) { Release(1), Dev(2) }

enum class ChallengeFileError {
    NotAChallengeFile,
    UnsupportedVersion,
    WrongDomain,
    /** Damaged, edited, or made by another build. */
    Damaged,
    /** A report for a challenge this device did not create. */
    UnknownChallenge
}

sealed class ChallengeFileResult<out T> {
    data class Ok<T>(val value: T) : ChallengeFileResult<T>()
    data class Failed(val error: ChallengeFileError) : ChallengeFileResult<Nothing>()
}

/** A challenge created on this device, with the key part that never leaves it. */
class AuthoredChallenge(val spec: ChallengeSpec, val authorPrivateKey: Blob)

/** Container layout: magic, version, domain, ids, then the sealed compressed TLV payload. */
class ChallengeFiles(
    private val sealer: ChallengeFileSealer,
    private val domain: ChallengeDomain
) {
    fun encodeTask(spec: ChallengeSpec): ByteArray {
        val header = taskHeader(spec.id)
        val payload = sealer.seal(deflate(ChallengeCodec.encode(spec)), taskContext(header, spec.id))
        return header + payload
    }

    fun decodeTask(file: ByteArray): ChallengeFileResult<ChallengeSpec> {
        val parsed = parseHeader(file, TASK_MAGIC) ?: return ChallengeFileResult.Failed(headerError(file, TASK_MAGIC))
        if (parsed.reportId != null) return ChallengeFileResult.Failed(ChallengeFileError.NotAChallengeFile)
        return try {
            val plain = sealer.open(parsed.payload, taskContext(parsed.header, parsed.challengeId))
            val spec = ChallengeCodec.decode(inflate(plain))
            if (spec.id != parsed.challengeId) ChallengeFileResult.Failed(ChallengeFileError.Damaged)
            else ChallengeFileResult.Ok(spec)
        } catch (e: Exception) {
            if (e is SealException || e is TlvFormatException || e is java.util.zip.DataFormatException) {
                ChallengeFileResult.Failed(ChallengeFileError.Damaged)
            } else {
                throw e
            }
        }
    }

    fun encodeReport(report: ChallengeReport, spec: ChallengeSpec): ByteArray {
        require(report.challengeId == spec.id)
        val header = reportHeader(spec.id, report.reportId)
        val context = SealContext(
            kind = ChallengeFileKind.Report,
            header = header,
            challengeId = spec.id,
            reportId = report.reportId,
            taskSecret = spec.taskSecret,
            authorPublicKey = spec.authorPublicKey,
            authorPrivateKey = null
        )
        return header + sealer.seal(deflate(ReportCodec.encode(report)), context)
    }

    /** Opens a report with the challenge it answers, which [lookup] finds among the author's own. */
    fun decodeReport(
        file: ByteArray,
        lookup: (Blob) -> AuthoredChallenge?
    ): ChallengeFileResult<Pair<ChallengeReport, AuthoredChallenge>> {
        val parsed = parseHeader(file, REPORT_MAGIC) ?: return ChallengeFileResult.Failed(headerError(file, REPORT_MAGIC))
        val reportId = parsed.reportId ?: return ChallengeFileResult.Failed(ChallengeFileError.NotAChallengeFile)
        val authored = lookup(parsed.challengeId) ?: return ChallengeFileResult.Failed(ChallengeFileError.UnknownChallenge)
        val context = SealContext(
            kind = ChallengeFileKind.Report,
            header = parsed.header,
            challengeId = parsed.challengeId,
            reportId = reportId,
            taskSecret = authored.spec.taskSecret,
            authorPublicKey = authored.spec.authorPublicKey,
            authorPrivateKey = authored.authorPrivateKey
        )
        return try {
            val report = ReportCodec.decode(inflate(sealer.open(parsed.payload, context)))
            if (report.challengeId != parsed.challengeId || report.reportId != reportId) {
                ChallengeFileResult.Failed(ChallengeFileError.Damaged)
            } else {
                ChallengeFileResult.Ok(report to authored)
            }
        } catch (e: Exception) {
            if (e is SealException || e is TlvFormatException || e is java.util.zip.DataFormatException) {
                ChallengeFileResult.Failed(ChallengeFileError.Damaged)
            } else {
                throw e
            }
        }
    }

    fun kindOf(file: ByteArray): ChallengeFileKind? = when {
        file.startsWith(TASK_MAGIC) -> ChallengeFileKind.Task
        file.startsWith(REPORT_MAGIC) -> ChallengeFileKind.Report
        else -> null
    }

    /** For deleting local inbox files only; this header does not authenticate a report. */
    internal fun reportChallengeId(header: ByteArray): Blob? = parseHeader(header, REPORT_MAGIC)?.challengeId

    private fun taskContext(header: ByteArray, id: Blob) = SealContext(
        kind = ChallengeFileKind.Task,
        header = header,
        challengeId = id,
        reportId = null,
        taskSecret = null,
        authorPublicKey = null,
        authorPrivateKey = null
    )

    private fun taskHeader(id: Blob): ByteArray = TASK_MAGIC + byteArrayOf(FORMAT_VERSION.toByte(), domain.code.toByte()) + id.bytes

    private fun reportHeader(id: Blob, reportId: Blob): ByteArray =
        REPORT_MAGIC + byteArrayOf(FORMAT_VERSION.toByte(), domain.code.toByte()) + id.bytes + reportId.bytes

    private class ParsedHeader(val header: ByteArray, val challengeId: Blob, val reportId: Blob?, val payload: ByteArray)

    private fun parseHeader(file: ByteArray, magic: ByteArray): ParsedHeader? {
        if (!file.startsWith(magic)) return null
        val idsSize = if (magic.contentEquals(REPORT_MAGIC)) ChallengeSpec.ID_SIZE * 2 else ChallengeSpec.ID_SIZE
        val headerSize = magic.size + 2 + idsSize
        if (file.size < headerSize) return null
        if (file[magic.size].toInt() != FORMAT_VERSION || file[magic.size + 1].toInt() != domain.code) return null
        val idStart = magic.size + 2
        val challengeId = Blob(file.copyOfRange(idStart, idStart + ChallengeSpec.ID_SIZE))
        val reportId = if (idsSize > ChallengeSpec.ID_SIZE) {
            Blob(file.copyOfRange(idStart + ChallengeSpec.ID_SIZE, headerSize))
        } else {
            null
        }
        return ParsedHeader(file.copyOfRange(0, headerSize), challengeId, reportId, file.copyOfRange(headerSize, file.size))
    }

    private fun headerError(file: ByteArray, magic: ByteArray): ChallengeFileError = when {
        !file.startsWith(magic) -> ChallengeFileError.NotAChallengeFile
        file.size < magic.size + 2 -> ChallengeFileError.Damaged
        file[magic.size].toInt() != FORMAT_VERSION -> ChallengeFileError.UnsupportedVersion
        file[magic.size + 1].toInt() != domain.code -> ChallengeFileError.WrongDomain
        else -> ChallengeFileError.Damaged
    }

    companion object {
        const val FORMAT_VERSION = 2
        const val TEXT_CODE_PREFIX = "PGT2:"
        const val TASK_EXTENSION = "pgtask"
        const val REPORT_EXTENSION = "pgreport"
        /** Upper bound for an inflated payload; a 30 minute trace is a few megabytes. */
        const val MAX_INFLATED_BYTES = 64 * 1024 * 1024
        private const val INFLATE_INITIAL_CAPACITY = 1024 * 1024
        private val TASK_MAGIC = byteArrayOf('P'.code.toByte(), 'G'.code.toByte(), 'T'.code.toByte())
        private val REPORT_MAGIC = byteArrayOf('P'.code.toByte(), 'G'.code.toByte(), 'R'.code.toByte())
        internal val REPORT_HEADER_SIZE = REPORT_MAGIC.size + 2 + ChallengeSpec.ID_SIZE * 2

        fun toTextCode(taskFile: ByteArray): String =
            TEXT_CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(taskFile)

        /**
         * The challenge file in imported data: the data itself, or the task of a text code in it.
         * The code prefix starts with the task magic, so a binary file is told apart by its version
         * byte, a control character that text does not contain there.
         */
        fun fileBytes(data: ByteArray): ByteArray? {
            val binary = (data.startsWith(TASK_MAGIC) || data.startsWith(REPORT_MAGIC)) &&
                data.size > TASK_MAGIC.size && data[TASK_MAGIC.size].toInt() in 0 until 0x20
            return if (binary) data else fromTextCode(data.toString(Charsets.UTF_8))
        }

        /** True when [text] holds a text code, readable or not, so a cut-off code reads as damaged. */
        fun mentionsTextCode(text: String): Boolean = text.contains(TEXT_CODE_PREFIX)

        /** Finds a text code anywhere in pasted text, so a whole chat message can be pasted. */
        fun fromTextCode(text: String): ByteArray? {
            val start = text.indexOf(TEXT_CODE_PREFIX)
            if (start < 0) return null
            val body = text.substring(start + TEXT_CODE_PREFIX.length)
                .takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            if (body.isEmpty()) return null
            return runCatching { Base64.getUrlDecoder().decode(body) }.getOrNull()
        }

        internal fun deflate(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            try {
                deflater.setInput(data)
                deflater.finish()
                val out = ByteArrayOutputStream(data.size / 2 + 64)
                val buffer = ByteArray(64 * 1024)
                while (!deflater.finished()) {
                    out.write(buffer, 0, deflater.deflate(buffer))
                }
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        internal fun inflate(data: ByteArray): ByteArray {
            val inflater = Inflater()
            try {
                inflater.setInput(data)
                // The size check below runs per chunk; do not reserve the bound up front.
                val out = ByteArrayOutputStream(minOf(data.size.toLong() * 3 + 64, INFLATE_INITIAL_CAPACITY.toLong()).toInt())
                val buffer = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                        throw TlvFormatException("Truncated payload")
                    }
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_INFLATED_BYTES) throw TlvFormatException("Payload too large")
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
