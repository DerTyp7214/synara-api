package dev.dertyp.db

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.date.getDateFromISO
import dev.dertyp.core.date.getISOFromDate
import dev.dertyp.core.date.isoDateKey
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.random.Random

class SongDuplicateKeyTest {
    private object ProbeTable : Table("duplicate_key_probe") {
        val position = integer("position")
        val title = text("title")
        val date = text("date").nullable()
        override val primaryKey = PrimaryKey(position)
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    private val whitespace = (Char.MIN_VALUE..Char.MAX_VALUE).filter { it.isWhitespace() }.map { it.toString() }

    private fun titles(): List<String> {
        val marker = EXPLICIT_TITLE_MARKER
        val fixed = listOf(
            "", "A", marker, marker + marker, "A$marker$marker", " $marker", "A $marker $marker", "$marker A",
            "A​", "​A", "A\u0085", "A﻿", "A%$marker", "A_$marker", "A\\$marker", "😀$marker",
            "A$marker$marker$marker", "$marker $marker", " ", "  A  ",
        )
        val perWhitespace = whitespace.flatMap { space ->
            listOf(
                "A$space", "${space}A", "A$space$marker", "A$marker$space", space, space + space,
                "A$space$marker$space$marker", "$marker$space", "${space}A$space$marker$space",
            )
        }
        val random = Random(4)
        val alphabet = listOf("a", "B", " ", "\t", "\n", marker, " ", "　", "", "é", "😀", "%", "_", "-")
        val generated = List(3000) { List(random.nextInt(0, 9)) { alphabet.random(random) }.joinToString("") }
        return fixed + perWhitespace + generated
    }

    private fun dates(): List<String?> {
        val fixed = listOf(
            null, "", " ", "    ", "2020", "0000", "9999", "abcd", "٢٠٢٠", "202", "20200",
            "2020-01-01", "2020-1-01", "2020-01-1", "2020-13-01", "2020-00-10", "2020/01/01", "+2020-01-01",
            "20200-01-01", "2020-01-01T00:00", " 2020", "2020 ", "2020-05", "0000-02-29", "2020-01-01 ",
            "2020-0a-01", "2020-01-0a", "20a0-01-01", "2020x01-01", "2020-01x01", "𝟘𝟘-01-01",
        )
        val years = listOf("0000", "0004", "0100", "1600", "1900", "2000", "2023", "2024", "2100", "2400", "9996")
        val calendar = years.flatMap { year ->
            (0..13).flatMap { month ->
                (0..32).map { day ->
                    "$year-${month.toString().padStart(2, '0')}-${
                        day.toString().padStart(2, '0')
                    }"
                }
            }
        }
        val random = Random(9)
        val alphabet = "0123456789-9 a".toList()
        val generated = List(3000) { List(random.nextInt(0, 12)) { alphabet.random(random) }.joinToString("") }
        return fixed + calendar + generated
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the sql duplicate keys match the kotlin title and release date normalization`(dialect: DbDialect) {
        val database = TestDatabase.connect(dialect, "duplicate_key", ProbeTable)
        val titles = titles()
        val dates = dates()
        val rows = (0 until maxOf(titles.size, dates.size)).map { index ->
            Triple(index, titles[index % titles.size], dates[index % dates.size])
        }
        val titleKey = duplicateSongTitleKey(ProbeTable.title)
        val dateKey = isoDateKey(ProbeTable.date)

        val actual = transaction(database) {
            ProbeTable.batchInsert(rows) { (position, title, date) ->
                this[ProbeTable.position] = position
                this[ProbeTable.title] = title
                this[ProbeTable.date] = date
            }
            ProbeTable.select(ProbeTable.position, titleKey, dateKey)
                .orderBy(ProbeTable.position)
                .map { it[ProbeTable.position] to (it[titleKey] to it.getOrNull(dateKey)) }
        }

        val expected = rows.map { (position, title, date) ->
            position to (duplicateSongTitle(displaySongTitle(title)) to getISOFromDate(getDateFromISO(date)))
        }
        val titleMismatches = expected.zip(actual)
            .filter { (want, got) -> want.second.first != got.second.first }
            .map { (want, got) -> "${rows[want.first].second.map { it.code }} expected=${want.second.first.map { it.code }} actual=${got.second.first.map { it.code }}" }
        val dateMismatches = expected.zip(actual)
            .filter { (want, got) -> want.second.second != got.second.second }
            .map { (want, got) -> "'${rows[want.first].third}' expected=${want.second.second} actual=${got.second.second}" }

        assertEquals(emptyList<String>(), titleMismatches.take(20))
        assertEquals(emptyList<String>(), dateMismatches.take(20))
        assertEquals(rows.size, actual.size)
    }
}
