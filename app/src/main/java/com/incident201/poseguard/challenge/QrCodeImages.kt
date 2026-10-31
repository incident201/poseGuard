package com.incident201.poseguard.challenge

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.util.UUID

internal object QrCodeImages {
    /** Lossless, opaque image with the QR quiet zone intact. Call on an IO worker. */
    fun save(context: Context, code: String): Uri {
        val bitmap = QrCodes.encode(code, 2048) ?: error("Task does not fit a QR code")
        val resolver = context.contentResolver
        var inserted: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "poseguard_qr_${UUID.randomUUID()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PoseGuard")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            ) ?: error("Cannot create QR image")
            inserted = uri
            resolver.openOutputStream(uri)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Cannot write QR image" }
            } ?: error("Cannot open QR image")
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null) == 1) { "Cannot publish QR image" }
            return uri
        } catch (failure: Exception) {
            inserted?.let { runCatching { resolver.delete(it, null, null) } }
            throw failure
        } finally {
            bitmap.recycle()
        }
    }
}
