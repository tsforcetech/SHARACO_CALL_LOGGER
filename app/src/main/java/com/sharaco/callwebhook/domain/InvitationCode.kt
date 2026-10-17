package com.sharaco.callwebhook.domain

import java.security.SecureRandom
import java.util.Locale

/** 128 random bits, grouped for typing. The QR contains the very same code. */
object InvitationCode {
    fun create(): String =
        ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) }

    fun parse(value: String): String {
        val code = value.trim().replace("-", "").replace(" ", "").uppercase(Locale.ROOT)
        require(code.matches(Regex("[0-9A-F]{32}"))) {
            "Enter the complete 32-character invitation code or scan its QR. Code: INVITE_FORMAT"
        }
        return code
    }

    fun display(code: String): String = parse(code).chunked(4).joinToString("-")
}
