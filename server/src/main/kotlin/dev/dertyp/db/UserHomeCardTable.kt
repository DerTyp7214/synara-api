package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object UserHomeCardTable : Table("userHomeCard") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val contributionId = varchar("contributionId", 255)
    val pinned = bool("pinned").default(true)
    val position = integer("position").default(0)

    override val primaryKey = PrimaryKey(userId, contributionId)
}
