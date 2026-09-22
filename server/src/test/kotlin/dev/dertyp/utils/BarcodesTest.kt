package dev.dertyp.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BarcodesTest {

    @Test
    fun `normalize strips padding, dashes and spaces`() {
        assertEquals("602445790000", Barcodes.normalize("00602445790000"))
        assertEquals("602445790000", Barcodes.normalize("602445790000"))
        assertEquals("602445790000", Barcodes.normalize(" 0-602-445-790-000 "))
        assertEquals("75678643101", Barcodes.normalize("075678643101"))
    }

    @Test
    fun `normalize rejects short and blank input`() {
        assertNull(Barcodes.normalize(null))
        assertNull(Barcodes.normalize(""))
        assertNull(Barcodes.normalize("   "))
        assertNull(Barcodes.normalize("BARCODE"))
        assertNull(Barcodes.normalize("1234567"))
        assertNull(Barcodes.normalize("00000000001"))
    }

    @Test
    fun `variants pad a twelve digit code`() {
        assertEquals(
            listOf("602445790000", "0602445790000", "00602445790000"),
            Barcodes.variants("602445790000")
        )
        assertEquals(
            listOf("602445790000", "0602445790000", "00602445790000"),
            Barcodes.variants("00602445790000")
        )
    }

    @Test
    fun `variants pad a thirteen digit code`() {
        assertEquals(
            listOf("6024457900001", "06024457900001"),
            Barcodes.variants("6024457900001")
        )
        assertEquals(
            listOf("60244579000012"),
            Barcodes.variants("60244579000012")
        )
    }

    @Test
    fun `variants of short or blank input are empty`() {
        assertTrue(Barcodes.variants(null).isEmpty())
        assertTrue(Barcodes.variants("").isEmpty())
        assertTrue(Barcodes.variants("1234567").isEmpty())
    }
}
