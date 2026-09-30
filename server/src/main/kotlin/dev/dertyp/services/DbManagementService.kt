package dev.dertyp.services

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import dev.dertyp.core.db.SchemaTables
import dev.dertyp.core.db.dbQuery
import kotlinx.serialization.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import java.io.*
import java.util.UUID
import kotlin.sequences.Sequence

@Serializable
sealed class DbValue {
    @Serializable @SerialName("null") object DbNull : DbValue()
    @Serializable @SerialName("int") data class DbInt(val value: Int) : DbValue()
    @Serializable @SerialName("long") data class DbLong(val value: Long) : DbValue()
    @Serializable @SerialName("float") data class DbFloat(val value: Float) : DbValue()
    @Serializable @SerialName("double") data class DbDouble(val value: Double) : DbValue()
    @Serializable @SerialName("bool") data class DbBoolean(val value: Boolean) : DbValue()
    @Serializable @SerialName("str") data class DbString(val value: String) : DbValue()
    @Serializable @SerialName("uuid") data class DbUuid(val value: String) : DbValue()
    @Serializable @SerialName("bytes") data class DbBytes(val value: ByteArray) : DbValue() {
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

class DbManagementService : IDbManagementService {
    private val rowSerializer = MapSerializer(String.serializer(), DbValue.serializer())

    private val tables: List<Table> by lazy {
        val discovered = SchemaTables.all
        val known = discovered.toSet()
        SchemaUtils.sortTablesByReferences(discovered).filter { it in known }
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
        ZstdOutputStream(ShieldedOutputStream(output)).use { zstd ->
            DataOutputStream(zstd).use { dos ->
                dbQuery {
                    dos.writeInt(FORMAT_V2_MARKER)
                    dos.writeInt(tables.size)
                    tables.forEach { table ->
                        dos.writeUTF(table.tableName)
                        table.selectAll().fetchSize(FETCH_SIZE).forEach { row ->
                            val map = mutableMapOf<String, DbValue>()
                            table.columns.forEach { column ->
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
        ZstdInputStream(ShieldedInputStream(input)).use { zstd ->
            DataInputStream(zstd).use { dis ->
                val header = dis.readInt()
                if (header < 0) {
                    importV2(dis)
                } else {
                    importV1(dis, header)
                }
            }
        }
    }

    private inner class Restore {
        private val cleared = mutableSetOf<Table>()
        private val restored = mutableSetOf<Table>()

        fun isReady(table: Table) = parents.getValue(table).all { it in restored }

        suspend fun restore(table: Table, rows: Sequence<Map<String, DbValue>>) {
            clear(table)
            dbQuery {
                val ordered = if (table.foreignKeys.any { it.targetTable == table }) {
                    orderSelfReferences(table, rows.toList()).asSequence()
                } else {
                    rows
                }
                ordered.chunked(CHUNK_SIZE).forEach { insertChunk(table, it) }
            }
            restored += table
        }

        private suspend fun clear(table: Table) {
            if (!cleared.add(table)) return
            children.getValue(table).forEach { clear(it) }
            dbQuery { table.deleteAll() }
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

    private suspend fun importV2(dis: DataInputStream) {
        val restore = Restore()
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
    private suspend fun importV1(dis: DataInputStream, tableCount: Int) {
        val restore = Restore()
        val deferred = mutableMapOf<Table, ByteArray>()
        suspend fun restoreTable(table: Table, cborBytes: ByteArray) {
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
        table.batchInsert(rows) { rowMap ->
            table.columns.forEach { column ->
                val dbValue = rowMap[column.name]
                if (dbValue != null) {
                    val value = convertFromDbValue(dbValue)
                    @Suppress("UNCHECKED_CAST")
                    this[column as Column<Any?>] = value?.let { column.columnType.valueFromDB(it) }
                }
            }
        }
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
        private const val ROW_TERMINATOR = -1
        private const val FETCH_SIZE = 1000
        private const val CHUNK_SIZE = 500
    }
}
