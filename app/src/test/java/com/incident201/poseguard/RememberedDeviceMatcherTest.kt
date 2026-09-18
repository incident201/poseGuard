package com.incident201.poseguard.intiface

import org.junit.Assert.*
import org.junit.Test

class RememberedDeviceMatcherTest {
    private val left = IntifaceDeviceInfo(1, "Same model", "Left", 1)
    private val right = IntifaceDeviceInfo(2, "Same model", "Right", 1)

    @Test fun customLabelWinsOverFirstDeviceWithSameModelName() {
        assertEquals(right, matchRememberedDevice(listOf(left, right),
            IntifaceRememberedDevice("Same model", "Right", 1)))
    }
    @Test fun missingLabelDoesNotSilentlySelectAnotherDevice() {
        assertNull(matchRememberedDevice(listOf(left),
            IntifaceRememberedDevice("Same model", "Right", 1)))
    }
    @Test fun reusedIndexDoesNotIdentifyRememberedDevice() {
        assertNull(matchRememberedDevice(listOf(left),
            IntifaceRememberedDevice("Other model", "Other model", 1)))
    }
    @Test fun identicalUnlabelledDevicesRequireManualSelection() {
        assertNull(matchRememberedDevice(listOf(left.copy(displayName = left.name), right.copy(displayName = right.name)),
            IntifaceRememberedDevice("Same model", "Same model", 2)))
    }
    @Test fun uniqueDeviceCanReconnectWithChangedIndex() {
        assertEquals(left, matchRememberedDevice(listOf(left),
            IntifaceRememberedDevice("Same model", "", 100)))
    }
}
