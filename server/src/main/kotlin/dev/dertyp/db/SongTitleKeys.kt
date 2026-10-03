package dev.dertyp.db

import org.jetbrains.exposed.v1.core.*

const val EXPLICIT_TITLE_MARKER = "🅴"

private val titleWhitespace = (Char.MIN_VALUE..Char.MAX_VALUE).filter { it.isWhitespace() }.joinToString("")

fun displaySongTitle(title: String): String = title.removeSuffix(EXPLICIT_TITLE_MARKER).trimEnd()

fun duplicateSongTitle(displayTitle: String): String = displayTitle.removeSuffix(EXPLICIT_TITLE_MARKER).trim()

fun duplicateSongTitleKey(title: Expression<String>): Expression<String> =
    case()
        .When(
            title like "%$EXPLICIT_TITLE_MARKER%",
            title.withoutExplicitMarker().trimWhitespaceEnd().withoutExplicitMarker().trimWhitespaceEnd()
                .trimWhitespaceStart()
        )
        .Else(title.trimWhitespaceEnd().trimWhitespaceStart())

private fun Expression<String>.withoutExplicitMarker(): Expression<String> =
    case()
        .When(
            this like "%$EXPLICIT_TITLE_MARKER",
            Substring(
                this,
                intLiteral(1),
                MinusOp(CustomFunction("length", IntegerColumnType(), this), intLiteral(1), IntegerColumnType())
            )
        )
        .Else(this)

private fun Expression<String>.trimWhitespaceEnd(): Expression<String> =
    CustomFunction("rtrim", TextColumnType(), this, stringParam(titleWhitespace))

private fun Expression<String>.trimWhitespaceStart(): Expression<String> =
    CustomFunction("ltrim", TextColumnType(), this, stringParam(titleWhitespace))
