package com.incident201.poseguard.tracker

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.Delegate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PoseLandmarkerLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val attempts = LinkedBlockingQueue<Attempt>()
    private val allAttempts = CopyOnWriteArrayList<Attempt>()
    private val states = LinkedBlockingQueue<AccelerationState>()
    private var service: PoseLandmarkerService? = null

    private class Attempt(
        val delegate: Delegate,
        val result: (PoseLandmarks, Int, Int, Long) -> Unit,
        val error: (RuntimeException) -> Unit
    ) : PoseLandmarkerEngine {
        val initializationGate = CountDownLatch(1)
        val detectionStarted = CountDownLatch(1)
        val detectionGate = CountDownLatch(1)
        @Volatile var failure: RuntimeException? = null
        override fun detectAsync(image: MPImage, timestampMs: Long) {
            detectionStarted.countDown()
            check(detectionGate.await(3, TimeUnit.SECONDS))
        }
        override fun close() = Unit
        fun succeed() = initializationGate.countDown()
        fun fail() {
            failure = IllegalStateException("test GPU failure")
            initializationGate.countDown()
        }
        fun emitResult() = result(PoseLandmarks(), 8, 8, 100)
    }

    @Before fun clearPreferences() {
        context.getSharedPreferences("pose_landmarker_acceleration", 0).edit().clear().commit()
    }
    @After fun close() {
        allAttempts.forEach {
            it.initializationGate.countDown()
            it.detectionGate.countDown()
        }
        service?.close()
    }
    private fun start(mode: AccelerationMode): PoseLandmarkerService {
        return PoseLandmarkerService(context, mode, PoseLandmarkerModel.Heavy,
            object : PoseLandmarkerService.LandmarkerListener {
                override fun onError(error: String) = Unit
                override fun onResults(result: PoseLandmarks, imageWidth: Int, imageHeight: Int, timestampMs: Long) = Unit
                override fun onFrameDropped(timestampMs: Long) = Unit
                override fun onAccelerationStateChanged(state: AccelerationState) { states.add(state) }
            },
            PoseLandmarkerFactory { delegate, result, error ->
                val attempt = Attempt(delegate, result, error)
                allAttempts.add(attempt)
                attempts.add(attempt)
                check(attempt.initializationGate.await(3, TimeUnit.SECONDS))
                attempt.failure?.let { throw it }
                attempt
            }
        ).also { service = it }
    }
    private fun next(delegate: Delegate): Attempt {
        val attempt = attempts.poll(3, TimeUnit.SECONDS) ?: error("Delegate did not start")
        assertEquals(delegate, attempt.delegate)
        return attempt
    }
    private inline fun <reified T : AccelerationState> awaitState(): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (true) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "Missing state ${T::class.java.simpleName}" }
            val state = states.poll(remaining, TimeUnit.NANOSECONDS) ?: error("No state")
            if (state is T) return state
        }
    }
    private fun executorFor(fieldName: String): ExecutorService {
        val field = PoseLandmarkerService::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
        val backend = field.get(service)
        val executor = backend.javaClass.getDeclaredField("executor").apply { isAccessible = true }
        return executor.get(backend) as ExecutorService
    }

    @Test fun autoStartsCpuThenGpuAfterFirstCpuResult() {
        start(AccelerationMode.Auto)
        val cpu = next(Delegate.CPU)
        cpu.succeed()
        awaitState<AccelerationState.Cpu>()
        assertTrue(attempts.isEmpty())
        cpu.emitResult()
        next(Delegate.GPU).succeed()
        awaitState<AccelerationState.Gpu>()
    }

    @Test fun gpuRuntimeErrorFallsBackToCpu() {
        start(AccelerationMode.Gpu)
        val gpu = next(Delegate.GPU)
        gpu.succeed()
        awaitState<AccelerationState.Gpu>()
        gpu.error(IllegalStateException("runtime failure"))
        next(Delegate.CPU).succeed()
        assertTrue(awaitState<AccelerationState.CpuFallback>().reason.contains("runtime failure"))
    }

    @Test fun lateGpuInitializationFailureCannotOverrideManualCpuMode() {
        val service = start(AccelerationMode.Gpu)
        val gpu = next(Delegate.GPU)
        val oldExecutor = executorFor("gpuBackend")
        service.setAccelerationMode(AccelerationMode.Cpu)
        next(Delegate.CPU).succeed()
        awaitState<AccelerationState.Cpu>()
        gpu.fail()
        assertTrue(oldExecutor.awaitTermination(3, TimeUnit.SECONDS))
        assertTrue(states.none { it is AccelerationState.CpuFallback || it is AccelerationState.Error })
        assertNull(context.getSharedPreferences("pose_landmarker_acceleration", 0).getString("gpu_result", null))
    }

    @Test fun lateFailureAfterCloseCannotPoisonGpuCache() {
        val service = start(AccelerationMode.Gpu)
        val gpu = next(Delegate.GPU)
        val oldExecutor = executorFor("gpuBackend")
        service.close()
        gpu.fail()
        assertTrue(oldExecutor.awaitTermination(3, TimeUnit.SECONDS))
        assertNull(context.getSharedPreferences("pose_landmarker_acceleration", 0).getString("gpu_result", null))
    }

    @Test fun submissionIsBoundedAndKeepsBitmapAliveUntilNativeSubmissionReturns() {
        val service = start(AccelerationMode.Cpu)
        val cpu = next(Delegate.CPU)
        cpu.succeed()
        awaitState<AccelerationState.Cpu>()
        val executor = executorFor("cpuBackend")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        CameraFrame(bitmap).use { assertTrue(service.detectLiveStreamFrame(it, 1)) }
        assertTrue(cpu.detectionStarted.await(3, TimeUnit.SECONDS))
        assertFalse(bitmap.isRecycled)
        CameraFrame(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)).use {
            assertFalse(service.detectLiveStreamFrame(it, 2))
        }
        cpu.detectionGate.countDown()
        executor.submit {}.get(3, TimeUnit.SECONDS)
        assertTrue(bitmap.isRecycled)
    }
}
