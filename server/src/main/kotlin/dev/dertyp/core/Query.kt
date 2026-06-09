package dev.dertyp.core

import dev.dertyp.db.*
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.Function
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.Query

val mbArtistSearchTable = MBArtistTable.alias("mbArtistSearch")
val mbArtistAliasSearchTable = MBArtistAliasTable.alias("mbArtistAliasSearch")

fun ColumnSet.withMBArtistSearch(): ColumnSet = this
    .leftJoin(mbArtistSearchTable, { ArtistMusicBrainzTable.musicBrainzId }, { mbArtistSearchTable[MBArtistTable.id] })
    .leftJoin(mbArtistAliasSearchTable, { mbArtistSearchTable[MBArtistTable.id] }, { mbArtistAliasSearchTable[MBArtistAliasTable.artistId] })

val mbArtistSearchColumns: List<Expression<out String?>> = listOf(
    mbArtistSearchTable[MBArtistTable.name],
    mbArtistAliasSearchTable[MBArtistAliasTable.name],
    mbArtistSearchTable[MBArtistTable.disambiguation]
)

val mbReleaseSearchTable = MBReleaseTable.alias("mbReleaseSearch")

fun ColumnSet.withMBReleaseSearch(): ColumnSet = this
    .leftJoin(mbReleaseSearchTable, { AlbumMusicBrainzTable.musicBrainzId }, { mbReleaseSearchTable[MBReleaseTable.id] })

val mbReleaseSearchColumns: List<Expression<out String?>> = listOf(
    mbReleaseSearchTable[MBReleaseTable.title],
    mbReleaseSearchTable[MBReleaseTable.disambiguation]
)

val mbRecordingSearchTable = MBRecordingTable.alias("mbRecordingSearch")

fun ColumnSet.withMBRecordingSearch(): ColumnSet = this
    .leftJoin(mbRecordingSearchTable, { SongMusicBrainzTable.musicBrainzId }, { mbRecordingSearchTable[MBRecordingTable.id] })

val mbRecordingSearchColumns: List<Expression<out String?>> = listOf(
    mbRecordingSearchTable[MBRecordingTable.title]
)

fun Query.paging(page: Int, pageSize: Int, offset: Int = 0) = apply {
    offset((pageSize * page).toLong())
    limit(pageSize + offset)
}

fun toTsVector(column: Expression<*>): Function<String> =
    CustomFunction("to_tsvector", VarCharColumnType(), stringLiteral("simple"), column)

fun toTsQuery(query: String): Function<String> =
    CustomFunction("to_tsquery", VarCharColumnType(), stringLiteral("simple"), stringParam(query))

class MatchOp(val expr1: Expression<*>, val expr2: Expression<*>) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        expr1.toQueryBuilder(queryBuilder)
        queryBuilder.append(" @@ ")
        expr2.toQueryBuilder(queryBuilder)
    }
}

infix fun Expression<*>.match(query: Expression<*>): Op<Boolean> = MatchOp(this, query)

fun tsRank(vector: Expression<*>, query: Expression<*>): Function<Float> =
    CustomFunction("ts_rank_cd", FloatColumnType(), vector, query)

class FloatMathOp(val operator: String, val expr1: Expression<*>, val expr2: Expression<*>) : ExpressionWithColumnType<Float>() {
    override val columnType = FloatColumnType()
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("(")
        expr1.toQueryBuilder(queryBuilder)
        queryBuilder.append(" $operator ")
        expr2.toQueryBuilder(queryBuilder)
        queryBuilder.append(")")
    }
}

