package com.incident201.poseguard.challenge

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR codes carry the same `PGT2:` text code as chats, so scanning and pasting share one path. */
object QrCodes {
    /**
     * Longest text code shown as a QR code (QR version 29 at level L). Longer tasks are
     * shared as a file or a text code, which have no such limit.
     */
    const val MAX_TEXT_LENGTH = 1_500

    /** Up to this length the code also fits level M, which tolerates more glare and blur. */
    private const val MEDIUM_CORRECTION_MAX_LENGTH = 1_100

    fun canEncode(text: String): Boolean = text.length <= MAX_TEXT_LENGTH

    fun encode(text: String, sizePx: Int): Bitmap? {
        if (!canEncode(text)) return null
        val matrix = runCatching {
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to
                        if (text.length <= MEDIUM_CORRECTION_MAX_LENGTH) ErrorCorrectionLevel.M else ErrorCorrectionLevel.L,
                    // The quiet zone the QR standard asks for.
                    EncodeHintType.MARGIN to 4
                )
            )
        }.getOrNull() ?: return null
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }

    /** Returns the text of a QR code in [bitmap], or null. Runs on the camera worker. */
    fun decode(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0 || bitmap.isRecycled) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        )
        val reader = MultiFormatReader().apply { setHints(hints) }
        val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        return try {
            reader.decodeWithState(binary).text
        } catch (_: Exception) {
            // Payload data can resemble an extra finder pattern. The single-code detector
            // then selects the wrong triplet; the multi-code detector checks alternatives.
            runCatching { QRCodeMultiReader().decodeMultiple(binary, hints).firstOrNull()?.text }.getOrNull()
        } finally {
            reader.reset()
        }
    }
}
