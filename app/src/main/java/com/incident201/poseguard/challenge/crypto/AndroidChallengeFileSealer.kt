package com.incident201.poseguard.challenge.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.incident201.poseguard.challenge.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Private author keys are wrapped with a non-exportable, device-local Android Keystore key. */
internal object DeviceKeyBox {
    private const val ALIAS = "poseguard.challenge.author.v2"
    internal val domainFragment: ByteArray get() = byteArrayOf(0x1b, 0x58, 0x3f, 0x66, 0x04, 0x2d, 0x71, 0x49)

    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    fun wrap(data: ByteArray, recipient: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key())
        updateAAD(recipient)
        iv + doFinal(data)
    }
    fun unwrap(data: ByteArray, recipient: ByteArray): ByteArray {
        require(data.size >= 28)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            updateAAD(recipient)
            doFinal(data, 12, data.size - 12)
        }
    }
}

class AndroidChallengeFileSealer(context: Context) : ChallengeFileSealer {
    private val appContext = context.applicationContext
    private val delegate by lazy { EncryptedChallengeFileSealer(AppKey.derive(appContext)) }

    override fun createAuthorKeys(): AuthorKeys {
        val keys = delegate.createAuthorKeys()
        return try {
            AuthorKeys(keys.publicKey, Blob(DeviceKeyBox.wrap(keys.privateKey.bytes, keys.publicKey.bytes)))
        } finally { keys.privateKey.bytes.fill(0) }
    }
    override fun seal(payload: ByteArray, context: SealContext): ByteArray = delegate.seal(payload, context)
    override fun open(sealed: ByteArray, context: SealContext): ByteArray {
        if (context.kind == ChallengeFileKind.Task) return delegate.open(sealed, context)
        val privateKey = try {
            DeviceKeyBox.unwrap(requireNotNull(context.authorPrivateKey).bytes, requireNotNull(context.authorPublicKey).bytes)
        } catch (e: Exception) { throw SealException("Author key unavailable on this device") }
        return try {
            delegate.open(sealed, SealContext(context.kind, context.header, context.challengeId, context.reportId,
                context.taskSecret, context.authorPublicKey, Blob(privateKey)))
        } finally { privateKey.fill(0) }
    }
}
