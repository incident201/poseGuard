package com.incident201.poseguard.viewmodel

/** Monotonic session timing. The owner serializes access with its session-state lock. */
internal class SessionClock {
    private var startedAtMs: Long? = null
    private var targetSeconds = 0L

    data class Snapshot(val elapsedSeconds: Int, val remainingSeconds: Int, val finished: Boolean)

    fun reset(durationSeconds: Int) {
        targetSeconds = durationSeconds.coerceAtLeast(1).toLong()
        startedAtMs = null
    }

    fun start(nowMs: Long) {
        startedAtMs = nowMs
    }

    fun addPenalty(seconds: Int) {
        targetSeconds = (targetSeconds + seconds.coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong())
    }

    fun snapshot(nowMs: Long): Snapshot {
        val start = startedAtMs
        val elapsed = start?.let { ((nowMs - it).coerceAtLeast(0L) / 1000L) } ?: 0L
        return Snapshot(
            elapsedSeconds = elapsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            remainingSeconds = (targetSeconds - elapsed).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            finished = start != null && elapsed >= targetSeconds
        )
    }
}
