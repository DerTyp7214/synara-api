package dev.dertyp.core

import org.jetbrains.exposed.v1.core.*

infix fun <T : String?> Expression<T>.ilike(pattern: String) = lowerCase().like(LikePattern(pattern.lowercase()))
infix fun <T : String?> Expression<T>.notIlike(pattern: String) = lowerCase().notLike(LikePattern(pattern.lowercase()))

infix fun <T : String?> Expression<T>.likeAny(patterns: Iterable<String>): Op<Boolean> {
    val patternList = patterns.toList()
    if (patternList.isEmpty()) return Op.FALSE

    return patternList
        .map { (this like it) as Op<Boolean> }
        .reduce { acc, op -> acc or op }
}

infix fun <T : String?> Expression<T>.ilikeAny(patterns: Iterable<String>): Op<Boolean> {
    val patternList = patterns.toList()
    if (patternList.isEmpty()) return Op.FALSE

    return patternList
        .map { (this ilike it) as Op<Boolean> }
        .reduce { acc, op -> acc or op }
}