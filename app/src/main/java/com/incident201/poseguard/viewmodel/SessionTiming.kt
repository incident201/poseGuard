package com.incident201.poseguard.viewmodel

import android.os.SystemClock

/** Defaults preserve device timing; instrumentation can supply a monotonic test clock. */
internal data class SessionTiming(
    val nowMs: () -> Long = SystemClock::elapsedRealtime,
    val stabilizationDurationMs: Long = 4_000L,
    val startDelaySeconds: Int = 10
) {
    init {
        require(stabilizationDurationMs >= 0L)
        require(startDelaySeconds >= 0)
    }
}
