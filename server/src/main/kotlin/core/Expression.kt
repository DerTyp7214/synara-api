package dev.dertyp.core

import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.LikePattern
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.lowerCase

infix fun <T : String?> Expression<T>.ilike(pattern: String) = lowerCase().like(LikePattern(pattern.lowercase()))