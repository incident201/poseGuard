package com.incident201.poseguard.challenge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrCodesTest {
    @Test fun generatedCodeThatConfusesAlignmentDetectionStillDecodes() {
        // Synthetic task captured from the failing on-device exchange test.
        val code = javaClass.getResource("/qr-detector-edge.txt")!!.readText().trim()
        val bitmap = QrCodes.encode(code, 900)!!
        val scaled = Bitmap.createScaledBitmap(bitmap, 620, 620, false)
        val frame = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888)
        try {
            assertEquals(code, QrCodes.decode(bitmap))
            Canvas(frame).apply { drawColor(Color.GRAY); drawBitmap(scaled, 400f, 200f, null) }
            assertEquals(code, QrCodes.decode(frame))
        } finally {
            frame.recycle()
            scaled.recycle()
            bitmap.recycle()
        }
    }

    @Test fun codesOfDifferentLengthsDecodeDirectlyAndInsideACameraFrame() {
        val random = Random(20261031)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
        for (length in listOf(128, 808, 1100, 1500)) {
            repeat(4) {
                val code = "PGT2:" + buildString { repeat(length - 5) { append(alphabet[random.nextInt(alphabet.length)]) } }
                val qr = QrCodes.encode(code, 900)!!
                val scaled = Bitmap.createScaledBitmap(qr, 620, 620, false)
                val frame = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888)
                try {
                    assertEquals("Direct code of length $length", code, QrCodes.decode(qr))
                    Canvas(frame).apply { drawColor(Color.GRAY); drawBitmap(scaled, 400f, 200f, null) }
                    assertEquals("Camera frame with code of length $length", code, QrCodes.decode(frame))
                } finally {
                    frame.recycle()
                    scaled.recycle()
                    qr.recycle()
                }
            }
        }
    }

    @Test fun aBlankFrameDoesNotDecode() {
        val frame = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        try { assertNull(QrCodes.decode(frame)) } finally { frame.recycle() }
    }
}
