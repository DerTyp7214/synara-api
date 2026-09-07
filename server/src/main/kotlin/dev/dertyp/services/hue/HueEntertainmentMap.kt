package dev.dertyp.services.hue

data class HueEntertainmentChannel(
    val id: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val lightIds: List<String>,
)

data class HueEntertainmentArea(
    val id: String,
    val name: String,
    val channels: List<HueEntertainmentChannel>,
    val lightIds: Set<String>,
    val active: Boolean,
    val activeStreamer: String?,
) {
    val orderedChannelIds: List<Int> get() = channels.map { it.id }
}

object HueEntertainmentMap {
    private const val DEFAULT_NAME = "Entertainment area"

    fun build(
        configurations: List<ClipEntertainmentConfiguration>,
        services: List<ClipEntertainment>,
        lights: List<ClipLight>,
    ): List<HueEntertainmentArea> {
        val deviceByService = services.associate { it.id to it.owner?.rid }
        val lightsByDevice = lights.groupBy { it.owner?.rid }
        return configurations.mapNotNull { configuration ->
            val channels = configuration.channels
                .sortedWith(compareBy({ it.position?.x ?: 0.0 }, { it.channelId }))
                .map { channel ->
                    HueEntertainmentChannel(
                        id = channel.channelId,
                        x = channel.position?.x ?: 0.0,
                        y = channel.position?.y ?: 0.0,
                        z = channel.position?.z ?: 0.0,
                        lightIds = channel.members
                            .mapNotNull { deviceByService[it.service.rid] }
                            .flatMap { lightsByDevice[it].orEmpty() }
                            .map { it.id },
                    )
                }
            if (channels.isEmpty()) return@mapNotNull null
            val declared = configuration.lightServices.map { it.rid }.toSet()
            HueEntertainmentArea(
                id = configuration.id,
                name = configuration.metadata?.name ?: DEFAULT_NAME,
                channels = channels,
                lightIds = declared.ifEmpty { channels.flatMap { it.lightIds }.toSet() },
                active = configuration.active,
                activeStreamer = configuration.activeStreamer?.rid,
            )
        }.sortedBy { it.name }
    }
}
