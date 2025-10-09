package dev.dertyp.core

import org.jetbrains.exposed.sql.Query

fun Query.paging(page: Int, pageSize: Int, offset: Int = 0) = apply {
    offset((pageSize * page).toLong())
    limit(pageSize + offset)
}