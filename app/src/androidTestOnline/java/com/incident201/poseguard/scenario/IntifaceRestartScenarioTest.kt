package com.incident201.poseguard.scenario

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Process
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.incident201.poseguard.MainActivity
import com.incident201.poseguard.viewmodel.GameState
import com.incident201.poseguard.viewmodel.GameViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/** Two instrumentation invocations with an actual host-side force-stop between them. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class IntifaceRestartScenarioTest {
    private data class Backup(val prefs: HashMap<String, Any?>, val pid: Int) : Serializable

    @Test fun savedDeviceSurvivesProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("poseguardRestartPhase")
        assumeNotNull(phase)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("game_settings", Context.MODE_PRIVATE)
        val backupFile = File(app.filesDir, "intiface-test-preferences.bin")
        if (phase == "restore") {
            if (backupFile.exists()) {
                val backup = ObjectInputStream(backupFile.inputStream()).use { it.readObject() as Backup }
                PreferencesSnapshot(prefs, backup.prefs).close()
                check(backupFile.delete())
            }
            return
        }
        IntifaceLab.requireEnabled()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.CAMERA)
        if (phase == "prepare") {
            check(!backupFile.exists()) { "Restore interrupted restart test before running again" }
            val saved = HashMap<String, Any?>()
            prefs.all.forEach { (key, value) -> saved[key] = if (value is Set<*>) HashSet(value) else value }
            ObjectOutputStream(backupFile.outputStream()).use { it.writeObject(Backup(saved, Process.myPid())) }
            check(prefs.edit().clear().putBoolean("onboarding_completed", true)
                .putBoolean("timelapse_recording_enabled", false).putString("acceleration_mode", "Cpu")
                .putString("pose_landmarker_model", "Full").commit())
        } else {
            assertEquals("verify", phase)
            val backup = ObjectInputStream(backupFile.inputStream()).use { it.readObject() as Backup }
            assertNotEquals("Must be a new Android process", backup.pid, Process.myPid())
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: GameViewModel
            scenario.onActivity { model = ViewModelProvider(it)[GameViewModel::class.java] }
            if (phase == "prepare") {
                onMain { model.updateIntifaceConnectionEnabled(true); model.searchIntifaceDevices(IntifaceLab.url) }
                awaitCondition("scan", 15_000) { !model.intifaceState.value.isBusy && model.intifaceState.value.devices.size == 2 }
                onMain { model.selectIntifaceDevice(model.intifaceState.value.devices.single { it.name.contains(" B ") }) }
            }
            awaitCondition("saved device B restored by application UI", 15_000) {
                model.intifaceState.value.isConnected && model.intifaceState.value.selectedDevice?.name?.contains(" B ") == true
            }
            assertEquals(GameState.Idle, model.gameState.value)
            assertNull(model.sessionSummary.value)
            // Reconnect is silent until the user starts a session or requests a test pulse.
            assertFalse(IntifaceLab.commands().any { it.value > 0 })
            onMain { model.testIntifaceVibration() }
            IntifaceLab.awaitValue(60, "B")
            awaitCondition("test pulse finished", 10_000) { !model.intifaceState.value.isBusy }
            assertFalse(IntifaceLab.commands().any { it.device == "A" && it.value > 0 })
        }
    }
}
