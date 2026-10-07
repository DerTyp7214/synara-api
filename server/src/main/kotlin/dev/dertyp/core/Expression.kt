package dev.dertyp.core

import org.jetbrains.exposed.v1.core.*

infix fun <T : String?> Expression<T>.ilike(pattern: String) = lowerCase().like(LikePattern(pattern.lowercase()))
infix fun <T : String?> Expression<T>.notIlike(pattern: String) = lowerCase().notLike(LikePattern(pattern.lowercase()))
