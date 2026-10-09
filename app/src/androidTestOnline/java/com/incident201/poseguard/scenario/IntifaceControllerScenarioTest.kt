package com.incident201.poseguard.scenario

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.incident201.poseguard.intiface.IntifaceMessage
import com.incident201.poseguard.intiface.IntifaceRememberedDevice
import com.incident201.poseguard.intiface.OnlineIntifaceController
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntifaceControllerScenarioTest {
    private lateinit var controller: OnlineIntifaceController

    @Before fun setUp() {
        IntifaceLab.requireEnabled()
        IntifaceLab.reset()
        controller = OnlineIntifaceController()
    }

    @After fun tearDown() = runBlocking {
        if (::controller.isInitialized) { controller.disconnect(); delay(200) }
    }

    private suspend fun connectAndSelect() {
        controller.searchDevices(IntifaceLab.url)
        assertTrue(controller.state.value.isConnected)
        assertFalse(controller.state.value.isBusy)
        assertNull(controller.state.value.errorMessage)
        assertEquals(2, controller.state.value.devices.size)
        val device = controller.state.value.devices.single { it.name.contains(" A ") }
        assertEquals(2L, device.vibrateCount)
        controller.selectDevice(device)
    }

    @Test fun discoveryClampingTestPulseAndStopReachBothMotors() = runBlocking {
        connectAndSelect()
        controller.setVibrationStrength(2.0)
        IntifaceLab.awaitValue(100)
        controller.stopVibration()
        IntifaceLab.awaitValue(0)
        controller.testVibration()
        IntifaceLab.awaitValue(60)
        assertEquals(IntifaceMessage.TestVibrationDone, controller.state.value.statusMessage?.message)
        assertFalse(controller.state.value.isTestingVibration)
        assertNull(controller.state.value.errorMessage)
        assertTrue(IntifaceLab.commands().takeLast(2).all { it.value == 0 })
    }

    @Test fun testPulseWaitsForInFlightStopInsteadOfGettingDropped() = runBlocking {
        connectAndSelect()
        IntifaceLab.fault("delay-next-scalar")
        val stop = async(Dispatchers.IO) { controller.stopVibration() }
        IntifaceLab.awaitFaultApplied("delay-next-scalar")
        controller.testVibration()
        stop.await()
        IntifaceLab.awaitValue(60)
        assertEquals(IntifaceMessage.TestVibrationDone, controller.state.value.statusMessage?.message)
        assertFalse(controller.state.value.isTestingVibration)
    }

    @Test fun commandTimeoutReturnsAnErrorAndFreshSearchRecovers() = runBlocking {
        connectAndSelect()
        IntifaceLab.fault("timeout-next-scalar")
        controller.setVibrationStrength(.7)
        assertNotNull(controller.state.value.errorMessage)
        connectAndSelect()
        controller.setVibrationStrength(.3)
        IntifaceLab.awaitValue(30)
    }

    @Test fun serverRejectionDoesNotPoisonTheNextConnection() = runBlocking {
        connectAndSelect()
        IntifaceLab.fault("reject-next-scalar")
        controller.setVibrationStrength(.7)
        assertNotNull(controller.state.value.errorMessage)
        connectAndSelect()
        controller.setVibrationStrength(.4)
        IntifaceLab.awaitValue(40)
    }

    @Test fun transportDisconnectCanBeRecoveredByManualSearch() = runBlocking {
        connectAndSelect()
        IntifaceLab.fault("disconnect-next-scalar")
        controller.setVibrationStrength(.7)
        connectAndSelect()
        controller.setVibrationStrength(.5)
        IntifaceLab.awaitValue(50)
    }

    @Test fun emptyDiscoveryAndInvalidUrlHaveExplicitStates() = runBlocking {
        IntifaceLab.fault("no-devices")
        controller.searchDevices(IntifaceLab.url)
        assertTrue(controller.state.value.devices.isEmpty())
        assertEquals(IntifaceMessage.NoVibrateDevices, controller.state.value.statusMessage?.message)
        assertFalse(controller.state.value.isBusy)
        controller.searchDevices("https://127.0.0.1:12345")
        assertEquals(IntifaceMessage.InvalidUrl, controller.state.value.errorMessage?.message)
        assertFalse(controller.state.value.isConnected)
    }

    @Test fun switchingStopsPreviousDeviceBeforeSignallingNewDevice() = runBlocking {
        connectAndSelect()
        controller.setVibrationStrength(.35)
        IntifaceLab.awaitValue(35)
        val b = controller.state.value.devices.single { it.name.contains(" B ") }
        IntifaceLab.reset()
        controller.selectDevice(b)
        controller.setVibrationStrength(.65)
        IntifaceLab.awaitValue(65, "B")
        IntifaceLab.awaitValue(0, "A")
        assertTrue(IntifaceLab.commands().filter { it.device == "A" }.all { it.value == 0 })
        assertEquals(b, controller.state.value.selectedDevice)
    }

    @Test fun manualDisconnectZerosAllOutputsAndFreshSearchRecovers() = runBlocking {
        connectAndSelect()
        controller.setVibrationStrength(.45)
        IntifaceLab.awaitValue(45)
        IntifaceLab.reset()
        controller.disconnect()
        assertFalse(controller.state.value.isConnected)
        assertNull(controller.state.value.selectedDevice)
        assertTrue(controller.state.value.devices.isEmpty())
        IntifaceLab.awaitValue(0)
        connectAndSelect()
        controller.setVibrationStrength(.25)
        IntifaceLab.awaitValue(25)
    }

    @Test fun freshControllerRestoresSavedIdentityInsteadOfStaleIndex() = runBlocking {
        connectAndSelect()
        val b = controller.state.value.devices.single { it.name.contains(" B ") }
        val wrongIndex = controller.state.value.selectedDevice!!.index
        controller.disconnect()
        controller = OnlineIntifaceController()
        controller.connectToRememberedDevice(IntifaceLab.url, IntifaceRememberedDevice(b.name, b.displayName, wrongIndex))
        assertEquals(b.name, controller.state.value.selectedDevice?.name)
        controller.setVibrationStrength(.5)
        IntifaceLab.awaitValue(50, "B")
        assertFalse(IntifaceLab.commands().any { it.device == "A" && it.value == 50 })
    }

    @Test fun missingRememberedDeviceDoesNotFallbackToAnotherDevice() = runBlocking {
        connectAndSelect()
        val b = controller.state.value.devices.single { it.name.contains(" B ") }
        controller.disconnect()
        IntifaceLab.device("B", false)
        controller.connectToRememberedDevice(IntifaceLab.url, IntifaceRememberedDevice(b.name, b.displayName, b.index))
        assertTrue(controller.state.value.isConnected)
        assertNull(controller.state.value.selectedDevice)
        assertEquals(IntifaceMessage.SavedDeviceNotFound, controller.state.value.errorMessage?.message)
        assertEquals(1, controller.state.value.devices.size)
        IntifaceLab.reset()
        controller.setVibrationStrength(.8)
        assertFalse(IntifaceLab.commands().any { it.value > 0 })
    }

    @Test fun deviceUnplugAndReplugRefreshDiscoveryAndClearSelection() = runBlocking {
        connectAndSelect()
        IntifaceLab.device("A", false)
        awaitCondition("selected device removed") {
            controller.state.value.devices.size == 1 && controller.state.value.selectedDevice == null
        }
        controller.setVibrationStrength(.9)
        assertEquals(IntifaceMessage.SelectedDeviceMissing, controller.state.value.errorMessage?.message)
        IntifaceLab.device("A", true)
        // The application stops discovery after scanning; a new scan rediscovers reattached hardware.
        connectAndSelect()
        controller.setVibrationStrength(.15)
        IntifaceLab.awaitValue(15)
    }
}
