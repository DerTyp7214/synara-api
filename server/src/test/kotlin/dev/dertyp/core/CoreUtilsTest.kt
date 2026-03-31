package dev.dertyp.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoreUtilsTest {

    @Test
    fun `duplicatesBy should find duplicate elements`() {
        val list = listOf("a", "b", "a", "c", "b")
        val duplicates = list.duplicatesBy { it }
        assertEquals(listOf("a", "a", "b", "b"), duplicates.sorted())
    }

    @Test
    fun `toHumanReadableSize should format correctly`() {
        assertEquals("1.0 KB", 1024L.toHumanReadableSize())
        assertEquals("1.5 MB", (1024L * 1024 * 1.5).toLong().toHumanReadableSize())
        assertEquals("0 Bytes", 0L.toHumanReadableSize())
    }

    @Test
    fun `Quadruple should hold values`() {
        val q = Quadruple(1, "2", 3.0, true)
        assertEquals(1, q.first)
        assertEquals("2", q.second)
        assertEquals(3.0, q.third)
        assertEquals(true, q.fourth)
    }

    @Test
    fun `Quintuple should hold values`() {
        val q = Quintuple(1, "2", 3.0, true, '5')
        assertEquals(1, q.first)
        assertEquals("2", q.second)
        assertEquals(3.0, q.third)
        assertEquals(true, q.fourth)
        assertEquals('5', q.fifth)
    }
}
