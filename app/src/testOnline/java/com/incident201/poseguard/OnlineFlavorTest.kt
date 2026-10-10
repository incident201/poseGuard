package com.incident201.poseguard

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.incident201.poseguard.intiface.IntifaceMessage
import com.incident201.poseguard.intiface.createIntifaceController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnlineFlavorTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun packageRequestsOnlyThePermissionsIntifaceNeeds() {
        val requested = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertTrue("android.permission.INTERNET" in requested)
        assertTrue("android.permission.ACCESS_LOCAL_NETWORK" in requested)
        assertTrue("android.permission.CAMERA" in requested)
    }

    @Test fun realControllerIsSelectedAndRejectsANonWebSocketUrlWithoutTouchingTheNetwork() = runBlocking {
        val controller = createIntifaceController(app)
        assertTrue(controller.state.value.isSupported)

        controller.searchDevices("https://127.0.0.1:12345")

        assertEquals(IntifaceMessage.InvalidUrl, controller.state.value.errorMessage?.message)
        assertFalse(controller.state.value.isConnected)
        assertFalse(controller.state.value.isBusy)
    }
}
