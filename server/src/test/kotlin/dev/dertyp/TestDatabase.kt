package dev.dertyp

import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import java.util.UUID

object TestDatabase {
    private var currentFile: File? = null

    fun connect(dialect: DbDialect, name: String): Database {
        return when (dialect) {
            DbDialect.POSTGRES -> Database.connect(
                "jdbc:h2:mem:${name}_${UUID.randomUUID().toString().replace("-", "")};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver"
            )
            DbDialect.SQLITE -> {
                currentFile = File.createTempFile(name, ".db")
                Database.connect("jdbc:sqlite:${currentFile!!.absolutePath}", "org.sqlite.JDBC")
            }
        }
    }

    fun cleanUp() {
        currentFile?.delete()
        currentFile = null
    }
}
