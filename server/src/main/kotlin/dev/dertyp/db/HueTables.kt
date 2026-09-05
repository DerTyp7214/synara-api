package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object HueBridgeTable : UUIDTable("hue_bridge") {
    val bridgeId = varchar("bridgeId", 32).uniqueIndex()
    val ip = varchar("ip", 64)
    val name = varchar("name", 255)
    val modelId = varchar("modelId", 32).nullable()
    val applicationKey = text("applicationKey")
    val clientKey = text("clientKey").nullable()
    val certFingerprint = varchar("certFingerprint", 95).nullable()
    val createdBy = reference("createdBy", UserTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = long("createdAt")
    val lastSeen = long("lastSeen").nullable()
    val lastError = text("lastError").nullable()
}

object HueUserLinkTable : Table("hue_user_link") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val bridgeId = reference("bridgeId", HueBridgeTable.id, onDelete = ReferenceOption.CASCADE)
    val enabled = bool("enabled").default(false)
    val targets = text("targets").default("[]")
    val intensity = varchar("intensity", 16).default("MEDIUM")
    val transitionMode = varchar("transitionMode", 16).default("FIXED")
    val transitionMs = integer("transitionMs").default(400)
    val onStop = varchar("onStop", 16).default("KEEP")
    val updatedAt = long("updatedAt")

    override val primaryKey = PrimaryKey(userId, bridgeId)
}
