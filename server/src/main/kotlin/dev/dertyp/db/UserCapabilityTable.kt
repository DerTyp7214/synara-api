package dev.dertyp.db

import dev.dertyp.data.UserCapability
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object UserCapabilityTable : Table("user_capability") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val capability = enumerationByName("capability", 50, UserCapability::class)

    override val primaryKey = PrimaryKey(userId, capability)
}
