package dev.dertyp.plugins

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PartialDatesTest {
    @Test
    fun `a full date is taken as it is`() {
        assertEquals(LocalDate.of(2016, 5, 20), parsePartialDate("2016-05-20"))
        assertEquals(LocalDate.of(2016, 5, 20), parsePartialDate(" 2016-05-20 "))
        assertEquals(LocalDate.of(2016, 5, 20), parsePartialDate("2016-05-20T10:15:30Z"))
    }

    @Test
    fun `a year and month becomes the first day of the month`() {
        assertEquals(LocalDate.of(2016, 5, 1), parsePartialDate("2016-05"))
    }

    @Test
    fun `a year becomes the first day of the year`() {
        assertEquals(LocalDate.of(2016, 1, 1), parsePartialDate("2016"))
    }

    @Test
    fun `anything else is no date`() {
        assertNull(parsePartialDate(null))
        assertNull(parsePartialDate(""))
        assertNull(parsePartialDate("   "))
        assertNull(parsePartialDate("soon"))
        assertNull(parsePartialDate("20a6"))
        assertNull(parsePartialDate("2016-13"))
        assertNull(parsePartialDate("2016-02-30"))
        assertNull(parsePartialDate("16"))
        assertNull(parsePartialDate("2016-5"))
        assertNull(parsePartialDate("unknown date"))
    }
}
