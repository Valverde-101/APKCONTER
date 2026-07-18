package com.valcrono.virtualspace

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.valcrono.virtualstorage.VirtualFileSystem
import com.valcrono.virtualstorage.VirtualFsAccessContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object SQLiteIdentifierQuoter {
    fun quoteSqliteIdentifier(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""
}

object SQLiteQueryValidator {
    private val rejected = Regex("\\b(INSERT|UPDATE|DELETE|REPLACE|CREATE|DROP|ALTER|ATTACH|DETACH|VACUUM|REINDEX|ANALYZE)\\b", RegexOption.IGNORE_CASE)
    fun requireReadOnly(sql: String) {
        require(!rejected.containsMatchIn(sql)) { "SQLITE_QUERY_FAILED: write statement rejected" }
        require(!Regex("PRAGMA\\s+(writable_schema|journal_mode\\s*=)", RegexOption.IGNORE_CASE).containsMatchIn(sql)) { "SQLITE_QUERY_FAILED: unsafe pragma rejected" }
    }
}

data class SQLiteSnapshot(val id: String, val virtualPath: String, val baseFile: File, val directory: File, val sidecars: List<File>)
class SQLiteSnapshotHandle(val snapshot: SQLiteSnapshot, val database: SQLiteDatabase) : AutoCloseable {
    override fun close() { runCatching { database.close() }; snapshot.directory.deleteRecursively() }
}

class SQLiteSnapshotManager(private val context: Context, private val vfs: VirtualFileSystem) {
    private val admin = VirtualFsAccessContext.admin()
    suspend fun createSnapshot(virtualPath: String): SQLiteSnapshotHandle = withContext(Dispatchers.IO) {
        val node = vfs.resolve(virtualPath, admin)
        val source = node.physicalFile ?: error("SQLITE_SNAPSHOT_FAILED: no physical file")
        val dir = File(context.cacheDir, "sqlite-snapshot-${UUID.randomUUID()}").apply { mkdirs() }
        val base = File(dir, source.name)
        source.copyTo(base, overwrite = true)
        val sidecars = listOf("-wal", "-shm", "-journal").mapNotNull { suffix ->
            File(source.parentFile, source.name + suffix).takeIf { it.exists() }?.also { it.copyTo(File(dir, it.name), overwrite = true) }
        }
        val db = SQLiteDatabase.openDatabase(base.path, null, SQLiteDatabase.OPEN_READONLY)
        db.execSQL("PRAGMA query_only = ON")
        db.rawQuery("PRAGMA query_only", null).use { c -> check(c.moveToFirst() && c.getInt(0) == 1) { "SQLITE_OPEN_FAILED: query_only not enabled" } }
        SQLiteSnapshotHandle(SQLiteSnapshot(UUID.randomUUID().toString(), virtualPath, base, dir, sidecars), db)
    }
}

interface SQLiteRepositoryApi {
    suspend fun getTableRows(snapshotId: String, tableName: String, offset: Int, limit: Int): SQLitePageData
    suspend fun closeSnapshot(snapshotId: String)
}

class SQLiteViewerRepository(private val context: Context, private val vfs: VirtualFileSystem) : SQLiteRepositoryApi {
    companion object { private val snapshots = ConcurrentHashMap<String, SQLiteSnapshotHandle>() }
    private val snapshotManager = SQLiteSnapshotManager(context, vfs)
    private val q = SQLiteIdentifierQuoter

    suspend fun openDatabase(virtualPath: String): SQLiteDatabaseData = withContext(Dispatchers.IO) {
        val stat = vfs.stat(virtualPath, VirtualFsAccessContext.admin())
        val handle = snapshotManager.createSnapshot(virtualPath)
        snapshots[handle.snapshot.id] = handle
        handle.database.toDatabaseData(virtualPath, stat.size, handle.snapshot.id)
    }
    suspend fun getTableSchema(snapshotId: String, tableName: String): SQLiteTableSummary = withContext(Dispatchers.IO) { handle(snapshotId).database.tableSummary(tableName) }
    override suspend fun getTableRows(snapshotId: String, tableName: String, offset: Int, limit: Int): SQLitePageData = withContext(Dispatchers.IO) { handle(snapshotId).database.page(tableName, offset, limit.coerceIn(25, 500)) }
    suspend fun getRowDetail(snapshotId: String, tableName: String, rowIndex: Long): SQLiteRowData = withContext(Dispatchers.IO) { handle(snapshotId).database.page(tableName, rowIndex.toInt(), 1).rows.firstOrNull() ?: error("SQLITE_PAGE_OUT_OF_RANGE") }
    override suspend fun closeSnapshot(snapshotId: String) = withContext(Dispatchers.IO) { snapshots.remove(snapshotId)?.close() ?: Unit }

    private fun handle(id: String) = snapshots[id] ?: error("SQLITE_SNAPSHOT_EXPIRED")
    private fun SQLiteDatabase.toDatabaseData(path:String, size:Long, id:String): SQLiteDatabaseData {
        val userVersion = pragmaInt("user_version")
        val pageSize = pragmaInt("page_size")
        val journalMode = pragmaString("journal_mode")
        val tables = schemaRows("table").map { tableSummary(it.first, it.second) }
        val views = schemaRows("view").map { SQLiteViewSummary(it.first, it.second) }
        val indexes = indexRows()
        val triggers = schemaRows("trigger").map { it.first }
        return SQLiteDatabaseData(path, size, userVersion, pageSize, journalMode, tables, views, indexes, triggers, id)
    }
    private fun SQLiteDatabase.schemaRows(type:String): List<Pair<String,String?>> = rawQuery("SELECT name, sql FROM sqlite_master WHERE type=? ORDER BY name", arrayOf(type)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to if (c.isNull(1)) null else c.getString(1)) } }
    private fun SQLiteDatabase.indexRows(): List<SQLiteIndexSummary> = rawQuery("SELECT name, tbl_name, sql FROM sqlite_master WHERE type='index' ORDER BY name", null).use { c -> buildList { while (c.moveToNext()) add(SQLiteIndexSummary(c.getString(0), c.getString(1), false, if (c.isNull(2)) null else c.getString(2))) } }
    private fun SQLiteDatabase.tableSummary(table:String, sql:String? = rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }): SQLiteTableSummary = SQLiteTableSummary(table, sql, runCatching { countRowsBlocking(table) }.getOrNull(), columns(table), indexes(table))
    private fun SQLiteDatabase.columns(table:String): List<SQLiteColumnInfo> = rawQuery("PRAGMA table_xinfo(${q.quoteSqliteIdentifier(table)})", null).use { c -> buildList { while (c.moveToNext()) add(SQLiteColumnInfo(c.getInt("cid"), c.getString("name"), c.getString("type") ?: "", c.getInt("notnull") != 0, if (c.isNull(c.getColumnIndexOrThrow("dflt_value"))) null else c.getString("dflt_value"), c.getInt("pk"), c.getIntOrZero("hidden"))) } }
    private fun SQLiteDatabase.indexes(table:String): List<SQLiteIndexInfo> = rawQuery("PRAGMA index_list(${q.quoteSqliteIdentifier(table)})", null).use { c -> buildList { while (c.moveToNext()) { val name=c.getString("name"); add(SQLiteIndexInfo(name, c.getInt("unique") != 0, c.getStringOrNull("origin"), c.getIntOrZero("partial") != 0, indexColumns(name))) } } }
    private fun SQLiteDatabase.indexColumns(index:String): List<String> = rawQuery("PRAGMA index_info(${q.quoteSqliteIdentifier(index)})", null).use { c -> buildList { while (c.moveToNext()) add(c.getString("name")) } }
    private fun SQLiteDatabase.countRowsBlocking(table:String): Long = rawQuery("SELECT COUNT(*) FROM ${q.quoteSqliteIdentifier(table)}", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    private suspend fun SQLiteDatabase.countRows(table:String): Long = withTimeout(5_000) { countRowsBlocking(table) }
    private suspend fun SQLiteDatabase.page(table:String, offset:Int, limit:Int): SQLitePageData {
        require(offset >= 0) { "SQLITE_PAGE_OUT_OF_RANGE" }
        val total = countRows(table)
        val cols = columns(table).map { it.name }
        val rows = rawQuery("SELECT * FROM ${q.quoteSqliteIdentifier(table)} LIMIT $limit OFFSET $offset", null).use { c -> buildList { var n=offset.toLong(); while (c.moveToNext()) add(SQLiteRowData(n++, (0 until c.columnCount).map { c.cell(it) })) } }
        return SQLitePageData(table, cols, rows, offset, limit, total, offset > 0, offset + rows.size < total)
    }
    private fun SQLiteDatabase.pragmaInt(name:String) = rawQuery("PRAGMA $name", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    private fun SQLiteDatabase.pragmaString(name:String) = rawQuery("PRAGMA $name", null).use { if (it.moveToFirst()) it.getString(0) else null }
    private fun Cursor.cell(i:Int): SQLiteCellData = when (getType(i)) { Cursor.FIELD_TYPE_NULL -> SQLiteCellData.NullValue; Cursor.FIELD_TYPE_INTEGER -> SQLiteCellData.IntegerValue(getLong(i)); Cursor.FIELD_TYPE_FLOAT -> SQLiteCellData.RealValue(getDouble(i)); Cursor.FIELD_TYPE_BLOB -> { val b=getBlob(i) ?: ByteArray(0); SQLiteCellData.BlobValue(b.size, b.take(256).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }, b.size > 256) }; else -> { val s=getString(i) ?: ""; SQLiteCellData.TextValue(s.take(500), s.length > 500) } }
    private fun Cursor.getString(name:String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.getInt(name:String) = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.getIntOrZero(name:String) = getColumnIndex(name).takeIf { it >= 0 }?.let { getInt(it) } ?: 0
    private fun Cursor.getStringOrNull(name:String) = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }
}
