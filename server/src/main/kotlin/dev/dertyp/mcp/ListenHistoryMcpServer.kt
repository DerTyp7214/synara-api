package dev.dertyp.mcp

import dev.dertyp.data.StatsRange
import dev.dertyp.data.TopOrder
import dev.dertyp.data.User
import dev.dertyp.db.ListenSource
import dev.dertyp.serializers.AppJson
import dev.dertyp.server.BuildConfig
import dev.dertyp.services.ListeningStatsService
import dev.dertyp.services.Service
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.ZoneId
import java.util.UUID

private const val INSTRUCTIONS = """
Synara is a personal music server. Every tool on this server reads the listen history and library of the
authenticated user only; there is no way to reach another user's data.

Ids are UUID strings. Use `search_library` first to turn a song, artist or album name into the id that the
other tools expect.

All timestamps are returned as an object with `epochMs` (epoch milliseconds, UTC) and `iso` (ISO-8601 with
offset). Time inputs (`from`, `to`) accept either an ISO-8601 value (a date, a local date-time, or a date-time
with an offset or Z) or epoch milliseconds. `from` is inclusive, `to` is exclusive.

The optional `timezone` argument is an IANA zone name such as `Europe/Berlin`. It controls local-time bucketing
(hour of day, day of week, timeline buckets) and how time inputs without an explicit offset are interpreted.
It defaults to UTC, so pass the user's zone when the answer depends on local time.

Listens imported from ListenBrainz may not be matched to a song in the library. Those entries carry
`unmatched` metadata (track, artist and release names plus MusicBrainz ids) instead of a `song`, and appear in
top lists with `matched = false` and a null `id`.

Every tool is read-only; nothing on this server modifies the library, the listen history or any setting.
"""

private const val FROM_DESC =
    "Start of the window, inclusive. ISO-8601 (date, local date-time, or date-time with offset) or epoch milliseconds. Omit for no lower bound."
private const val TO_DESC =
    "End of the window, exclusive. ISO-8601 (date, local date-time, or date-time with offset) or epoch milliseconds. Omit for no upper bound."
private const val TIMEZONE_DESC =
    "IANA timezone name (e.g. Europe/Berlin) used for local-time bucketing and for interpreting time inputs without an offset. Defaults to UTC."
private const val SONG_ID_DESC = "Restrict the result to this song id (a UUID, resolve names with search_library)."
private const val ARTIST_ID_DESC = "Restrict the result to this artist id (a UUID, resolve names with search_library)."
private const val ALBUM_ID_DESC = "Restrict the result to this album id (a UUID, resolve names with search_library)."

private val ReadOnlyTool = ToolAnnotations(
    readOnlyHint = true,
    destructiveHint = false,
    idempotentHint = true,
    openWorldHint = false,
)

private val StatsJson = Json(from = AppJson) { explicitNulls = false }

private fun stringSchema(description: String, values: List<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (values != null) putJsonArray("enum") { values.forEach { add(it) } }
}

private fun integerSchema(description: String, minimum: Int? = null, maximum: Int? = null, default: Int? = null): JsonObject =
    buildJsonObject {
        put("type", "integer")
        put("description", description)
        minimum?.let { put("minimum", it) }
        maximum?.let { put("maximum", it) }
        default?.let { put("default", it) }
    }

private fun booleanSchema(description: String, default: Boolean? = null): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
    default?.let { put("default", it) }
}

private fun stringArraySchema(description: String, values: List<String>): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", stringSchema("One of ${values.joinToString(", ")}.", values))
}

private fun normalizeEnumValue(value: String): String = value.replace("_", "").replace("-", "").lowercase()

private object McpArgs {
    private fun element(args: JsonObject?, name: String): JsonElement? = args?.get(name)?.takeUnless { it is JsonNull }

    private fun primitive(args: JsonObject?, name: String): JsonPrimitive? = element(args, name)?.let {
        it as? JsonPrimitive ?: throw IllegalArgumentException("Parameter '$name' must be a scalar value")
    }

    fun string(args: JsonObject?, name: String): String? = primitive(args, name)?.content?.takeIf { it.isNotBlank() }

    fun requiredString(args: JsonObject?, name: String): String =
        string(args, name) ?: throw IllegalArgumentException("Parameter '$name' is required")

    fun int(args: JsonObject?, name: String, default: Int, min: Int, max: Int): Int {
        val raw = primitive(args, name) ?: return default.coerceIn(min, max)
        val value = raw.content.toIntOrNull()
            ?: throw IllegalArgumentException("Parameter '$name' must be an integer, got '${raw.content}'")
        return value.coerceIn(min, max)
    }

