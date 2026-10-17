package com.sharaco.callwebhook

import com.sharaco.callwebhook.domain.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    @Test
    fun identityIncludesDeviceAndTimestamp() {
        assertEquals(callId("a", "1", 2), callId("a", "1", 2))
        assertNotEquals(callId("a", "1", 2), callId("a", "1", 3))
    }

    @Test
    fun unknownTypesAreSafe() {
        assertEquals("UNKNOWN", callType(999))
        assertFalse(connected("MISSED", 10))
        assertTrue(connected("OUTGOING", 1))
    }

    @Test
    fun dateBoundariesFollowCompanyTimezone() {
        val p = Filters(LocalDate.of(2026, 10, 16)).payload("Asia/Dubai")
        assertEquals(1792094400000L, p.num("from"))
    }
}
