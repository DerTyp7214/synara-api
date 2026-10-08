package dev.dertyp.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class EntityChangeCoverageTest {
    private data class Site(val file: String, val function: String, val target: String, val statement: String) {
        override fun toString() = "$file | $function | $target | $statement"
    }

    private data class Occurrence(val site: Site, val line: Int)

    private data class Classified(val site: Site, val count: Int, val verdict: String)

    private class Function(val name: String, val braces: Int, val parens: Int) {
        var signatureOpened = false
        var signatureClosed = false
        var expression = false
        var block = false
        var bodySeen = false
    }

    private val trackedTables = listOf(
        "SongTable",
        "AlbumTable",
        "ArtistTable",
        "SongArtistTable",
        "AlbumArtistTable",
        "ArtistAliasTable",
        "ArtistSplitAliasTable",
        "ArtistMemberTable",
        "AlbumVersionGroupTable",
        "SongGenreTable",
        "AlbumGenreTable",
        "ArtistGenreTable",
        "SongMusicBrainzTable",
        "AlbumMusicBrainzTable",
        "ArtistMusicBrainzTable",
        "SongProviderTable",
        "AlbumProviderTable",
        "ArtistProviderTable",
        "SongVariantTable",
        "SongTitleTagTable",
        "AlbumTitleTagTable",
        "ImageTable",
        "AnimatedImageTable",
        "SyncedLyricsTable",
        "SongAudioDataTable",
        "SongAudioTimelineTable",
        "SongComposerTable",
        "SongLyricistTable",
        "SongProducerTable",
        "GenreTable",
        "PersonTable",
        "UserPlaylistTable",
        "UserPlaylistSongTable",
        "PlaylistTable",
        "PlaylistSongTable",
        "CollectionTable",
        "CollectionSongTable",
        "CollectionAlbumTable",
        "CollectionArtistTable",
        "CollectionPlaylistTable",
        "UserSongTable",
        "UserAlbumTable",
        "FollowedArtistTable",
        "TimecodeTagTable",
        "UserTable",
    )

    private val writeStatements = listOf(
        "insert",
        "insertIgnore",
        "insertAndGetId",
        "insertIgnoreAndGetId",
        "insertReturning",
        "batchInsert",
        "upsert",
        "upsertReturning",
        "batchUpsert",
        "batchReplace",
        "replace",
        "update",
        "updateReturning",
        "deleteWhere",
        "deleteIgnoreWhere",
        "deleteReturning",
        "deleteAll",
        "mergeFrom",
    )

    private val sourceRoots = listOf("server/src/main/kotlin", "plugin-api/src/main/kotlin")
    private val verdicts = Regex("(RECORDED|MIGRATION)(: \\S.*)?|BOOKKEEPING: \\S.*")
    private val statements = writeStatements.joinToString("|")
    private val functionStart = Regex("\\bfun\\s+(?:<[^>]*>\\s*)?(?:[\\w<>?, ]+\\.)?(`[^`]+`|\\w+)\\s*\\(")
    private val trackedWrite = Regex("\\b(${trackedTables.joinToString("|")})\\s*\\.\\s*($statements)\\b")
    private val indirectWrite = Regex(
        "\\b([a-z]\\w*(?:\\s*\\.\\s*[a-z]\\w*)*)\\s*\\.\\s*" +
            "((?:deleteWhere|deleteIgnoreWhere|deleteAll|batchInsert|batchUpsert|batchReplace|insertIgnore|" +
            "insertAndGetId|upsert|mergeFrom)\\b|update(?=\\s*\\(\\s*\\{)|insert(?=\\s*\\{))"
    )
    private val rawStatement = Regex("\\b(exec)\\s*\\(")

    private val projectRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `every write on a tracked table is classified`() {
        val found = scan { code -> trackedWrite.findAll(code).map { Triple(it.range.first, it.groupValues[1], it.groupValues[2]) } }
        compare(
            found,
            "entity-change/write-sites.txt",
            """
            A write on a table whose rows clients read through the entity change API is not classified, or a classified one is gone.
            For a new or moved write: decide whether it can change something a client can read (a field of a song, album, artist,
            playlist or collection, its members, a like, a follow, an album star or a timecode tag).
            If it can, announce it through EntityEventPublisher inside the transaction of the write and mark the line RECORDED. The change recorder subscribes to it.
            If it cannot, mark the line "BOOKKEEPING: <the columns it writes and why no client reads them>".
            A custom migration announces like a service. MIGRATION is only for a migration that ran before tracking began or that restarts tracking.
            Then put the line into server/src/test/resources/entity-change/write-sites.txt (sorted, one line per site and verdict).
            """.trimIndent()
        )
    }

    @Test
    fun `every write the scanner cannot attribute to a table is listed`() {
        val found = scan { code ->
            indirectWrite.findAll(code).map { Triple(it.range.first, it.groupValues[1].filterNot(Char::isWhitespace), it.groupValues[2]) } +
                rawStatement.findAll(code).map { Triple(it.range.first, "raw SQL", it.groupValues[1]) }
        }
        compare(
            found,
            "entity-change/indirect-write-sites.txt",
            """
            A write through a variable (table.deleteWhere, columns.table.update) or raw SQL (exec) is not listed, or a listed one is gone.
            The scanner cannot tell which table such a statement writes, so every one of them is listed by hand.
            Find out which tables it can reach. If one of them is a tracked table and the write can change something a client can read,
            announce it through EntityEventPublisher and mark the line RECORDED, otherwise "BOOKKEEPING: <reason>",
            or MIGRATION for a migration that ran before tracking began or that restarts tracking.
            Then put the line into server/src/test/resources/entity-change/indirect-write-sites.txt (sorted).
            """.trimIndent()
        )
    }

    @Test
    fun `the scanner attributes statements to the function around them`() {
        val code = blank(
            """
            class Sample {
                // SongTable.update in a comment
                val text = "SongTable.deleteAll ${'$'}{ "}" } {"
                fun block(a: () -> Unit = { }) {
                    fun local() = AlbumTable
                        .update({ x }) { }
                    val other = 1
                    SongTable.insert { }
                }
                suspend fun <T> Receiver<T>.expression(): Int = dbQuery {
                    SongTable.deleteWhere { }
                }.also { SongTable.update { } }
                fun signature(): Int
                val property = { SongTable.upsert { } }
            }
            """.trimIndent()
        )
        val found = trackedWrite.findAll(code).map { Triple(it.range.first, it.groupValues[1], it.groupValues[2]) }
        assertEquals(
            listOf(
                "local | AlbumTable | update",
                "block | SongTable | insert",
                "expression | SongTable | deleteWhere",
                "expression | SongTable | update",
                "<top> | SongTable | upsert",
            ),
            attribute("Sample.kt", code, found.toList()).map { "${it.site.function} | ${it.site.target} | ${it.site.statement}" }
        )
    }

    private fun scan(matches: (String) -> Sequence<Triple<Int, String, String>>): List<Occurrence> =
        sourceRoots.flatMap { root ->
            File(projectRoot, root).walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
        }.flatMap { file ->
            val code = blank(file.readText())
            val found = matches(code).sortedBy { it.first }.toList()
            if (found.isEmpty()) emptyList()
            else attribute(file.relativeTo(projectRoot).invariantSeparatorsPath, code, found)
        }

    private fun compare(found: List<Occurrence>, resource: String, advice: String) {
        val classified = readClassified(resource)
        val expected = classified.groupBy { it.site }.mapValues { (_, entries) -> entries.sumOf { it.count } }
        val actual = found.groupBy { it.site }

        val unclassified = actual.filterKeys { it !in expected }.map { (site, hits) ->
            "$site | ${hits.size} | <RECORDED, MIGRATION or BOOKKEEPING: reason>   (line ${hits.joinToString { it.line.toString() }})"
        }
        val gone = expected.keys.filter { it !in actual }.map { "$it" }
        val miscounted = actual.filter { (site, hits) -> expected[site]?.let { it != hits.size } == true }.map { (site, hits) ->
            "$site: ${hits.size} in the sources (line ${hits.joinToString { it.line.toString() }}), ${expected[site]} classified"
        }

        val problems = listOf(
            "Write sites in the sources that are not classified" to unclassified,
            "Classified write sites that no longer exist" to gone,
            "Write sites whose number of statements changed" to miscounted,
        ).filter { it.second.isNotEmpty() }
        assertTrue(problems.isEmpty()) {
            problems.joinToString("\n\n", postfix = "\n\n$advice") { (title, lines) ->
                "$title:\n" + lines.sorted().joinToString("\n") { "  $it" }
            }
        }

        val lines = classified.map { "${it.site} | ${it.count} | ${it.verdict}" }
        assertEquals(lines.sorted(), lines) { "$resource is not sorted" }
    }

    private fun readClassified(resource: String): List<Classified> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(resource)) { "$resource is missing" }
        return stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val parts = line.split(" | ", limit = 6)
            assertEquals(6, parts.size) { "$resource: '$line' is not 'file | function | table | statement | count | verdict'" }
            assertTrue(verdicts.matches(parts[5])) {
                "$resource: '$line' needs RECORDED, MIGRATION or 'BOOKKEEPING: <reason>' as its verdict"
            }
            Classified(Site(parts[0], parts[1], parts[2], parts[3]), parts[4].toInt(), parts[5])
        }
    }

    private fun attribute(file: String, code: String, found: List<Triple<Int, String, String>>): List<Occurrence> {
        val starts = functionStart.findAll(code).associate { it.range.first to it.groupValues[1] }
        val writes = found.groupBy { it.first }
        val open = ArrayDeque<Function>()
        val result = mutableListOf<Occurrence>()
        var braces = 0
        var parens = 0
        var line = 1

        for ((index, char) in code.withIndex()) {
            starts[index]?.let { name ->
                while (open.isNotEmpty() && open.last().braces >= braces && !open.last().block) open.removeLast()
                open.addLast(Function(name, braces, parens))
            }
            writes[index]?.forEach { (_, target, statement) ->
                result += Occurrence(Site(file, open.lastOrNull()?.name ?: "<top>", target, statement), line)
            }

            val current = open.lastOrNull()
            val declared = current?.takeIf { braces == it.braces && parens == it.parens }
            when (char) {
                '(' -> {
                    if (declared != null && !declared.signatureClosed) declared.signatureOpened = true
                    parens++
                }

                ')' -> {
                    parens--
                    if (current != null && current.signatureOpened && braces == current.braces && parens == current.parens) {
                        current.signatureClosed = true
                    }
                }

                '{' -> {
                    if (declared != null && declared.signatureClosed && !declared.expression) declared.block = true
                    braces++
                }

                '}' -> {
                    braces--
                    while (open.isNotEmpty() && open.last().braces > braces) open.removeLast()
                    val closed = open.lastOrNull()
                    if (closed != null && closed.block && closed.braces == braces && closed.parens == parens) open.removeLast()
                }

                '=' -> if (declared != null && declared.signatureClosed && !declared.block) declared.expression = true

                '\n' -> {
                    line++
                    if (declared != null && declared.signatureClosed && !declared.block) {
                        val ended = if (declared.expression) {
                            declared.bodySeen && !continues(code, index)
                        } else {
                            !startsBody(code, index)
                        }
                        if (ended) open.removeLast()
                    }
                }

                else -> if (current != null && current.expression && !char.isWhitespace()) current.bodySeen = true
            }
        }
        assertEquals(0, braces) { "$file: the scanner lost track of the braces, so its function names cannot be trusted" }
        return result
    }

    private fun rest(code: String, lineEnd: Int): String {
        var start = lineEnd
        while (start < code.length && code[start].isWhitespace()) start++
        return code.substring(start, minOf(code.length, start + 8))
    }

    private fun continues(code: String, lineEnd: Int): Boolean {
        var last = lineEnd - 1
        while (last >= 0 && code[last].isWhitespace()) last--
        val after = rest(code, lineEnd)
        return (last >= 0 && code[last] in "=+-*/&|,(<:?") ||
            listOf(".", "?.", "?:", "&&", "||", "+", "-", "*", "/", "else", "as ").any { after.startsWith(it) }
    }

    private fun startsBody(code: String, lineEnd: Int): Boolean =
        listOf("=", "{", ":", "where ").any { rest(code, lineEnd).startsWith(it) }

    private fun blank(source: String): String {
        val code = StringBuilder(source)
        fun erase(from: Int, until: Int) {
            for (position in from until minOf(until, code.length)) if (code[position] != '\n') code[position] = ' '
        }

        fun skipString(start: Int): Int {
            val raw = source.startsWith("\"\"\"", start)
            var position = start + if (raw) 3 else 1
            while (position < source.length) {
                when {
                    raw && source.startsWith("\"\"\"", position) -> {
                        position += 3
                        while (position < source.length && source[position] == '"') position++
                        return position
                    }

                    !raw && source[position] == '\\' -> position += 2
                    !raw && source[position] == '"' -> return position + 1
                    source.startsWith("\${", position) -> {
                        var depth = 1
                        position += 2
                        while (position < source.length && depth > 0) {
                            when (source[position]) {
                                '{' -> depth++
                                '}' -> depth--
                                '"' -> {
                                    position = skipString(position)
                                    continue
                                }
                            }
                            position++
                        }
                    }

                    else -> position++
                }
            }
            return position
        }

        var position = 0
        while (position < source.length) {
            val end = when {
                source.startsWith("//", position) -> source.indexOf('\n', position).let { if (it < 0) source.length else it }
                source.startsWith("/*", position) -> source.indexOf("*/", position + 2).let { if (it < 0) source.length else it + 2 }
                source[position] == '"' -> skipString(position)
                source[position] == '\'' -> {
                    val close = source.indexOf('\'', position + if (source.getOrNull(position + 1) == '\\') 3 else 2)
                    if (close in position + 2..position + 8) close + 1 else position
                }

                else -> position
            }
            if (end > position) {
                erase(position, end)
                position = end
            } else {
                position++
            }
        }
        return code.lines().joinToString("\n") { if (it.startsWith("import ")) " ".repeat(it.length) else it }
    }
}
