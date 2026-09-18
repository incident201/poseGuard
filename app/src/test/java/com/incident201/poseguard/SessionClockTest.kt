package com.incident201.poseguard.viewmodel

import org.junit.Assert.*
import org.junit.Test

class SessionClockTest {
    @Test fun delayedTickUsesElapsedTimeRatherThanNumberOfTicks() {
        val clock = SessionClock()
        clock.reset(60)
        clock.start(1_000)
        assertEquals(53, clock.snapshot(8_400).remainingSeconds)
        assertEquals(7, clock.snapshot(8_400).elapsedSeconds)
    }

    @Test fun penaltyAtDeadlinePreventsPrematureSuccess() {
        val clock = SessionClock()
        clock.reset(1)
        clock.start(1_000)
        clock.addPenalty(60)
        assertFalse(clock.snapshot(2_000).finished)
        assertEquals(60, clock.snapshot(2_000).remainingSeconds)
        assertTrue(clock.snapshot(62_000).finished)
    }

    @Test fun resetClearsPreviousStartAndPenalties() {
        val clock = SessionClock()
        clock.reset(5)
        clock.start(0)
        clock.addPenalty(60)
        clock.reset(10)
        assertFalse(clock.snapshot(100_000).finished)
        assertEquals(0, clock.snapshot(100_000).elapsedSeconds)
        assertEquals(10, clock.snapshot(100_000).remainingSeconds)
    }

    @Test fun penaltiesSaturateInsteadOfOverflowingIntoSuccess() {
        val clock = SessionClock()
        clock.reset(Int.MAX_VALUE)
        clock.start(0)
        clock.addPenalty(600_000)
        assertEquals(Int.MAX_VALUE, clock.snapshot(0).remainingSeconds)
        assertFalse(clock.snapshot(0).finished)
    }
}
