package dev.dertyp.services

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import io.github.classgraph.ClassGraph
import kotlinx.serialization.*
import kotlinx.serialization.cbor.Cbor
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
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

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun exportData(): ByteArray {
        val baos = ByteArrayOutputStream()
        ZstdOutputStream(baos).use { zstd ->
            DataOutputStream(zstd).use { dos ->
                transaction {
                    dos.writeInt(tables.size)
                    tables.forEach { table ->
                        val rows = table.selectAll().map { row ->
                            val map = mutableMapOf<String, DbValue>()
                            table.columns.forEach { column ->
                                val value = row[column]
                                map[column.name] = convertToDbValue(value)
                            }
                            map
                        }
                        val tableData = TableData(table.tableName, rows)
                        val cborBytes = Cbor.encodeToByteArray(tableData)
                        
                        dos.writeUTF(table.tableName)
                        dos.writeInt(cborBytes.size)
                        dos.write(cborBytes)
                    }
                }
            }
        }
        return baos.toByteArray()
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun importData(data: ByteArray) {
        ZstdInputStream(ByteArrayInputStream(data)).use { zstd ->
            DataInputStream(zstd).use { dis ->
                val tableCount = dis.readInt()
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
        }
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
}
