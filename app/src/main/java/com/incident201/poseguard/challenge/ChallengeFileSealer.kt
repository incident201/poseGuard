package com.incident201.poseguard.challenge


enum class ChallengeFileKind { Task, Report }

/** Everything a sealer may bind a file to. The container header carries the same ids. */
class SealContext(
    val kind: ChallengeFileKind,
    /** Container header bytes; a sealer should bind the payload to them. */
    val header: ByteArray,
    val challengeId: Blob,
    val reportId: Blob?,
    /** Task secret from the challenge, null for tasks. */
    val taskSecret: Blob?,
    /** Author key from the challenge (public part). */
    val authorPublicKey: Blob?,
    /** Author key kept only on the author's device (private part), null when sealing reports. */
    val authorPrivateKey: Blob?
)

class AuthorKeys(val publicKey: Blob, val privateKey: Blob)

class SealException(message: String) : Exception(message)

/**
 * The single place where challenge and report files get protected.
 * Everything else in the app works through this interface only.
 */
interface ChallengeFileSealer {
    /** Per-challenge key pair. The public part travels inside the task, the private part stays with the author. */
    fun createAuthorKeys(): AuthorKeys

    fun seal(payload: ByteArray, context: SealContext): ByteArray

    /** Throws [SealException] when the file cannot be opened by this app on this device. */
    fun open(sealed: ByteArray, context: SealContext): ByteArray
}

