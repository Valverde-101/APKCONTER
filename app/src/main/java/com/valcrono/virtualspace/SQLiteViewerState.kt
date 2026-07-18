package com.valcrono.virtualspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

sealed interface SQLiteScreen {
    data class Overview(val snapshotId: String) : SQLiteScreen
    data class Table(val snapshotId: String, val tableName: String, val selectedTab: SQLiteTableTab = SQLiteTableTab.DATA) : SQLiteScreen
    data class RowDetail(val snapshotId: String, val tableName: String, val rowNumber: Long, val row: SQLiteRowData) : SQLiteScreen
}

enum class SQLiteTableTab { STRUCTURE, DATA, INDEXES }

data class SQLiteViewerError(val code: String, val message: String)

data class SQLiteViewerState(
    val database: SQLiteDatabaseData? = null,
    val screen: SQLiteScreen? = null,
    val selectedTable: SQLiteTableSummary? = null,
    val page: SQLitePageData? = null,
    val pageSize: Int = 100,
    val loading: Boolean = false,
    val exporting: Boolean = false,
    val error: SQLiteViewerError? = null,
    val message: String? = null,
    val exportRequest: SQLiteExportRequest? = null,
    val exportProgress: String? = null,
)

sealed interface SQLiteAction {
    data class OpenStructure(val tableName: String) : SQLiteAction
    data class OpenData(val tableName: String) : SQLiteAction
    data class OpenIndexes(val tableName: String) : SQLiteAction
    data class OpenRow(val row: SQLiteRowData) : SQLiteAction
    data object PreviousPage : SQLiteAction
    data object NextPage : SQLiteAction
    data class ChangePageSize(val size: Int) : SQLiteAction
    data class ExportSchema(val tableName: String) : SQLiteAction
    data class ExportPageCsv(val tableName: String) : SQLiteAction
    data class ExportTableCsv(val tableName: String) : SQLiteAction
    data object Back : SQLiteAction
    data object DismissError : SQLiteAction
    data object ClearMessage : SQLiteAction
    data object ExportHandled : SQLiteAction
}

sealed interface SQLiteExportRequest { val tableName: String; val mimeType: String; val fileName: String
    data class Schema(override val tableName: String, override val fileName: String) : SQLiteExportRequest { override val mimeType = "application/json" }
    data class PageCsv(override val tableName: String, override val fileName: String) : SQLiteExportRequest { override val mimeType = "text/csv" }
    data class TableCsv(override val tableName: String, override val fileName: String) : SQLiteExportRequest { override val mimeType = "text/csv" }
}

class SQLiteViewerViewModel(private val repository: SQLiteRepositoryApi, database: SQLiteDatabaseData, private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) {
    private val initialSnapshotId = database.snapshotId
    private var disposed = false
    private val _state = MutableStateFlow(SQLiteViewerState(database = database, screen = SQLiteScreen.Overview(database.snapshotId)))
    val state: StateFlow<SQLiteViewerState> = _state.asStateFlow()