fun Query.rankedSearchQuery(
    queryString: String,
    weights: List<Int>,
    columns: Collection<Expression<out String?>>,
    sortFallback: Column<*>? = null
): Query {
    val normalizedQuery = if (currentDialect is PostgreSQLDialect) {
        queryString.replace(Regex("[&|!()\\\\:*'\"]"), " ")
    } else {
        queryString
    }

    val tokens = normalizedQuery
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    if (tokens.isEmpty()) return this

    if (currentDialect is PostgreSQLDialect) {
        val positiveTokens = tokens.filter { !it.startsWith("-") }
        val negativeTokens = tokens.filter { it.startsWith("-") && it.length > 1 }

        var whereClause: Op<Boolean>? = null

        for (token in positiveTokens) {
            val tsQuery = toTsQuery("$token:*")

            val matchConditions = columns.map { col ->
                val coalesceCol = CustomFunction<String>("coalesce", VarCharColumnType(), col, stringLiteral(""))
                val vector = toTsVector(coalesceCol)
                MatchOp(vector, tsQuery) as Op<Boolean>
            }
            val tokenMatchOp = matchConditions.reduce { acc, op -> acc or op }
            whereClause = whereClause?.let { it and tokenMatchOp } ?: tokenMatchOp
        }

        for (token in negativeTokens) {
            val clean = token.substring(1)
            if (clean.isBlank()) continue
            val tsQuery = toTsQuery("$clean:*")

            val matchConditions = columns.map { col ->
                val coalesceCol = CustomFunction<String>("coalesce", VarCharColumnType(), col, stringLiteral(""))
                val vector = toTsVector(coalesceCol)
                MatchOp(vector, tsQuery) as Op<Boolean>
            }
            val tokenMatchOp = matchConditions.reduce { acc, op -> acc or op }
            whereClause = whereClause?.let { it and not(tokenMatchOp) } ?: not(tokenMatchOp)
        }

        if (whereClause != null) {
            where { whereClause }
        } else if (positiveTokens.isEmpty() && negativeTokens.isEmpty()) {
            return this
        }

        val exactScoreExpression = columns.mapIndexed { index, col ->
            val coalesceCol = CustomFunction<String>("coalesce", VarCharColumnType(), col, stringLiteral(""))
            
            val exactMatchOp = coalesceCol ilike queryString
            val phraseMatchOp = coalesceCol ilike "%$queryString%"
            val exactMatchBonus = case()
                .When(exactMatchOp, intLiteral(1000 * weights[index]))
                .When(phraseMatchOp, intLiteral(100 * weights[index]))
                .Else(intLiteral(0))

            var totalRankForCol: ExpressionWithColumnType<Float> = intLiteral(0).castTo(FloatColumnType())
            
            for (token in positiveTokens) {
                val tsQuery = toTsQuery("$token:*")
                val vector = toTsVector(coalesceCol)
                val rank = tsRank(vector, tsQuery)
                val rankWeighted = FloatMathOp("*", rank, intLiteral(weights[index]))
                totalRankForCol = FloatMathOp("+", totalRankForCol, rankWeighted)
            }
            
            FloatMathOp("+", totalRankForCol, exactMatchBonus)
        }.reduce { acc, weightedRank ->
            FloatMathOp("+", acc, weightedRank)
        }

        orderBy(exactScoreExpression, SortOrder.DESC)
        sortFallback?.let { orderBy(it, SortOrder.ASC) }

        return this
    }

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
        val exactMatchBonus = (weights.first() * tokens.size) + 500
        val phraseBonus = (weights.first() * tokens.size) + 100

        val exactMatchOp = columns.first() ilike queryString
        val phraseMatchOp = columns.first() ilike "%$queryString%"

        val bonusExpression = case()
            .When(exactMatchOp, intLiteral(exactMatchBonus))
            .When(phraseMatchOp, intLiteral(phraseBonus))
            .Else(zeroLiteral)

        scoreExpression += bonusExpression
        whereClause = whereClause?.let { it or exactMatchOp or phraseMatchOp } ?: (exactMatchOp or phraseMatchOp)

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
        val matches = columns.map { it.isNull() or (it notIlike "%${token.substring(1)}%") }

        val tokenMatchOp = matches.reduce { a, b -> a and b }
        whereClause = whereClause?.let { it and tokenMatchOp } ?: tokenMatchOp
    }

    where { whereClause!! }
    orderBy(scoreExpression.let {
        if (it is LiteralOp && it.value == 0) intLiteral(1)
        else it
    }, SortOrder.DESC)
    sortFallback?.let { orderBy(it, SortOrder.ASC) }

    return this
}

suspend inline fun Query.fetchBatchedResults(
    batchSize: Int,
    crossinline body: suspend (List<ResultRow>) -> Unit
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

suspend inline fun <T : Any> Query.fetchBatchedResultsByIdKeyset(
    idColumn: Column<T>,
    batchSize: Int,
    crossinline body: suspend (List<ResultRow>) -> Unit
) {
    var lastId: T? = null
    var hasMore = true

    while (hasMore) {
        val results = dbQuery {
            val query = this.copy()
            if (lastId != null) {
                query.adjustWhere {
                    val newOp = GreaterOp(idColumn, QueryParameter(lastId!!, idColumn.columnType))
                    if (this != null) this and newOp
                    else newOp
                }
            }
            query.orderBy(idColumn to SortOrder.ASC)
            query.limit(batchSize)
            query.toList()
        }

        if (results.isNotEmpty()) {
            body(results)
            lastId = results.last()[idColumn]
            if (results.size < batchSize) hasMore = false
        } else {
            hasMore = false
        }
    }
}
