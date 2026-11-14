package com.mockonomy.ui.screens.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PortInputTest {

    @Test
    fun `a port inside the range is accepted`() {
        assertNull(portInputError("8080"))
    }

    @Test
    fun `the range boundaries are accepted`() {
        assertNull(portInputError("1024"))
        assertNull(portInputError("65535"))
    }

    @Test
    fun `a privileged port below 1024 is rejected`() {
        assertEquals("Must be between 1024 and 65535", portInputError("80"))
        assertNotNull(portInputError("1023"))
    }

    @Test
    fun `a value above 65535 is rejected`() {
        // 80818 was typed on a device and persisted; it is not a TCP port.
        assertNotNull(portInputError("80818"))
        assertNotNull(portInputError("65536"))
    }

    @Test
    fun `zero is rejected even though the server treats it as any free port`() {
        assertNotNull(portInputError("0"))
    }

    @Test
    fun `empty input is rejected`() {
        assertNotNull(portInputError(""))
        assertNotNull(portInputError("   "))
    }
}
