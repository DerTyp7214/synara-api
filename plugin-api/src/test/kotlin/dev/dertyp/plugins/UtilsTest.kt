package dev.dertyp.plugins

import kotlin.test.Test
import kotlin.test.assertEquals

class UtilsTest {
    @Test
    fun testToHumanReadableSize() {
        assertEquals("0 Bytes", 0.toHumanReadableSize())
        assertEquals("1.0 KB", 1024.toHumanReadableSize())
        assertEquals("1.0 MB", (1024 * 1024).toHumanReadableSize())
        assertEquals("1.0 GB", (1024L * 1024 * 1024).toHumanReadableSize())
    }
}
