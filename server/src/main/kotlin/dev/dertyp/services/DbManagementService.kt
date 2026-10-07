package dev.dertyp.services

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import dev.dertyp.core.db.SchemaTables
import dev.dertyp.core.db.dbQuery
import dev.dertyp.db.BackupSchemaCheck
import dev.dertyp.db.BackupSchemaInfo
import dev.dertyp.db.CustomMigrationTable
import dev.dertyp.db.SearchIndexQueueTable
import dev.dertyp.db.TsVectorColumnType
import kotlinx.serialization.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.io.*
import java.util.UUID
import kotlin.sequences.Sequence

@Serializable
sealed class DbValue {
    @Serializable
    @SerialName("null")
    object DbNull : DbValue()
    @Serializable
    @SerialName("int")
    data class DbInt(val value: Int) : DbValue()
    @Serializable
    @SerialName("long")
    data class DbLong(val value: Long) : DbValue()
    @Serializable
    @SerialName("float")
    data class DbFloat(val value: Float) : DbValue()
    @Serializable
    @SerialName("double")
    data class DbDouble(val value: Double) : DbValue()
    @Serializable
    @SerialName("bool")
    data class DbBoolean(val value: Boolean) : DbValue()
    @Serializable
    @SerialName("str")
    data class DbString(val value: String) : DbValue()
    @Serializable
    @SerialName("uuid")
    data class DbUuid(val value: String) : DbValue()
    @Serializable
    @SerialName("bytes")
    data class DbBytes(val value: ByteArray) : DbValue() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DbBytes

            return value.contentEquals(other.value)
        }

        override fun hashCode(): Int {
            return value.contentHashCode()
        }
    }
}

@Serializable
data class TableData(
    val tableName: String,
    val rows: List<Map<String, DbValue>>
)