    fun bool(args: JsonObject?, name: String, default: Boolean): Boolean {
        val raw = primitive(args, name) ?: return default
        return raw.content.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("Parameter '$name' must be a boolean, got '${raw.content}'")
    }

    fun <T : Enum<T>> enum(
        args: JsonObject?,
        name: String,
        values: Array<T>,
        default: T? = null,
        allowed: List<String> = values.map { it.name },
    ): T? {
        val raw = string(args, name) ?: return default
        val wanted = normalizeEnumValue(raw)
        return values.firstOrNull { normalizeEnumValue(it.name) == wanted }
            ?: throw IllegalArgumentException("Parameter '$name' must be one of ${allowed.joinToString(", ")}, got '$raw'")
    }

    fun <T : Enum<T>> requiredEnum(
        args: JsonObject?,
        name: String,
        values: Array<T>,
        allowed: List<String> = values.map { it.name },
    ): T = enum(args, name, values, null, allowed) ?: throw IllegalArgumentException("Parameter '$name' is required")

    fun uuid(args: JsonObject?, name: String): UUID? {
        val raw = string(args, name) ?: return null
        return runCatching { UUID.fromString(raw) }
            .getOrElse { throw IllegalArgumentException("Parameter '$name' must be a UUID, got '$raw'") }
    }

    fun stringList(args: JsonObject?, name: String, allowed: List<String>): List<String>? {
        val raw = element(args, name) ?: return null
        val array = raw as? JsonArray ?: throw IllegalArgumentException("Parameter '$name' must be an array of strings")
        val values = array.map {
            val item = it as? JsonPrimitive ?: throw IllegalArgumentException("Parameter '$name' must be an array of strings")
            val wanted = normalizeEnumValue(item.content)
            allowed.firstOrNull { candidate -> normalizeEnumValue(candidate) == wanted }
                ?: throw IllegalArgumentException("Parameter '$name' must only contain ${allowed.joinToString(", ")}, got '${item.content}'")
        }
        return values.takeIf { it.isNotEmpty() }
    }

    fun time(args: JsonObject?, name: String, zone: ZoneId): Long? {
        val raw = primitive(args, name)?.content?.takeIf { it.isNotBlank() } ?: return null
        return parseMcpTime(raw, zone)
    }

    fun zone(args: JsonObject?): ZoneId = mcpZone(string(args, "timezone"))
}

private inline fun <reified T> toolResult(value: T): CallToolResult =
    CallToolResult(content = listOf(TextContent(text = McpToolJson.encodeToString(value))))

