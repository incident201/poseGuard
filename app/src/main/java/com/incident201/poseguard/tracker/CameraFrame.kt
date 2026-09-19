package com.incident201.poseguard.tracker

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage

/** Shared ownership between the bounded frame cache and the delegate submission worker. */
class CameraFrame(val bitmap: Bitmap) : AutoCloseable {
    val image: MPImage = BitmapImageBuilder(bitmap).build()
    private var owners = 1

    @Synchronized
    fun retain(): Boolean {
        if (owners == 0) return false
        owners += 1
        return true
    }

    @Synchronized
    override fun close() {
        check(owners > 0) { "Camera frame released without ownership" }
        owners -= 1
        // Bitmap-backed MPImage.close() recycles its bitmap. Never recycle it separately.
        if (owners == 0) image.close()
    }
}
