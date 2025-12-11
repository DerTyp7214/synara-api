@file:Suppress("UnusedImport")

package dev.dertyp.core

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.case
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import kotlin.collections.Collection
import kotlin.collections.List
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.drop
import kotlin.collections.filter
import kotlin.collections.first
import kotlin.collections.forEachIndexed
import kotlin.collections.map
import kotlin.collections.reduce
import kotlin.collections.windowed

fun Query.paging(page: Int, pageSize: Int, offset: Int = 0) = apply {
    offset((pageSize * page).toLong())
    limit(pageSize + offset)
}

fun Query.rankedSearchQuery(
    queryString: String,
    weights: List<Int>,
    columns: Collection<Column<String>>,
): Query {
    val tokens = queryString
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    if (tokens.isEmpty()) return this

    val zeroLiteral = intLiteral(0)

    var scoreExpression: ExpressionWithColumnType<Int> = intLiteral(0)
    var whereClause: Op<Boolean>? = null

    for (token in tokens.filter { !it.startsWith("-") }) {
        val matches = columns.map { Op.build { it ilike "%${token}%" } }

        val tokenMatchOp = matches.reduce { a, b -> a or b }
        whereClause = whereClause?.let { it or tokenMatchOp } ?: tokenMatchOp

        val tokenScore = case(null).let {
            var case = it.When(matches.first(), intLiteral(weights.first()))
            matches.drop(1).forEachIndexed { index, op ->
                case = case.When(op, intLiteral(weights[index + 1]))
            }
            case
        }.Else(zeroLiteral)

        scoreExpression += tokenScore
    }

    if (!queryString.startsWith("-")) {
        val phraseBonus = (weights.first() * tokens.size) + 100

        val phraseMatchOp = Op.build { columns.first() ilike "%$queryString%" }

        val bonusExpression = case(null)
            .When(phraseMatchOp, intLiteral(phraseBonus))
            .Else(zeroLiteral)

        scoreExpression += bonusExpression
        whereClause = whereClause?.let { it or phraseMatchOp } ?: phraseMatchOp

        if (tokens.size > 1) {
            val bigrams = tokens.windowed(2, 1)

            for ((token1, token2) in bigrams) {
                columns.forEachIndexed { index, column ->
                    val consecutiveBonus = weights[index] * 2
                    val consecutiveMatchOp = Op.build { column ilike "%$token1% $token2%" }

                    scoreExpression += case(null)
                        .When(consecutiveMatchOp, intLiteral(consecutiveBonus))
                        .Else(zeroLiteral)
                }
            }
        }
    }

    for (token in tokens.filter { it.startsWith("-") }) {
        val matches = columns.map { Op.build { it notIlike "%${token.substring(1)}%" } }

        val tokenMatchOp = matches.reduce { a, b -> a and b }
        whereClause = whereClause?.let { it and tokenMatchOp } ?: tokenMatchOp
    }

    where { whereClause!! }
    orderBy(scoreExpression.let {
        if (it is LiteralOp && it.value == 0) intLiteral(1)
        else it
    }, SortOrder.DESC)

    return this
}