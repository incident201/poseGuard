package com.incident201.poseguard.video

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimelapseLifecycleTest {
    @Test(timeout = 5_000) fun releaseDoesNotWaitForBlockedEncoderAndPendingFramesAreBounded() {
        val recorder = TimelapseRecorder(ApplicationProvider.getApplicationContext<Context>())
        val worker = TimelapseRecorder::class.java.getDeclaredField("frameExecutor").apply { isAccessible = true }
            .get(recorder) as ExecutorService
        val slots = TimelapseRecorder::class.java.getDeclaredField("pendingFrameSlots").apply { isAccessible = true }
            .get(recorder) as Semaphore
        val blocked = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        worker.execute {
            blocked.countDown()
            unblock.await(4, TimeUnit.SECONDS)
        }
        assertTrue(blocked.await(1, TimeUnit.SECONDS))
        try {
            recorder.start(0)
            repeat(100) { recorder.offerFrame(bitmap, it * 100L, 0, "%d") }
            assertEquals(0, slots.availablePermits())
            recorder.release()
            assertEquals(1L, unblock.count)
        } finally {
            unblock.countDown()
            recorder.release()
            bitmap.recycle()
        }
        assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
        assertEquals(2, slots.availablePermits())
    }
}
