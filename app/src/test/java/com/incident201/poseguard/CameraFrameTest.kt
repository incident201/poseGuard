package com.incident201.poseguard.tracker

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraFrameTest {
    @Test fun cacheEvictionDoesNotRecycleBitmapStillOwnedByDelegateWorker() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val frame = CameraFrame(bitmap)
        assertTrue(frame.retain())
        frame.close()
        assertFalse(bitmap.isRecycled)
        frame.close()
        assertTrue(bitmap.isRecycled)
        assertFalse(frame.retain())
    }
    @Test fun releaseWithoutWorkerRecyclesImmediately() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        CameraFrame(bitmap).close()
        assertTrue(bitmap.isRecycled)
    }
}
