package com.incident201.poseguard.scenario

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.incident201.poseguard.audio.AudioCueEvent
import com.incident201.poseguard.tracker.CameraFrame
import com.incident201.poseguard.tracker.Point3D
import com.incident201.poseguard.tracker.PoseLandmarks
import com.incident201.poseguard.viewmodel.GameState
import com.incident201.poseguard.viewmodel.GameViewModel
import com.incident201.poseguard.viewmodel.SessionTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

internal fun awaitCondition(description: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        if (condition()) return
        Thread.sleep(20)
    }
    check(condition()) { "Timed out waiting for $description" }
}

internal class PreferencesSnapshot(
    private val prefs: SharedPreferences,
    private val saved: Map<String, *> = prefs.all.toMap()
) : AutoCloseable {
    override fun close() {
        val editor = prefs.edit().clear()
        saved.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit())
    }
}

/** Synthetic observations exercise the production frame pipeline and state machine on Android. */
open class SessionFixture {
    protected lateinit var app: Application
    protected lateinit var model: GameViewModel
    protected val now = AtomicLong()
    protected val cues = CopyOnWriteArrayList<AudioCueEvent>()
    private lateinit var preferences: PreferencesSnapshot
    private lateinit var worker: ExecutorService
    private lateinit var audioScope: CoroutineScope
    private val store = ViewModelStore()

    protected val pose: PoseLandmarks = run {
        val points = MutableList(33) { Point3D(0.5f, 0.15f, 0f, 1f, 1f) }
        val positions = mapOf(
            11 to (.3f to .2f), 12 to (.7f to .2f),
            13 to (.2f to .35f), 14 to (.8f to .35f),
            15 to (.15f to .2f), 16 to (.85f to .2f),
            23 to (.35f to .5f), 24 to (.65f to .5f),
            25 to (.4f to .8f), 26 to (.6f to .8f),
            27 to (.4f to .95f), 28 to (.6f to .95f)
        )
        positions.forEach { (index, xy) -> points[index] = Point3D(xy.first, xy.second, 0f, 1f, 1f) }
        PoseLandmarks.fromAllLandmarks(points)
    }

    @Before fun createSessionFixture() {
        app = ApplicationProvider.getApplicationContext()
        val prefs = app.getSharedPreferences("game_settings", Context.MODE_PRIVATE)
        preferences = PreferencesSnapshot(prefs)
        check(prefs.edit().clear().commit())
        now.set(SystemClock.elapsedRealtime())
        onMain {
            model = GameViewModel(app, SessionTiming(now::get, 0, 0))
            store.put("scenario", model)
            model.updateTimelapseRecordingEnabled(false)
            model.updateMinimumPenaltyIntervalSeconds(0)
        }
        worker = GameViewModel::class.java.getDeclaredField("mediaPipeResultExecutor")
            .apply { isAccessible = true }.get(model) as ExecutorService
        audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        audioScope.launch { model.audioCueEvents.collect { cues.add(it) } }
    }

    @After fun clearSessionFixture() {
        if (::model.isInitialized) onMain { store.clear() }
        if (::worker.isInitialized) check(worker.awaitTermination(5, TimeUnit.SECONDS))
        if (::audioScope.isInitialized) audioScope.cancel()
        if (::preferences.isInitialized) preferences.close()
    }

    protected fun frame(value: PoseLandmarks = pose, advanceMs: Long = 33, width: Int = 320, height: Int = 240) {
        val timestamp = now.addAndGet(advanceMs)
        CameraFrame(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)).use {
            model.registerCameraFrame(it, timestamp)
        }
        model.processMediaPipeResults(value, timestamp, width, height)
        worker.submit {}.get(5, TimeUnit.SECONDS)
    }

    protected fun start(durationSeconds: Int = 30) {
        begin(durationSeconds)
        awaitCondition("HoldingPose") { model.gameState.value == GameState.HoldingPose }
    }

    protected fun begin(durationSeconds: Int = 30, initialPose: PoseLandmarks = pose) {
        onMain {
            model.updateSelectedDurationSeconds(durationSeconds)
            model.startSession()
            assertEquals(GameState.WaitingForStabilization, model.gameState.value)
            frame(initialPose)
            val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensor ->
                // SensorEvent has no public constructor; only the input event is synthetic.
                val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.newInstance(3)
                event.sensor = sensor
                event.timestamp = now.get() * 1_000_000
                model.onSensorChanged(event)
            }
        }
    }

    protected fun disappearUntilViolation() {
        repeat(20) {
            if (model.gameState.value != GameState.HoldingPose || model.violationCount.value > 0) return
            frame(PoseLandmarks(), 200)
        }
        check(model.violationCount.value > 0) { "Missing pose never produced a violation" }
    }

    protected fun translated(dx: Float) = PoseLandmarks.fromAllLandmarks(
        pose.allLandmarks.map { it.copy(x = it.x + dx) }
    )
}