    fun onAction(action: SQLiteAction) { when (action) {
        is SQLiteAction.OpenStructure -> openTable(action.tableName, SQLiteTableTab.STRUCTURE, false)
        is SQLiteAction.OpenData -> openTable(action.tableName, SQLiteTableTab.DATA, true)
        is SQLiteAction.OpenIndexes -> openTable(action.tableName, SQLiteTableTab.INDEXES, false)
        is SQLiteAction.OpenRow -> _state.value = _state.value.copy(screen = SQLiteScreen.RowDetail(snapshotId(), currentTableName(), action.row.rowNumber, action.row))
        SQLiteAction.PreviousPage -> loadPage(((_state.value.page?.offset ?: 0) - _state.value.pageSize).coerceAtLeast(0))
        SQLiteAction.NextPage -> loadPage((_state.value.page?.offset ?: 0) + _state.value.pageSize)
        is SQLiteAction.ChangePageSize -> { _state.value = _state.value.copy(pageSize = action.size.coerceIn(25, 500)); loadPage(0) }
        is SQLiteAction.ExportSchema -> requestExport(SQLiteExportRequest.Schema(action.tableName, suggestedName(action.tableName, "schema.json")))
        is SQLiteAction.ExportPageCsv -> requestExport(SQLiteExportRequest.PageCsv(action.tableName, suggestedName(action.tableName, "page-${pageNumber()}.csv")))
        is SQLiteAction.ExportTableCsv -> requestExport(SQLiteExportRequest.TableCsv(action.tableName, suggestedName(action.tableName, "table.csv")))
        SQLiteAction.Back -> back()
        SQLiteAction.DismissError -> _state.value = _state.value.copy(error = null)
        SQLiteAction.ClearMessage -> _state.value = _state.value.copy(message = null)
        SQLiteAction.ExportHandled -> _state.value = _state.value.copy(exportRequest = null)
    } }

    fun writeExport(request: SQLiteExportRequest, output: OutputStream) { scope.launch { try { _state.value = _state.value.copy(exporting = true, exportProgress = null)
        withContext(Dispatchers.IO) { when (request) { is SQLiteExportRequest.Schema -> output.write(schemaJson(request.tableName).toByteArray(Charsets.UTF_8)); is SQLiteExportRequest.PageCsv -> output.write(pageCsv(request.tableName).toByteArray(Charsets.UTF_8)); is SQLiteExportRequest.TableCsv -> exportTableCsv(request.tableName, output) } }
        _state.value = _state.value.copy(exporting = false, message = "Exportación completada", exportProgress = null)
    } catch (t: Throwable) { _state.value = _state.value.copy(exporting = false, error = SQLiteViewerError("EXPORT_FAILED", t.message ?: "No se pudo exportar"), message = "No se pudo exportar") } finally { runCatching { output.close() } } } }

    private fun openTable(tableName: String, tab: SQLiteTableTab, loadRows: Boolean) { val table = findTable(tableName); _state.value = _state.value.copy(screen = SQLiteScreen.Table(snapshotId(), tableName, tab), selectedTable = table, page = if (loadRows) null else _state.value.page?.takeIf { it.tableName == tableName }, loading = loadRows); if (loadRows) loadPage(0) }
    private fun loadPage(offset: Int) { val tableName = currentTableName(); scope.launch { _state.value = _state.value.copy(loading = true, error = null); runCatching { repository.getTableRows(snapshotId(), tableName, offset, _state.value.pageSize) }.onSuccess { _state.value = _state.value.copy(page = it, loading = false, message = "Página cargada") }.onFailure { _state.value = _state.value.copy(loading = false, error = SQLiteViewerError("SQLITE_QUERY_FAILED", it.message ?: "No se pudo cargar la página")) } } }
    private fun requestExport(r: SQLiteExportRequest) { _state.value = _state.value.copy(exportRequest = r) }
    private fun back() { val s = _state.value.screen; _state.value = when (s) { is SQLiteScreen.RowDetail -> _state.value.copy(screen = SQLiteScreen.Table(s.snapshotId, s.tableName, SQLiteTableTab.DATA)); is SQLiteScreen.Table -> _state.value.copy(screen = SQLiteScreen.Overview(s.snapshotId), page = null, selectedTable = null); else -> _state.value } }
    private fun findTable(name: String) = _state.value.database?.tables?.firstOrNull { it.name == name }
    private fun snapshotId() = _state.value.database?.snapshotId.orEmpty()
    private fun currentTableName() = when (val s = _state.value.screen) { is SQLiteScreen.Table -> s.tableName; is SQLiteScreen.RowDetail -> s.tableName; else -> _state.value.selectedTable?.name.orEmpty() }
    private fun pageNumber() = ((_state.value.page?.offset ?: 0) / _state.value.pageSize) + 1
    private fun suggestedName(table: String, suffix: String) = "${_state.value.database?.virtualPath?.substringAfterLast('/')?.substringBeforeLast('.') ?: "database"}-$table-$suffix"
    fun closeSnapshotWhenViewerEnds(snapshotId: String) { scope.launch { repository.closeSnapshot(snapshotId) } }
    fun dispose() {
        if (disposed) return
        disposed = true
        val snapshot = initialSnapshotId.ifBlank { snapshotId() }
        if (snapshot.isNotBlank()) scope.launch { runCatching { repository.closeSnapshot(snapshot) }; scope.cancel() } else scope.cancel()
    }

