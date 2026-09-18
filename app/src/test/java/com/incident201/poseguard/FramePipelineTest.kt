package com.incident201.poseguard

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.incident201.poseguard.tracker.CameraFrame
import com.incident201.poseguard.tracker.AccelerationMode
import com.incident201.poseguard.tracker.Point3D
import com.incident201.poseguard.tracker.PoseLandmarks
import com.incident201.poseguard.viewmodel.GameState
import com.incident201.poseguard.viewmodel.GameViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FramePipelineTest {
    private lateinit var model: GameViewModel
    private val store = ViewModelStore()
    private val pose = PoseLandmarks.fromAllLandmarks(List(33) { i ->
        Point3D(0.25f + (i % 2) * 0.3f, 0.1f + i * 0.02f, 0f, 1f, 1f)
    })

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("game_settings", 0).edit().clear().commit()
        model = GameViewModel(app)
        store.put("test", model)
    }
    @After fun tearDown() = store.clear()

    private fun awaitResults() {
        val field = GameViewModel::class.java.getDeclaredField("mediaPipeResultExecutor")
        field.isAccessible = true
        (field.get(model) as ExecutorService).submit {}.get(3, TimeUnit.SECONDS)
    }

    @Test fun unmatchedResultCannotRepopulateOverlayAfterCacheClear() {
        model.processMediaPipeResults(pose, 100, 8, 8)
        awaitResults()
        assertTrue(model.poseOverlayState.value.landmarks.isEmpty())
        assertNull(model.buildPoseDebugSnapshotJson())
    }

    @Test fun matchingFrameIsProcessedAndReleased() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        CameraFrame(bitmap).use { model.registerCameraFrame(it, 100) }
        model.processMediaPipeResults(pose, 100, 8, 8)
        awaitResults()
        assertEquals(33, model.poseOverlayState.value.landmarks.size)
        assertTrue(bitmap.isRecycled)
    }

    @Test fun modelChangeInvalidatesPendingAndLatestFrames() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        CameraFrame(bitmap).use { model.registerCameraFrame(it, 100) }
        model.resetPoseInputContinuity()
        model.processMediaPipeResults(pose, 100, 8, 8)
        awaitResults()
        assertNull(model.buildPoseDebugSnapshotJson())
        assertTrue(bitmap.isRecycled)
    }

    @Test fun backgroundingCancelsStabilization() {
        model.startSession()
        assertEquals(GameState.WaitingForStabilization, model.gameState.value)
        model.onCameraUnavailable()
        assertEquals(GameState.Idle, model.gameState.value)
    }

    @Test fun backgroundingActiveSessionFailsInsteadOfContinuingWithoutCamera() {
        val state = GameViewModel::class.java.getDeclaredField("_gameState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = state.get(model) as MutableStateFlow<GameState>
        mutableState.value = GameState.HoldingPose
        model.onCameraUnavailable()
        assertEquals(GameState.Failed, model.gameState.value)
        assertEquals(GameState.Failed, model.sessionSummary.value?.result)
    }

    @Test fun cacheIsBoundedWithoutRecyclingFramesRetainedByWorker() {
        val oldest = CameraFrame(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        model.registerCameraFrame(oldest, 1)
        repeat(21) { i ->
            CameraFrame(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)).use {
                model.registerCameraFrame(it, i + 2L)
            }
        }
        assertFalse(oldest.bitmap.isRecycled)
        model.processMediaPipeResults(pose, 1, 8, 8)
        awaitResults()
        assertNull(model.buildPoseDebugSnapshotJson())
        oldest.close()
        assertTrue(oldest.bitmap.isRecycled)
    }

    @Test fun delayedCpuFallbackCannotChangeManualPreferenceToAutomatic() {
        model.updateAccelerationMode(AccelerationMode.Cpu)
        model.resolveAccelerationMode(AccelerationMode.Cpu)
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(app.getSharedPreferences("game_settings", 0)
            .getBoolean("acceleration_mode_auto_resolved", false))
    }

    @Test fun outOfOrderResultsCannotRollBackTheLatestPose() {
        listOf(200L, 100L).forEach { timestamp ->
            CameraFrame(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)).use {
                model.registerCameraFrame(it, timestamp)
            }
            model.processMediaPipeResults(pose, timestamp, 8, 8)
            awaitResults()
        }
        assertEquals(200L, org.json.JSONObject(model.buildPoseDebugSnapshotJson()!!).getLong("timestampMs"))
    }
}