class DbManagementService(
    private val entityChangeRecorder: EntityChangeRecorder,
    private val databaseManager: DatabaseManager
) : IDbManagementService {
    private val rowSerializer = MapSerializer(String.serializer(), DbValue.serializer())

    private val tables: List<Table> by lazy {
        val discovered = SchemaTables.all - SearchIndexQueueTable
        val known = discovered.toSet()
        SchemaUtils.sortTablesByReferences(discovered).filter { it in known }
    }

    private val columns: Map<Table, List<Column<*>>> by lazy {
        tables.associateWith { table -> table.columns.filterNot { it.columnType is TsVectorColumnType } }
    }

    private val parents: Map<Table, Set<Table>> by lazy {
        val known = tables.toSet()
        tables.associateWith { table ->
            table.foreignKeys.map { it.targetTable }.filter { it != table && it in known }.toSet()
        }
    }

    private val children: Map<Table, List<Table>> by lazy {
        tables.associateWith { table -> tables.filter { table in parents.getValue(it) } }
    }

    override suspend fun exportData(): ByteArray {
        val baos = ByteArrayOutputStream()
        exportData(baos)
        return baos.toByteArray()
    }

    override suspend fun importData(data: ByteArray) = importData(ByteArrayInputStream(data))

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportData(output: OutputStream) {
        val schemaVersion = databaseManager.schemaVersion()
        ZstdOutputStream(ShieldedOutputStream(output)).use { zstd ->
            DataOutputStream(zstd).use { dos ->
                dbQuery {
                    dos.writeInt(FORMAT_V2_MARKER)
                    dos.writeInt(tables.size + 1)
                    dos.writeUTF(SCHEMA_VERSION_SECTION)
                    val versionRow = Cbor.encodeToByteArray(
                        rowSerializer,
                        mapOf("version" to DbValue.DbString(schemaVersion))
                    )
                    dos.writeInt(versionRow.size)
                    dos.write(versionRow)
                    dos.writeInt(ROW_TERMINATOR)
                    tables.forEach { table ->
                        dos.writeUTF(table.tableName)
                        val exported = columns.getValue(table)
                        table.select(exported).fetchSize(FETCH_SIZE).forEach { row ->
                            val map = mutableMapOf<String, DbValue>()
                            exported.forEach { column ->
                                map[column.name] = convertToDbValue(row[column])
                            }
                            val bytes = Cbor.encodeToByteArray(rowSerializer, map)
                            dos.writeInt(bytes.size)
                            dos.write(bytes)
                        }
                        dos.writeInt(ROW_TERMINATOR)
                    }
                }
            }
        }
    }

    suspend fun importData(input: InputStream) {
        val spool = File.createTempFile("synara-restore", ".dump")
        try {
            spool.outputStream().use { input.copyTo(it) }
            BackupSchemaCheck.refusal(readSchemaInfo(spool), databaseManager.schemaVersion())?.let { throw it }
            dbQuery {
                TransactionManager.current().maxAttempts = 1
                val restore = Restore()
                ZstdInputStream(BufferedInputStream(FileInputStream(spool))).use { zstd ->
                    DataInputStream(zstd).use { dis ->
                        val header = dis.readInt()
                        if (header < 0) {
                            importV2(dis, restore)
                        } else {
                            importV1(dis, header, restore)
                        }
                    }
                }
                entityChangeRecorder.restartTracking()
            }
        } finally {
            spool.delete()
        }
    }

    private fun readSchemaInfo(file: File): BackupSchemaInfo =
        ZstdInputStream(BufferedInputStream(FileInputStream(file))).use { zstd ->
            DataInputStream(zstd).use { dis ->
                val header = dis.readInt()
                if (header < 0) scanV2(dis) else scanV1(dis, header)
            }
        }

    private fun scanV2(dis: DataInputStream): BackupSchemaInfo {
        var version: String? = null
        val customMigrations = mutableSetOf<String>()
        repeat(dis.readInt()) {
            when (dis.readUTF()) {
                SCHEMA_VERSION_SECTION -> version = readRows(dis).toList()
                    .firstNotNullOfOrNull { (it["version"] as? DbValue.DbString)?.value }

                CustomMigrationTable.tableName ->
                    customMigrations += readRows(dis).mapNotNull { (it["id"] as? DbValue.DbString)?.value }

                else -> skipRows(dis)
            }
        }
        return BackupSchemaInfo(version, customMigrations)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun scanV1(dis: DataInputStream, tableCount: Int): BackupSchemaInfo {
        val customMigrations = mutableSetOf<String>()
        repeat(tableCount) {
            val tableName = dis.readUTF()
            val cborBytes = ByteArray(dis.readInt())
            dis.readFully(cborBytes)
            if (tableName == CustomMigrationTable.tableName) {
                customMigrations += Cbor.decodeFromByteArray<TableData>(cborBytes).rows
                    .mapNotNull { (it["id"] as? DbValue.DbString)?.value }
            }
        }
        return BackupSchemaInfo(null, customMigrations)
    }

    private inner class Restore {
        private val cleared = mutableSetOf<Table>()
        private val restored = mutableSetOf<Table>()

        fun isReady(table: Table) = parents.getValue(table).all { it in restored }

        fun restore(table: Table, rows: Sequence<Map<String, DbValue>>) {
            clear(table)
            val ordered = if (table.foreignKeys.any { it.targetTable == table }) {
                orderSelfReferences(table, rows.toList()).asSequence()
            } else {
                rows
            }
            ordered.chunked(CHUNK_SIZE).forEach { insertChunk(table, it) }
            restored += table
        }

        private fun clear(table: Table) {
            if (!cleared.add(table)) return
            children.getValue(table).forEach { clear(it) }
            table.deleteAll()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun readRows(dis: DataInputStream): Sequence<Map<String, DbValue>> = sequence {
        while (true) {
            val size = dis.readInt()
            if (size == ROW_TERMINATOR) break
            val bytes = ByteArray(size)
            dis.readFully(bytes)
            yield(Cbor.decodeFromByteArray(rowSerializer, bytes))
        }
    }

    private fun skipRows(dis: DataInputStream) {
        while (true) {
            val size = dis.readInt()
            if (size == ROW_TERMINATOR) break
            dis.readFully(ByteArray(size))
        }
    }

    private fun spoolRows(dis: DataInputStream): File {
        val file = File.createTempFile("synara-restore", ".rows")
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            while (true) {
                val size = dis.readInt()
                out.writeInt(size)
                if (size == ROW_TERMINATOR) break
                val bytes = ByteArray(size)
                dis.readFully(bytes)
                out.write(bytes)
            }
        }
        return file
    }

    private fun importV2(dis: DataInputStream, restore: Restore) {
        val deferred = mutableMapOf<Table, File>()
        try {
            val tableCount = dis.readInt()
            repeat(tableCount) {
                val tableName = dis.readUTF()
                val table = tables.find { it.tableName == tableName }
                when {
                    table == null -> skipRows(dis)
                    restore.isReady(table) -> restore.restore(table, readRows(dis))
                    else -> deferred.put(table, spoolRows(dis))?.delete()
                }
            }
            tables.filter { it in deferred }.forEach { table ->
                DataInputStream(BufferedInputStream(FileInputStream(deferred.getValue(table)))).use { spooled ->
                    restore.restore(table, readRows(spooled))
                }
            }
        } finally {
            deferred.values.forEach { it.delete() }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun importV1(dis: DataInputStream, tableCount: Int, restore: Restore) {
        val deferred = mutableMapOf<Table, ByteArray>()
        fun restoreTable(table: Table, cborBytes: ByteArray) {
            restore.restore(table, Cbor.decodeFromByteArray<TableData>(cborBytes).rows.asSequence())
        }
        for (i in 0 until tableCount) {
            val tableName = dis.readUTF()
            val dataSize = dis.readInt()
            val cborBytes = ByteArray(dataSize)
            dis.readFully(cborBytes)

            val table = tables.find { it.tableName == tableName } ?: continue
            if (restore.isReady(table)) restoreTable(table, cborBytes) else deferred[table] = cborBytes
        }
        tables.filter { it in deferred }.forEach { restoreTable(it, deferred.getValue(it)) }
    }

    private fun orderSelfReferences(table: Table, rows: List<Map<String, DbValue>>): List<Map<String, DbValue>> {
        val keys = table.foreignKeys.filter { it.targetTable == table }.map { key ->
            key.references.map { (from, target) -> from.name to target.name }
        }
        val byTarget = keys.map { pairs ->
            rows.indices.groupBy { index -> pairs.map { rows[index][it.second] } }
        }
        val visited = BooleanArray(rows.size)
        val ordered = ArrayList<Map<String, DbValue>>(rows.size)
        fun visit(index: Int) {
            if (visited[index]) return
            visited[index] = true
            keys.forEachIndexed { keyIndex, pairs ->
                val refs = pairs.map { rows[index][it.first] }
                if (refs.none { it == null || it == DbValue.DbNull }) {
                    byTarget[keyIndex][refs]?.forEach { visit(it) }
                }
            }
            ordered += rows[index]
        }
        rows.indices.forEach { visit(it) }
        return ordered
    }

    private fun insertChunk(table: Table, rows: List<Map<String, DbValue>>) {
        val restored = columns.getValue(table)
        table.batchInsert(rows) { rowMap ->
            restored.forEach { column ->
                val dbValue = rowMap[column.name]
                if (dbValue != null) {
                    val value = convertFromDbValue(dbValue)
                    @Suppress("UNCHECKED_CAST")
                    this[column as Column<Any?>] = value?.let { column.columnType.valueFromDB(it) }
                }
            }
        }
        TransactionManager.current().closeExecutedStatements()
    }

    private class ShieldedOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

        override fun close() = flush()
    }

    private class ShieldedInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() {}
    }

    private fun convertToDbValue(value: Any?): DbValue {
        return when (value) {
            null -> DbValue.DbNull
            is EntityID<*> -> convertToDbValue(value.value)
            is Int -> DbValue.DbInt(value)
            is Long -> DbValue.DbLong(value)
            is Float -> DbValue.DbFloat(value)
            is Double -> DbValue.DbDouble(value)
            is Boolean -> DbValue.DbBoolean(value)
            is String -> DbValue.DbString(value)
            is UUID -> DbValue.DbUuid(value.toString())
            is ByteArray -> DbValue.DbBytes(value)
            else -> DbValue.DbString(value.toString())
        }
    }

    private fun convertFromDbValue(dbValue: DbValue): Any? {
        return when (dbValue) {
            is DbValue.DbNull -> null
            is DbValue.DbInt -> dbValue.value
            is DbValue.DbLong -> dbValue.value
            is DbValue.DbFloat -> dbValue.value
            is DbValue.DbDouble -> dbValue.value
            is DbValue.DbBoolean -> dbValue.value
            is DbValue.DbString -> dbValue.value
            is DbValue.DbUuid -> UUID.fromString(dbValue.value)
            is DbValue.DbBytes -> dbValue.value
        }
    }

    companion object {
        const val FORMAT_V2_MARKER = -2
        const val SCHEMA_VERSION_SECTION = "schema.version"
        private const val ROW_TERMINATOR = -1
        private const val FETCH_SIZE = 1000
        private const val CHUNK_SIZE = 500
    }
}
