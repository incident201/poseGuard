package com.incident201.poseguard

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.incident201.poseguard.intiface.IntifaceMessage
import com.incident201.poseguard.intiface.createIntifaceController
import com.incident201.poseguard.viewmodel.GameViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The offline flavor promises that nothing in it can reach the network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineFlavorTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun packageRequestsNoNetworkPermissionButKeepsTheCamera() {
        val requested = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        val network = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_LOCAL_NETWORK"
        )
        assertEquals(emptySet<String>(), requested.intersect(network))
        assertTrue("android.permission.CAMERA" in requested)
    }

    @Test fun intifaceStubReportsOnlineOnlyForEveryOperationAndNeverConnects() = runBlocking {
        val controller = createIntifaceController(app)
        assertFalse(controller.state.value.isSupported)
        assertEquals(IntifaceMessage.OnlineOnly, controller.state.value.statusMessage?.message)

        controller.searchDevices("ws://127.0.0.1:12345")
        controller.testVibration()
        controller.setVibrationStrength(1.0)
        controller.stopVibration()

        val state = controller.state.value
        assertEquals(IntifaceMessage.OnlineOnly, state.errorMessage?.message)
        assertFalse(state.isConnected)
        assertFalse(state.isBusy)
        assertTrue(state.devices.isEmpty())
        assertNull(state.selectedDevice)

        controller.clearTransientMessages()
        assertNull(controller.state.value.errorMessage)
    }

    @Test fun enablingIntifaceInSettingsHasNoEffectAndIsNotPersisted() {
        val store = ViewModelStore()
        app.getSharedPreferences("game_settings", 0).edit().clear().commit()
        try {
            val model = GameViewModel(app).also { store.put("model", it) }
            model.updateIntifaceConnectionEnabled(true)
            assertFalse(model.gameSettings.value.intifaceConnectionEnabled)
            model.searchIntifaceDevices("ws://127.0.0.1:12345")
            assertFalse(model.intifaceState.value.isConnected)
            assertFalse(app.getSharedPreferences("game_settings", 0).getBoolean("intiface_connection_enabled", true))
        } finally {
            store.clear()
        }
    }
}
