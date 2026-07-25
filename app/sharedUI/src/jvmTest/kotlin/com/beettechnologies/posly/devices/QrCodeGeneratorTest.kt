package com.beettechnologies.posly.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class QrCodeGeneratorTest {

    @Test
    fun `generates a square bitmap of the requested size`() {
        val bitmap = assertNotNull(generateQrCodeImageBitmap("PAIR-CODE-123", size = 64))
        assertEquals(64, bitmap.width)
        assertEquals(64, bitmap.height)
    }
}
