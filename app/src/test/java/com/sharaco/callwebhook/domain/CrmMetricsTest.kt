package com.sharaco.callwebhook.domain

import org.junit.Assert.*
import org.junit.Test

class CrmMetricsTest {
    @Test fun outcomesDoNotBecomeTelephoneConnections() {
        val attempts = listOf(mapOf("dispositionId" to "converted"), mapOf("dispositionId" to "interested"))
        assertEquals(0.0, targetAchievement("connected", emptyList(), attempts), 0.0)
        assertEquals(1.0, targetAchievement("converted", emptyList(), attempts), 0.0)
    }
    @Test fun callMetricsMatchWebAndIgnoreMissedCallDurationForConnections() {
        val calls = listOf(mapOf("type" to "OUTGOING", "durationSec" to 60), mapOf("type" to "OUTGOING", "durationSec" to 0), mapOf("type" to "ANSWERED_EXTERNALLY", "durationSec" to 120), mapOf("type" to "MISSED", "durationSec" to 0))
        assertEquals(4.0, targetAchievement("calls", calls, emptyList()), 0.0)
        assertEquals(2.0, targetAchievement("outgoing", calls, emptyList()), 0.0)
        assertEquals(2.0, targetAchievement("connected", calls, emptyList()), 0.0)
        assertEquals(3.0, targetAchievement("minutes", calls, emptyList()), 0.0)
    }
    @Test fun partialDateWindowsCannotProduceTargetTotals() {
        assertTrue(targetWindowCovered(100, 300, 100, 300))
        assertFalse(targetWindowCovered(101, 300, 100, 300))
        assertFalse(targetWindowCovered(100, 299, 100, 300))
        assertFalse(targetWindowCovered(0, 300, 0, 0))
    }
}
