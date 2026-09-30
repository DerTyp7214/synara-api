package dev.dertyp.core

import dev.dertyp.services.import.Type
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProviderLookupTest {

    @Test
    fun `providerLookup accepts untyped ids and ids of the requested type`() = runBlocking {
        assertEquals(ProviderLookup("tidal", "42"), providerLookup("tidal:42", Type.ALBUM))
        assertEquals(ProviderLookup("tidal", "42"), providerLookup("https://tidal.com/album/42", Type.ALBUM))
        assertEquals(ProviderLookup("tidal", "42"), providerLookup("https://tidal.com/track/42", Type.SONG))
    }

    @Test
    fun `providerLookup rejects ids of another type unless no type is requested`() = runBlocking {
        assertNull(providerLookup("https://tidal.com/track/42", Type.ALBUM))
        assertEquals(ProviderLookup("tidal", "42"), providerLookup("https://tidal.com/track/42"))
    }

    @Test
    fun `providerLookup returns null for unknown urls`() = runBlocking {
        assertNull(providerLookup("https://example.invalid/whatever", Type.SONG))
    }
}
