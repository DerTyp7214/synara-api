package dev.dertyp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

class UtilsTest {

    @Test
    fun `getDateFromISO should parse valid ISO date`() {
        assertEquals(LocalDate.of(2023, 10, 27), getDateFromISO("2023-10-27"))
    }

    @Test
    fun `getDateFromISO should return null for null input`() {
        assertNull(getDateFromISO(null))
    }

    @Test
    fun `getDateTimeFromISO should parse valid ISO date time`() {
        val expected = LocalDateTime.of(2023, 10, 27, 10, 0, 0)
        assertEquals(expected, getDateTimeFromISO("2023-10-27T10:00:00"))
    }

    @Test
    fun `getDateTimeFromISO should return null for null input`() {
        assertNull(getDateTimeFromISO(null))
    }

    @Test
    fun `getISOFromDate should format date to ISO`() {
        assertEquals("2023-10-27", getISOFromDate(LocalDate.of(2023, 10, 27)))
    }

    @Test
    fun `getISOFromDate should return null for null input`() {
        assertNull(getISOFromDate(null))
    }

    @Test
    fun `getISOFromDateTime should format date time to ISO`() {
        val dt = LocalDateTime.of(2023, 10, 27, 10, 0, 0)
        assertEquals("2023-10-27T10:00:00", getISOFromDateTime(dt))
    }

    @Test
    fun `executeCommand should return success for echo`() = runBlocking {
        val result = executeCommand(
            command = listOf("echo", "hello world"),
            aliveCheck = { true }
        )
        assertEquals(0, result.exitCode)
        assertTrue(result.fullOutput.contains("hello world"))
    }

    @Test
    fun `findInPath should find common executables`() {
        val sh = findInPath("sh")
        assertNotNull(sh)
        assertTrue(File(sh!!).exists())
    }
}
