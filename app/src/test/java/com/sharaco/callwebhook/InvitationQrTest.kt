package com.sharaco.callwebhook

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.sharaco.callwebhook.domain.InvitationCode
import org.junit.Assert.assertEquals
import org.junit.Test

class InvitationQrTest {
    @Test
    fun generatedQrDecodesToExactlyTheTypedInvitation() {
        val token = InvitationCode.display(InvitationCode.create())
        val matrix = MultiFormatWriter().encode(token, BarcodeFormat.QR_CODE, 480, 480)
        val pixels = IntArray(480 * 480) { p -> if (matrix[p % 480, p / 480]) -0x1000000 else -1 }
        val decoded =
            MultiFormatReader()
                .decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(480, 480, pixels))))
                .text
        assertEquals(InvitationCode.parse(token), InvitationCode.parse(decoded))
    }
}
