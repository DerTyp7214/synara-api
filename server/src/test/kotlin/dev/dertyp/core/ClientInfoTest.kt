package dev.dertyp.core

import dev.dertyp.data.ApiVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientInfoTest {

    @Test
    fun `missing or invalid header resolves to legacy version`() {
        assertEquals(ApiVersion.LEGACY, ClientInfo.fromHeader(null).apiVersion)
        assertEquals(ApiVersion.LEGACY, ClientInfo.fromHeader("").apiVersion)
        assertEquals(ApiVersion.LEGACY, ClientInfo.fromHeader("abc").apiVersion)
        assertEquals(ApiVersion.LEGACY, ClientInfo.fromHeader("0").apiVersion)
        assertEquals(ApiVersion.LEGACY, ClientInfo.fromHeader("-3").apiVersion)
    }

    @Test
    fun `header is parsed as integer`() {
        assertEquals(2, ClientInfo.fromHeader("2").apiVersion)
        assertEquals(7, ClientInfo.fromHeader(" 7 ").apiVersion)
    }

    @Test
    fun `legacy clients do not support wav aiff streaming`() {
        assertFalse(ClientInfo.LEGACY.supports(ClientFeature.LOSSLESS_WAV_AIFF))
        assertTrue(ClientInfo(2).supports(ClientFeature.LOSSLESS_WAV_AIFF))
        assertTrue(ClientInfo(99).supports(ClientFeature.LOSSLESS_WAV_AIFF))
    }

    @Test
    fun `dolby atmos requires api version 3`() {
        assertFalse(ClientInfo.LEGACY.supports(ClientFeature.DOLBY_ATMOS))
        assertFalse(ClientInfo(2).supports(ClientFeature.DOLBY_ATMOS))
        assertTrue(ClientInfo(3).supports(ClientFeature.DOLBY_ATMOS))
    }

    @Test
    fun `every feature is available at the current api version`() {
        ClientFeature.entries.forEach { feature ->
            assertTrue(ClientInfo(ApiVersion.CURRENT).supports(feature), "${feature.name} must be supported at CURRENT")
        }
    }
}
