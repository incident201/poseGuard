package com.incident201.poseguard.scenario

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mediapipe.tasks.core.Delegate
import com.incident201.poseguard.tracker.CameraFrame
import com.incident201.poseguard.tracker.FaceDetectionStatus
import com.incident201.poseguard.tracker.FaceDetectorService
import com.incident201.poseguard.tracker.MediaPipePoseLandmarkerFactory
import com.incident201.poseguard.tracker.PoseLandmarkerModel
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real JNI regression coverage for models shared by overlapping native graph instances. */
@RunWith(AndroidJUnit4::class)
class ModelLoadingScenarioTest {
    @Test fun anExistingPoseGraphKeepsRunningWhileAnotherGraphLoads() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val loader = Executors.newSingleThreadExecutor()
        try {
            PoseLandmarkerModel.entries.forEach { model ->
                val results = LinkedBlockingQueue<Long>()
                val error = AtomicReference<RuntimeException?>()
                val factory = MediaPipePoseLandmarkerFactory(app, model)
                factory.create(Delegate.CPU,
                    onResult = { _, _, _, timestamp -> results.offer(timestamp) },
                    onError = { error.set(it); results.offer(-1L) }
                ).use { running ->
                    CameraFrame(Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)).use { frame ->
                        var timestamp = 0L
                        fun infer() {
                            running.detectAsync(frame.image, ++timestamp)
                            val result = results.poll(30, TimeUnit.SECONDS)
                            assertNull(error.get())
                            org.junit.Assert.assertEquals("Existing $model graph must keep processing", timestamp, result)
                        }
                        infer()
                        repeat(4) {
                            val loading = loader.submit {
                                factory.create(Delegate.CPU, { _, _, _, _ -> }, { throw it }).use { }
                            }
                            // The old graph actively reads its model throughout creation of
                            // the next graph. Relative asset paths truncate that live mapping.
                            do { infer() } while (!loading.isDone)
                            loading.get(30, TimeUnit.SECONDS)
                        }
                    }
                }
            }
        } finally {
            loader.shutdownNow()
            check(loader.awaitTermination(60, TimeUnit.SECONDS))
        }
    }

    @Test fun bothPoseModelsAllowConcurrentInitializationAndInference() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PoseLandmarkerModel.entries.forEach { model ->
            concurrently(staggerSecondWorker = true) {
                val result = CountDownLatch(1)
                val error = AtomicReference<RuntimeException?>()
                MediaPipePoseLandmarkerFactory(app, model).create(
                    Delegate.CPU,
                    onResult = { _, width, height, _ ->
                        if (width != 320 || height != 240) error.set(IllegalStateException("Unexpected result dimensions: ${width}x$height"))
                        result.countDown()
                    },
                    onError = { error.set(it); result.countDown() }
                ).use { engine ->
                    CameraFrame(Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)).use { frame ->
                        engine.detectAsync(frame.image, 1L)
                        assertTrue("Native result for $model", result.await(30, TimeUnit.SECONDS))
                        assertNull(error.get())
                    }
                }
            }
        }
    }

    @Test fun faceDetectorsAllowConcurrentInitializationAndInference() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        concurrently {
            val detector = FaceDetectorService(app)
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            try {
                repeat(3) {
                    val result = detector.detectOnCrop(bitmap)
                    assertNotEquals(result.errorMessage, FaceDetectionStatus.Error, result.status)
                }
            } finally {
                detector.close()
                bitmap.recycle()
            }
        }
    }

    private fun concurrently(staggerSecondWorker: Boolean = false, action: () -> Unit) {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(5) { round ->
                val ready = CountDownLatch(2)
                val start = CountDownLatch(1)
                val futures = List(2) { worker ->
                    workers.submit {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        if (staggerSecondWorker && worker == 1) Thread.sleep(round * 40L)
                        action()
                    }
                }
                check(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                futures.forEach { it.get(60, TimeUnit.SECONDS) }
            }
        } finally {
            workers.shutdownNow()
            check(workers.awaitTermination(60, TimeUnit.SECONDS))
        }
    }
}
