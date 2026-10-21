package com.incident201.poseguard.challenge.crypto

import android.content.Context
import android.content.pm.PackageManager
import com.incident201.poseguard.BuildConfig
import java.security.MessageDigest

/**
 * Reproducible across installations/flavors signed with the same certificate and model.
 * Version-independent so app updates do not orphan existing tasks and author reports.
 * The material is recoverable from an APK; split fragments only deter casual inspection.
 * Re-signing or replacing the model changes the key unless the attacker patches this code.
 */
internal object AppKey {
    fun derive(context: Context): ByteArray {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = requireNotNull(info.signingInfo).apkContentsSigners
            .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
            .sortedBy { bytes -> bytes.joinToString("") { "%02x".format(it.toInt() and 255) } }
        require(signers.isNotEmpty())
        val model = context.assets.open("pose_landmarker_heavy.task").use { stream ->
            val slice = ByteArray(8192)
            var offset = 0
            while (offset < slice.size) {
                val read = stream.read(slice, offset, slice.size - offset)
                if (read < 0) break
                offset += read
            }
            require(offset == slice.size)
            MessageDigest.getInstance("SHA-256").digest(slice)
        }
        val certificate = signers.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
        val material = byteArrayOf(0x73, 0x21, 0x46, 0x17, 0x38, 0x6a, 0x5d, 0x02) +
            Envelope.domainFragment + DeviceKeyBox.domainFragment + model
        return Envelope.derive(material, certificate,
            ("PoseGuard/challenges/v2/" + if (BuildConfig.DEBUG) "dev" else "release").toByteArray())
    }
}
