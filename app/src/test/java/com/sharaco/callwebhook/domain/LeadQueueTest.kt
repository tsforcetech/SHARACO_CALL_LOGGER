package com.sharaco.callwebhook.domain
import org.junit.Assert.*
import org.junit.Test
class LeadQueueTest {
    @Test fun savedNonTerminalOutcomesLeavePending() {
        assertTrue(pendingLead(mapOf("status" to "new")))
        for (status in listOf("interested", "busy", "no_answer", "call_back", "converted")) {
            assertFalse(pendingLead(mapOf("status" to status, "lastAttemptId" to "saved")))
        }
        assertFalse(pendingLead(mapOf("status" to "new", "lastContactAt" to 123L)))
    }
    @Test fun uploadOrderOverridesPhoneIds() {
        val rows = listOf(mapOf("id" to "phone_1", "importStartedAt" to 100L, "importRow" to 3L), mapOf("id" to "phone_9", "importStartedAt" to 100L, "importRow" to 2L), mapOf("id" to "legacy", "createdAt" to 5L))
        assertEquals(listOf("legacy", "phone_9", "phone_1"), orderedLeads(rows).map { it.str("id") })
    }
}
