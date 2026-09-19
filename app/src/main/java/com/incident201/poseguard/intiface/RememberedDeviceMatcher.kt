package com.incident201.poseguard.intiface

/**
 * Device indices belong to the server, not to a physical device identity.
 * Ambiguous same-model devices require manual selection instead of sending to the first one.
 */
internal fun matchRememberedDevice(
    devices: List<IntifaceDeviceInfo>,
    remembered: IntifaceRememberedDevice
): IntifaceDeviceInfo? {
    val named = devices.filter { remembered.name.isNotBlank() && it.name == remembered.name }
    if (remembered.displayName.isNotBlank()) {
        val exact = named.filter { it.displayName == remembered.displayName }
        if (exact.size == 1) return exact.single()
        if (exact.size > 1) return null
        // A custom label distinguishes otherwise identical models: don't substitute another.
        if (remembered.displayName != remembered.name) return null
    }
    return named.singleOrNull()
}