class ListenHistoryMcpServerFactory(
    private val query: ListenHistoryQueryService,
    private val stats: ListeningStatsService,
) : Service() {

    fun create(user: User): Server = Server(
        Implementation(name = "synara", version = BuildConfig.VERSION),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = INSTRUCTIONS.trimIndent().trim(),
    ) {
        addSearchLibrary(user)
        addGetListens(user)
        addGetListeningSummary(user)
        addGetTop(user)
        addGetListenTimeline(user)
        addGetListeningStats(user)
        addGetNowPlaying(user)
    }

    private fun Server.addSearchLibrary(user: User) = addTool(
        name = "search_library",
        description = "Search the user's library for songs, artists and albums by name and return their ids. " +
            "Use this to resolve a name mentioned by the user into the songId, artistId or albumId that the other tools take.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("query", stringSchema("Free-text search terms, e.g. a song title, artist name or album name."))
                put(
                    "types",
                    stringArraySchema(
                        "Which entity types to search. Defaults to all three.",
                        listOf("songs", "artists", "albums"),
                    ),
                )
                put("limit", integerSchema("Maximum number of results per type.", minimum = 1, maximum = 50, default = 10))
            },
            required = listOf("query"),
        ),
        toolAnnotations = ReadOnlyTool,
    ) { request ->
        val args = request.params.arguments
        val terms = McpArgs.requiredString(args, "query")
        val types = McpArgs.stringList(args, "types", listOf("songs", "artists", "albums"))
            ?: listOf("songs", "artists", "albums")
        val limit = McpArgs.int(args, "limit", 10, 1, 50)
        toolResult(
            query.search(
                userId = user.id,
                query = terms,
                includeSongs = "songs" in types,
                includeArtists = "artists" in types,
                includeAlbums = "albums" in types,
                limit = limit,
            ),
        )
    }

    private fun Server.addGetListens(user: User) = addTool(
        name = "get_listens",
        description = "List the user's individual listens, newest first, optionally filtered by time window, song, artist, " +
            "album or source. Returns a page of listens plus a cursor for the next page.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("from", stringSchema(FROM_DESC))
                put("to", stringSchema(TO_DESC))
                put(
                    "limit",
                    integerSchema(
                        "Maximum number of listens to return.",
                        minimum = 1,
                        maximum = ListenHistoryQueryService.MAX_LISTENS_LIMIT,
                        default = 100,
                    ),
                )
                put("cursor", stringSchema("Opaque cursor from the nextCursor field of a previous get_listens result."))
                put("songId", stringSchema(SONG_ID_DESC))
                put("artistId", stringSchema(ARTIST_ID_DESC))
                put("albumId", stringSchema(ALBUM_ID_DESC))
                put(
                    "source",
                    stringSchema(
                        "Only return listens from this source: LOCAL for plays on this server, LISTENBRAINZ for imported ones.",
                        listOf("LOCAL", "LISTENBRAINZ"),
                    ),
                )
                put(
                    "qualifiedOnly",
                    booleanSchema("Only return listens that played long enough to count as a real listen.", default = false),
                )
                put("timezone", stringSchema(TIMEZONE_DESC))
            },
            required = emptyList(),
        ),
        toolAnnotations = ReadOnlyTool,
    ) { request ->
        val args = request.params.arguments
        val zone = McpArgs.zone(args)
        val page = query.listens(
            userId = user.id,
            filter = filter(args, zone),
            limit = McpArgs.int(args, "limit", 100, 1, ListenHistoryQueryService.MAX_LISTENS_LIMIT),
            cursor = McpArgs.string(args, "cursor"),
            zone = zone,
        )
        toolResult(page)
    }

    private fun Server.addGetListeningSummary(user: User) = addTool(
        name = "get_listening_summary",
        description = "Aggregate the user's listens over a time window: totals, unique songs, artists and albums, " +
            "first and last listen, and the distribution over the hours of the day and days of the week in the given timezone.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("from", stringSchema(FROM_DESC))
                put("to", stringSchema(TO_DESC))
                put("songId", stringSchema(SONG_ID_DESC))
                put("artistId", stringSchema(ARTIST_ID_DESC))
                put("albumId", stringSchema(ALBUM_ID_DESC))
                put("timezone", stringSchema(TIMEZONE_DESC))
            },
            required = emptyList(),
        ),
        toolAnnotations = ReadOnlyTool,
    ) { request ->
        val args = request.params.arguments
        val zone = McpArgs.zone(args)
        toolResult(query.summary(userId = user.id, filter = filter(args, zone), zone = zone))
    }

    private fun Server.addGetTop(user: User) = addTool(
        name = "get_top",
        description = "Rank the user's most listened songs, artists or albums over a time window, by listen count or by " +
            "milliseconds listened. Supports paging through offset and limit.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("kind", stringSchema("What to rank.", listOf("songs", "artists", "albums")))
                put("from", stringSchema(FROM_DESC))
                put("to", stringSchema(TO_DESC))
                put(
                    "limit",
                    integerSchema(
                        "Maximum number of entries to return.",
                        minimum = 1,
                        maximum = ListenHistoryQueryService.MAX_TOP_LIMIT,
                        default = 20,
                    ),
                )
                put("offset", integerSchema("Number of entries to skip, for paging.", minimum = 0, default = 0))
                put(
                    "orderBy",
                    stringSchema(
                        "Ranking criterion: listenCount ranks by deduplicated listen count, listenedMs by total milliseconds listened.",
                        listOf("listenCount", "listenedMs"),
                    ),
                )
                put("artistId", stringSchema(ARTIST_ID_DESC))
                put("albumId", stringSchema(ALBUM_ID_DESC))
                put("timezone", stringSchema(TIMEZONE_DESC))
            },
            required = listOf("kind"),
        ),
        toolAnnotations = ReadOnlyTool,
    ) { request ->
        val args = request.params.arguments
        val zone = McpArgs.zone(args)
        val kind = McpArgs.requiredEnum(args, "kind", McpTopKind.entries.toTypedArray(), listOf("songs", "artists", "albums"))
        val order = McpArgs.enum(
            args,
            "orderBy",
            McpTopOrder.entries.toTypedArray(),
            McpTopOrder.LISTEN_COUNT,
            listOf("listenCount", "listenedMs"),
        ) ?: McpTopOrder.LISTEN_COUNT
        toolResult(
            query.top(
                userId = user.id,
                filter = filter(args, zone),
                kind = kind,
                order = order,
                limit = McpArgs.int(args, "limit", 20, 1, ListenHistoryQueryService.MAX_TOP_LIMIT),
                offset = McpArgs.int(args, "offset", 0, 0, Int.MAX_VALUE),
                zone = zone,
            ),
        )
    }

    private fun Server.addGetListenTimeline(user: User) = addTool(
        name = "get_listen_timeline",
        description = "Bucket the user's listens over time (per hour, day, week, month or year) to show listening activity " +
            "as a series. Buckets are aligned to local boundaries in the given timezone and empty buckets are included.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("bucket", stringSchema("Bucket size of the series.", listOf("hour", "day", "week", "month", "year")))
                put("from", stringSchema(FROM_DESC))
                put("to", stringSchema(TO_DESC))
                put("songId", stringSchema(SONG_ID_DESC))
                put("artistId", stringSchema(ARTIST_ID_DESC))
                put("albumId", stringSchema(ALBUM_ID_DESC))
                put("timezone", stringSchema(TIMEZONE_DESC))
            },
            required = listOf("bucket"),
        ),
        toolAnnotations = ReadOnlyTool,
    ) { request ->
        val args = request.params.arguments
        val zone = McpArgs.zone(args)
        val bucket = McpArgs.requiredEnum(
            args,
            "bucket",
            McpBucket.entries.toTypedArray(),
            listOf("hour", "day", "week", "month", "year"),
        )
        toolResult(query.timeline(userId = user.id, filter = filter(args, zone), bucket = bucket, zone = zone))
    }

    private fun Server.addGetListeningStats(user: User) = addTool(
        name = "get_listening_stats",
        description = "The user's full listening statistics for a named range, the same report the Synara clients show: " +
            "totals, comparison against the previous range, top songs, artists and albums, listen clock, streaks and new discoveries.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "range",
                    stringSchema(
                        "Which range to report on. DAY, WEEK, MONTH and YEAR cover the current period so far; " +
                            "LAST_WEEK, LAST_MONTH and LAST_YEAR cover the previous complete one.",
                        StatsRange.entries.map { it.name },
                    ),
                )
                put("timezone", stringSchema(TIMEZONE_DESC))
                put("topLimit", integerSchema("How many entries each top list contains.", minimum = 1, maximum = 100, default = 10))
                put(
                    "topOrder",
                    stringSchema("How the top lists are ranked.", TopOrder.entries.map { it.name }),
                )
            },
            required = listOf("range"),
        ),
        toolAnnotations = ReadOnlyTool,
    ) { request ->
        val args = request.params.arguments
        val range = McpArgs.requiredEnum(args, "range", StatsRange.entries.toTypedArray())
        val topOrder = McpArgs.enum(args, "topOrder", TopOrder.entries.toTypedArray(), TopOrder.LISTEN_COUNT)
            ?: TopOrder.LISTEN_COUNT
        val result = stats.stats(
            userId = user.id,
            range = range,
            timezone = McpArgs.zone(args).id,
            topLimit = McpArgs.int(args, "topLimit", 10, 1, 100),
            topOrder = topOrder,
        )
        CallToolResult(content = listOf(TextContent(text = StatsJson.encodeToString(result))))
    }

    private fun Server.addGetNowPlaying(user: User) = addTool(
        name = "get_now_playing",
        description = "The song the user is currently playing, with the playback position, or null when nothing is playing.",
        inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
        toolAnnotations = ReadOnlyTool,
    ) { request ->
        val zone = McpArgs.zone(request.params.arguments)
        val nowPlaying = query.nowPlaying(user.id, zone)
        val text = nowPlaying?.let { McpToolJson.encodeToString(it) } ?: "null"
        CallToolResult(content = listOf(TextContent(text = text)))
    }

    private fun filter(args: JsonObject?, zone: ZoneId): ListenFilter = ListenFilter(
        from = McpArgs.time(args, "from", zone),
        to = McpArgs.time(args, "to", zone),
        songId = McpArgs.uuid(args, "songId"),
        artistId = McpArgs.uuid(args, "artistId"),
        albumId = McpArgs.uuid(args, "albumId"),
        source = McpArgs.enum(args, "source", ListenSource.entries.toTypedArray(), null, listOf("LOCAL", "LISTENBRAINZ")),
        qualifiedOnly = McpArgs.bool(args, "qualifiedOnly", false),
    )
}
