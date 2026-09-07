package dev.dertyp.services.hue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HueEntertainmentMapTest {
    private val services = listOf(
        ClipEntertainment("svc-a", ClipResourceRef("dev-a", "device")),
        ClipEntertainment("svc-b", ClipResourceRef("dev-b", "device")),
        ClipEntertainment("svc-c", ClipResourceRef("dev-c", "device")),
    )
    private val lights = listOf(
        light("l1", "dev-a"),
        light("l2", "dev-a"),
        light("l3", "dev-b"),
        light("l4", "dev-c"),
    )

    private fun light(id: String, device: String) = ClipLight(id = id, owner = ClipResourceRef(device, "device"))

    private fun channel(id: Int, x: Double, vararg serviceIds: String) = ClipEntertainmentChannel(
        channelId = id,
        position = ClipPosition(x, 0.5, -0.5),
        members = serviceIds.map { ClipChannelMember(ClipResourceRef(it, "entertainment"), index = 0) },
    )

    private fun configuration(
        id: String,
        name: String,
        channels: List<ClipEntertainmentChannel>,
        lightServices: List<String> = emptyList(),
        status: String? = null,
        streamer: String? = null,
    ) = ClipEntertainmentConfiguration(
        id = id,
        metadata = ClipMetadata(name),
        configurationType = "screen",
        status = status,
        activeStreamer = streamer?.let { ClipResourceRef(it, "auth_v1") },
        channels = channels,
        lightServices = lightServices.map { ClipResourceRef(it, "light") },
    )

    @Test
    fun `channels are ordered by position and joined through service, device and light`() {
        val configuration = configuration(
            "c1",
            "TV",
            listOf(channel(2, 0.5, "svc-c"), channel(1, -0.5, "svc-b"), channel(0, -0.5, "svc-a")),
        )
        val area = HueEntertainmentMap.build(listOf(configuration), services, lights).single()
        assertEquals(listOf(0, 1, 2), area.orderedChannelIds)
        assertEquals(listOf("l1", "l2"), area.channels[0].lightIds)
        assertEquals(listOf("l3"), area.channels[1].lightIds)
        assertEquals(listOf("l4"), area.channels[2].lightIds)
        assertEquals(0.5, area.channels[2].x)
        assertEquals(0.5, area.channels[2].y)
        assertEquals(-0.5, area.channels[2].z)
    }

    @Test
    fun `unknown members and missing positions are tolerated`() {
        val configuration = configuration(
            "c1",
            "TV",
            listOf(
                ClipEntertainmentChannel(channelId = 5, members = listOf(ClipChannelMember(ClipResourceRef("svc-a", "entertainment")))),
                ClipEntertainmentChannel(channelId = 1, members = listOf(ClipChannelMember(ClipResourceRef("ghost", "entertainment")))),
            ),
        )
        val area = HueEntertainmentMap.build(listOf(configuration), services, lights).single()
        assertEquals(listOf(1, 5), area.orderedChannelIds)
        assertTrue(area.channels.first().lightIds.isEmpty())
        assertEquals(0.0, area.channels.first().x)
    }

    @Test
    fun `light services win over the channel union and the union is the fallback`() {
        val channels = listOf(channel(0, 0.0, "svc-a"), channel(1, 1.0, "svc-b"))
        val declared = configuration("c1", "TV", channels, lightServices = listOf("l9"))
        val derived = configuration("c2", "Desk", channels)
        val areas = HueEntertainmentMap.build(listOf(declared, derived), services, lights)
        assertEquals(listOf("Desk", "TV"), areas.map { it.name })
        assertEquals(setOf("l1", "l2", "l3"), areas.single { it.id == "c2" }.lightIds)
        assertEquals(setOf("l9"), areas.single { it.id == "c1" }.lightIds)
    }

    @Test
    fun `configurations without channels are dropped and the name falls back`() {
        val empty = ClipEntertainmentConfiguration(id = "c0", metadata = ClipMetadata("Empty"))
        val unnamed = ClipEntertainmentConfiguration(id = "c1", channels = listOf(channel(0, 0.0, "svc-a")))
        val areas = HueEntertainmentMap.build(listOf(empty, unnamed), services, lights)
        assertEquals(listOf("Entertainment area"), areas.map { it.name })
        assertEquals("c1", areas.single().id)
        assertTrue(HueEntertainmentMap.build(emptyList(), services, lights).isEmpty())
    }

    @Test
    fun `the streaming status and its streamer are mapped`() {
        val streaming = configuration("c1", "TV", listOf(channel(0, 0.0, "svc-a")), status = "active", streamer = "app-1")
        val idle = configuration("c2", "Desk", listOf(channel(0, 0.0, "svc-b")), status = "inactive")
        val areas = HueEntertainmentMap.build(listOf(streaming, idle), services, lights).associateBy { it.id }
        assertTrue(areas.getValue("c1").active)
        assertEquals("app-1", areas.getValue("c1").activeStreamer)
        assertFalse(areas.getValue("c2").active)
        assertNull(areas.getValue("c2").activeStreamer)
    }
}
