package com.sharaco.callwebhook

import com.sharaco.callwebhook.domain.InvitationCode
import org.junit.Assert.*
import org.junit.Test

class InvitationCodeTest {
    @Test
    fun typedAndQrPayloadResolveIdentically() {
        val code = InvitationCode.create()
        assertEquals(32, code.length)
        assertEquals(code, InvitationCode.parse(InvitationCode.display(code)))
        assertEquals(code, InvitationCode.parse(InvitationCode.display(code).lowercase()))
    }

    @Test
    fun shortOrMalformedCodesAreRejected() {
        listOf("123456", "https://attacker.invalid/", "A".repeat(31), "Z".repeat(32)).forEach {
            assertThrows(IllegalArgumentException::class.java) { InvitationCode.parse(it) }
        }
    }
}
