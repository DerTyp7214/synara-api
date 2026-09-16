package dev.dertyp.routing.rest

import dev.dertyp.services.metadata.IMetadataService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

private enum class Flavor { SWEET, SOUR }

class RestConvertTest {
    private fun message(block: () -> Any): String = assertThrows(RestBindingException::class.java) { block() }.message!!

    @Test
    fun `string passes through`() {
        assertEquals("abc", RestConvert.string.convert("abc"))
    }

    @Test
    fun `numbers convert or fail with the legacy message`() {
        assertEquals(42, RestConvert.int.convert("42"))
        assertEquals(42L, RestConvert.long.convert("42"))
        assertEquals(4.5, RestConvert.double.convert("4.5"))
        assertEquals(4.5f, RestConvert.float.convert("4.5"))
        assertEquals("Invalid Int value: abc", message { RestConvert.int.convert("abc") })
        assertEquals("Invalid Long value: 1.5", message { RestConvert.long.convert("1.5") })
        assertEquals("Invalid Double value: x", message { RestConvert.double.convert("x") })
        assertEquals("Invalid Float value: x", message { RestConvert.float.convert("x") })
    }

    @Test
    fun `boolean is strict`() {
        assertEquals(true, RestConvert.boolean.convert("true"))
        assertEquals(false, RestConvert.boolean.convert("false"))
        assertEquals("Invalid Boolean value: TRUE", message { RestConvert.boolean.convert("TRUE") })
        assertEquals("Invalid Boolean value: 1", message { RestConvert.boolean.convert("1") })
    }

    @Test
    fun `uuid and instant parse or fail`() {
        val id = UUID.randomUUID()
        assertEquals(id, RestConvert.uuid.convert(id.toString()))
        assertEquals("Invalid UUID value: nope", message { RestConvert.uuid.convert("nope") })
        assertEquals(Instant.parse("2024-01-02T03:04:05Z"), RestConvert.instant.convert("2024-01-02T03:04:05Z"))
        assertEquals("Invalid Instant value: yesterday", message { RestConvert.instant.convert("yesterday") })
    }

    @Test
    fun `metadata type wraps the raw value`() {
        assertEquals(IMetadataService.MetadataType("tidal"), RestConvert.metadataType.convert("tidal"))
    }

    @Test
    fun `enum matches ignoring case`() {
        val convert = RestConvert.enum(Flavor.entries.toTypedArray())
        assertEquals(Flavor.SWEET, convert.convert("sweet"))
        assertEquals(Flavor.SOUR, convert.convert("SOUR"))
        assertEquals("Invalid Enum value: bitter", message { convert.convert("bitter") })
    }

    @Test
    fun `binding exception is an illegal argument`() {
        val e = RestBindingException("boom")
        assertEquals(true, e is IllegalArgumentException)
        assertEquals("boom", e.message)
    }
}
