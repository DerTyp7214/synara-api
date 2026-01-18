@file:Suppress("UnusedImport")

package dev.dertyp.core

import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Query

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
        val matches = columns.map { (it ilike "%${token}%") as Op<Boolean> }

        val tokenMatchOp = matches.reduce { a, b -> a or b }
        whereClause = whereClause?.let { it or tokenMatchOp } ?: tokenMatchOp

        val tokenScore = case().let {
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

        val phraseMatchOp = columns.first() ilike "%$queryString%"

        val bonusExpression = case()
            .When(phraseMatchOp, intLiteral(phraseBonus))
            .Else(zeroLiteral)

        scoreExpression += bonusExpression
        whereClause = whereClause?.let { it or phraseMatchOp } ?: phraseMatchOp

        if (tokens.size > 1) {
            val bigrams = tokens.windowed(2, 1)

            for ((token1, token2) in bigrams) {
                columns.forEachIndexed { index, column ->
                    val consecutiveBonus = weights[index] * 2
                    val consecutiveMatchOp = column ilike "%$token1% $token2%"

                    scoreExpression += case()
                        .When(consecutiveMatchOp, intLiteral(consecutiveBonus))
                        .Else(zeroLiteral)
                }
            }
        }
    }

    for (token in tokens.filter { it.startsWith("-") && it.length > 1 }) {
        val matches = columns.map { (it notIlike "%${token.substring(1)}%") as Op<Boolean> }

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

suspend inline fun Query.fetchBatchedResults(
    batchSize: Int,
    body: (List<ResultRow>) -> Unit
) {
    var offset = 0L
    var hasMore = true

    while (hasMore) {
        val batchQuery = this.copy().apply {
            limit(batchSize)
            offset(offset)
        }

        val results = dbQuery {
            batchQuery.toList()
        }

        if (results.isNotEmpty()) {
            body(results)
            offset += batchSize

            if (results.size < batchSize) {
                hasMore = false
            }
        } else {
            hasMore = false
        }
    }
}