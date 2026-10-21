package com.incident201.poseguard.challenge.crypto

import com.incident201.poseguard.challenge.*
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Platform primitives; HKDF-SHA256 is RFC 5869 with a single 32-byte output block. */
internal object Envelope {
    fun mac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    fun derive(input: ByteArray, salt: ByteArray, info: ByteArray): ByteArray =
        mac(mac(if (salt.isEmpty()) ByteArray(32) else salt, input), info + byteArrayOf(1))

    fun encrypt(key: ByteArray, data: ByteArray, aad: ByteArray, random: SecureRandom): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        return nonce + cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(data)
    }

    fun decrypt(key: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        require(data.size >= 28)
        return cipher(Cipher.DECRYPT_MODE, key, data.copyOfRange(0, 12), aad)
            .doFinal(data, 12, data.size - 12)
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }

    fun keyPair(random: SecureRandom) = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), random)
        generateKeyPair()
    }

    fun publicKey(encoded: ByteArray): PublicKey {
        require(encoded.size in 80..120)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) as java.security.interfaces.ECPublicKey
        require(key.params.order == java.math.BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16))
        return key
    }

    fun privateKey(encoded: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))

    fun agree(privateKey: PrivateKey, publicKey: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(privateKey)
        doPhase(publicKey, true)
        generateSecret()
    }

    // A public obfuscation fragment, not a server secret or an authentication credential.
    internal val domainFragment: ByteArray get() = byteArrayOf(0x35, 0x19, 0x62, 0x27, 0x51, 0x0c, 0x43, 0x6e)
}

/**
 * Tasks: per-task AES-GCM. Reports: task/app MAC inside ephemeral P-256 ECDH + AES-GCM.
 * Header, task id, report id and recipient key are authenticated. No plaintext fallback.
 * App-key extraction remains possible on a controlled device; this is an offline game
 * deterrent, not proof that camera data came from an unmodified app.
 */
class EncryptedChallengeFileSealer(
    appKey: ByteArray,
    private val random: SecureRandom = SecureRandom()
) : ChallengeFileSealer {
    private val appKey = appKey.copyOf().also { require(it.size == 32) }

    override fun createAuthorKeys(): AuthorKeys = Envelope.keyPair(random).let {
        AuthorKeys(Blob(it.public.encoded), Blob(it.private.encoded))
    }

    override fun seal(payload: ByteArray, context: SealContext): ByteArray = guarded {
        val aad = binding(context)
        if (context.kind == ChallengeFileKind.Task) {
            Envelope.encrypt(taskKey(context), payload, aad, random)
        } else {
            val recipient = requireNotNull(context.authorPublicKey).bytes
            val ephemeral = Envelope.keyPair(random)
            val publicBytes = ephemeral.public.encoded
            require(publicBytes.size <= 255)
            val prefix = byteArrayOf(publicBytes.size.toByte()) + publicBytes
            val shared = Envelope.agree(ephemeral.private, Envelope.publicKey(recipient))
            val key = Envelope.derive(shared, reportKey(context), "report-envelope-v2".toByteArray() + aad + prefix + recipient)
            val signed = payload + Envelope.mac(reportKey(context), aad + payload)
            prefix + Envelope.encrypt(key, signed, aad + prefix + recipient, random)
        }
    }

    override fun open(sealed: ByteArray, context: SealContext): ByteArray = guarded {
        val aad = binding(context)
        if (context.kind == ChallengeFileKind.Task) {
            Envelope.decrypt(taskKey(context), sealed, aad)
        } else {
            require(sealed.isNotEmpty())
            val size = sealed[0].toInt() and 255
            require(size in 1..200 && sealed.size >= 1 + size + 28)
            val prefix = sealed.copyOfRange(0, 1 + size)
            val recipient = requireNotNull(context.authorPublicKey).bytes
            val privateKey = Envelope.privateKey(requireNotNull(context.authorPrivateKey).bytes)
            val shared = Envelope.agree(privateKey, Envelope.publicKey(prefix.copyOfRange(1, prefix.size)))
            val key = Envelope.derive(shared, reportKey(context), "report-envelope-v2".toByteArray() + aad + prefix + recipient)
            val signed = Envelope.decrypt(key, sealed.copyOfRange(prefix.size, sealed.size), aad + prefix + recipient)
            require(signed.size >= 32)
            val payload = signed.copyOfRange(0, signed.size - 32)
            require(MessageDigest.isEqual(Envelope.mac(reportKey(context), aad + payload), signed.copyOfRange(payload.size, signed.size)))
            payload
        }
    }

    private fun taskKey(c: SealContext) = Envelope.derive(appKey, c.challengeId.bytes, "task-v2".toByteArray())
    private fun reportKey(c: SealContext): ByteArray {
        val secret = requireNotNull(c.taskSecret).bytes
        require(secret.size == ChallengeSpec.SECRET_SIZE)
        return Envelope.derive(appKey, secret, "report-auth-v2".toByteArray() + c.challengeId.bytes)
    }
    private fun binding(c: SealContext): ByteArray {
        require(c.challengeId.size == ChallengeSpec.ID_SIZE)
        if (c.kind == ChallengeFileKind.Report) require(c.reportId?.size == ChallengeSpec.ID_SIZE)
        return c.header + c.challengeId.bytes + (c.reportId?.bytes ?: byteArrayOf())
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: Exception) {
        throw SealException("Unable to authenticate challenge file")
    }
}
