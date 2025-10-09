package dev.dertyp.data

import kotlinx.serialization.Serializable

@Serializable
data class PaginatedResponse<T>(
    val data: List<T>,
    val page: Int = 0,
    val pageSize: Int = 150,
    val hasNextPage: Boolean = false,
)
