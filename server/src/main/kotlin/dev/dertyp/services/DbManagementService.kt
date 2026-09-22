package dev.dertyp.services

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import io.github.classgraph.ClassGraph
import kotlinx.serialization.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.*
import java.util.UUID

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
        ClassGraph()
            .enableClassInfo()
            .acceptPackages("dev.dertyp.db")
            .scan()
            .use { scanResult ->
                scanResult.getSubclasses(Table::class.java.name)
                    .loadClasses(Table::class.java)
                    .asSequence()
                    .mapNotNull {
                        try {
                            it.kotlin.objectInstance
                        } catch (_: Exception) {
                            null
                        }
                    }
                    .distinct()
                    .sortedBy { it.tableName }
                    .toList()
            }
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
                transaction {
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

    @OptIn(ExperimentalSerializationApi::class)
    private fun importV2(dis: DataInputStream) {
        val tableCount = dis.readInt()
        repeat(tableCount) {
            val tableName = dis.readUTF()
            val table = tables.find { it.tableName == tableName }
            if (table == null) {
                while (true) {
                    val size = dis.readInt()
                    if (size == ROW_TERMINATOR) break
                    dis.readFully(ByteArray(size))
                }
            } else {
                transaction {
                    table.deleteAll()
                    val chunk = mutableListOf<Map<String, DbValue>>()
                    while (true) {
                        val size = dis.readInt()
                        if (size == ROW_TERMINATOR) break
                        val bytes = ByteArray(size)
                        dis.readFully(bytes)
                        chunk.add(Cbor.decodeFromByteArray(rowSerializer, bytes))
                        if (chunk.size >= CHUNK_SIZE) {
                            insertChunk(table, chunk)
                            chunk.clear()
                        }
                    }
                    if (chunk.isNotEmpty()) insertChunk(table, chunk)
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun importV1(dis: DataInputStream, tableCount: Int) {
        for (i in 0 until tableCount) {
            val tableName = dis.readUTF()
            val dataSize = dis.readInt()
            val cborBytes = ByteArray(dataSize)
            dis.readFully(cborBytes)

            val table = tables.find { it.tableName == tableName }
            if (table != null) {
                val tableData = Cbor.decodeFromByteArray<TableData>(cborBytes)

                transaction {
                    table.deleteAll()
                    tableData.rows.forEach { rowMap ->
                        table.insert { iTable ->
                            table.columns.forEach { column ->
                                val dbValue = rowMap[column.name]
                                if (dbValue != null) {
                                    val value = convertFromDbValue(dbValue)
                                    @Suppress("UNCHECKED_CAST")
                                    iTable[column as Column<Any?>] = value?.let { column.columnType.valueFromDB(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
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
