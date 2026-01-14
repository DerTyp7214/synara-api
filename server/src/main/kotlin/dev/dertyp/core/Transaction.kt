package dev.dertyp.core

import java.sql.Connection

fun foreignKeyOn(connection: Connection) {
    when (connection.metaData.driverName) {
        "org.sqlite.JDBC" -> {
            val statement = connection.prepareStatement("PRAGMA foreign_keys = ON")
            statement.execute()
        }
    }
}