    fun schemaJson(tableName: String): String {
        val db = _state.value.database ?: error("No database")
        val t = findTable(tableName) ?: error("Table not found")
        return buildString {
            appendLine("{")
            appendLine("  \"database\": ${jsonString(db.virtualPath)},")
            appendLine("  \"table\": ${jsonString(t.name)},")
            appendLine("  \"createSql\": ${t.createSql?.let(::jsonString) ?: "null"},")
            appendLine("  \"columns\": [")
            t.columns.forEachIndexed { index, c ->
                appendLine("    {")
                appendLine("      \"cid\": ${c.cid},")
                appendLine("      \"name\": ${jsonString(c.name)},")
                appendLine("      \"type\": ${jsonString(c.declaredType)},")
                appendLine("      \"notNull\": ${c.notNull},")
                appendLine("      \"primaryKeyPosition\": ${c.primaryKeyPosition},")
                appendLine("      \"defaultValue\": ${c.defaultValue?.let(::jsonString) ?: "null"}")
                append("    }")
                appendLine(if (index == t.columns.lastIndex) "" else ",")
            }
            appendLine("  ],")
            appendLine("  \"indexes\": [")
            t.indexes.forEachIndexed { index, idx ->
                appendLine("    {")
                appendLine("      \"name\": ${jsonString(idx.name)},")
                appendLine("      \"unique\": ${idx.unique},")
                appendLine("      \"columns\": [${idx.columns.joinToString(", ") { jsonString(it) }}]")
                append("    }")
                appendLine(if (index == t.indexes.lastIndex) "" else ",")
            }
            appendLine("  ]")
            append("}")
        }
    }
    fun pageCsv(tableName: String): String { val p = _state.value.page?.takeIf { it.tableName == tableName } ?: error("No visible page"); return buildString { appendLine(p.columns.joinToString(",") { csv(it) }); p.rows.forEach { row -> appendLine(row.cells.joinToString(",") { csvCell(it) }) } } }
    private suspend fun exportTableCsv(tableName: String, out: OutputStream) { var offset = 0; val limit = 500; var wroteHeader = false; do { val p = repository.getTableRows(snapshotId(), tableName, offset, limit); if (!wroteHeader) { out.write((p.columns.joinToString(",") { csv(it) } + "\n").toByteArray()); wroteHeader = true }; p.rows.forEach { out.write((it.cells.joinToString(",") { c -> csvCell(c) } + "\n").toByteArray()) }; offset += p.rows.size; _state.value = _state.value.copy(exportProgress = "Exportando $offset de ${p.totalRows} filas") } while (p.hasNext && p.rows.isNotEmpty()) }
}

fun csvCell(cell: SQLiteCellData): String = when (cell) { SQLiteCellData.NullValue -> "NULL"; is SQLiteCellData.IntegerValue -> cell.value.toString(); is SQLiteCellData.RealValue -> cell.value.toString(); is SQLiteCellData.TextValue -> csv(cell.value); is SQLiteCellData.BlobValue -> csv("[BLOB ${cell.size} bytes]") }
fun csv(value: String): String = if (value.isEmpty() || value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value

private fun jsonString(value: String): String = buildString { append('"'); value.forEach { ch -> when (ch) { '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch) } }; append('"') }
