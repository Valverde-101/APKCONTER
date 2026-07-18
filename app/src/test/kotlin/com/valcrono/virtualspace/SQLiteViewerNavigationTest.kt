package com.valcrono.virtualspace

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class SQLiteViewerNavigationTest {
    private val table = SQLiteTableSummary("events", "CREATE TABLE events(id INTEGER PRIMARY KEY, msg TEXT)", 3, listOf(SQLiteColumnInfo(0,"id","INTEGER",false,null,1), SQLiteColumnInfo(1,"msg","TEXT",false,null,0)), emptyList())
    private val db = SQLiteDatabaseData("/data/data/com.valcrono.testapp.a/databases/a.db", 1, 0, 4096, "delete", listOf(table), emptyList(), emptyList(), snapshotId = "snap")

    @Test fun SQLiteStructureButtonChangesScreenTest() {
        val vm = SQLiteViewerViewModel(FakeSQLiteRepo(), db)
        vm.onAction(SQLiteAction.OpenStructure("events"))
        assertEquals(SQLiteScreen.Table("snap", "events", SQLiteTableTab.STRUCTURE), vm.state.value.screen)
    }

    @Test fun SQLiteDataButtonChangesScreenTest() {
        val repo = FakeSQLiteRepo(); val vm = SQLiteViewerViewModel(repo, db)
        vm.onAction(SQLiteAction.OpenData("events"))
        assertEquals(SQLiteScreen.Table("snap", "events", SQLiteTableTab.DATA), vm.state.value.screen)
        assertEquals(0, repo.offsets.single())
    }

    @Test fun SQLitePreviousPageLoadsCorrectOffsetTest() { val repo = FakeSQLiteRepo(); val vm = SQLiteViewerViewModel(repo, db); vm.onAction(SQLiteAction.OpenData("events")); vm.onAction(SQLiteAction.NextPage); vm.onAction(SQLiteAction.PreviousPage); assertEquals(listOf(0,100,0), repo.offsets) }
    @Test fun SQLiteNextPageLoadsCorrectOffsetTest() { val repo = FakeSQLiteRepo(); val vm = SQLiteViewerViewModel(repo, db); vm.onAction(SQLiteAction.OpenData("events")); vm.onAction(SQLiteAction.NextPage); assertEquals(100, repo.offsets.last()) }
    @Test fun SQLitePageSizeChangeReloadsTest() { val repo = FakeSQLiteRepo(); val vm = SQLiteViewerViewModel(repo, db); vm.onAction(SQLiteAction.OpenData("events")); vm.onAction(SQLiteAction.ChangePageSize(25)); assertEquals(25, vm.state.value.pageSize); assertEquals(25, repo.limits.last()); assertEquals(0, repo.offsets.last()) }
    @Test fun SQLiteRowClickOpensDetailTest() { val vm = SQLiteViewerViewModel(FakeSQLiteRepo(), db); vm.onAction(SQLiteAction.OpenData("events")); val row = vm.state.value.page!!.rows.first(); vm.onAction(SQLiteAction.OpenRow(row)); assertTrue(vm.state.value.screen is SQLiteScreen.RowDetail) }
    @Test fun SQLiteSnapshotSurvivesNavigationTest() { val repo = FakeSQLiteRepo(); val vm = SQLiteViewerViewModel(repo, db); vm.onAction(SQLiteAction.OpenStructure("events")); vm.onAction(SQLiteAction.OpenData("events")); assertEquals(0, repo.closed.size) }
    @Test fun SQLiteSnapshotClosesOnExitTest() { val repo = FakeSQLiteRepo(); SQLiteViewerViewModel(repo, db).closeSnapshotWhenViewerEnds("snap"); assertEquals(listOf("snap"), repo.closed) }
    @Test fun SQLiteOnlySelectedTableLoadsRowsTest() { val repo = FakeSQLiteRepo(); SQLiteViewerViewModel(repo, db).onAction(SQLiteAction.OpenStructure("events")); assertTrue(repo.offsets.isEmpty()) }
    @Test fun SQLiteSchemaJsonEncodingTest() { val json = SQLiteViewerViewModel(FakeSQLiteRepo(), db).schemaJson("events"); assertTrue(json.contains("\"database\"")); assertTrue(json.contains("\"primaryKeyPosition\": 1")) }
    @Test fun SQLiteCsvEscapingTest() { assertEquals("\"a,b\"", csv("a,b")); assertEquals("\"a\"\"b\"", csv("a\"b")); assertEquals("NULL", csvCell(SQLiteCellData.NullValue)); assertEquals("\"\"", csvCell(SQLiteCellData.TextValue("", false))) }
    @Test fun SQLiteExportSchemaButtonInvokesExporterTest() { val vm = SQLiteViewerViewModel(FakeSQLiteRepo(), db); vm.onAction(SQLiteAction.ExportSchema("events")); assertTrue(vm.state.value.exportRequest is SQLiteExportRequest.Schema) }
    @Test fun SQLiteFullTableStreamingExportTest() { val repo = FakeSQLiteRepo(); val vm = SQLiteViewerViewModel(repo, db); vm.onAction(SQLiteAction.OpenData("events")); vm.writeExport(SQLiteExportRequest.TableCsv("events", "x.csv"), ByteArrayOutputStream()); Thread.sleep(200); assertTrue(repo.limits.contains(500)) }
    @Test fun SQLiteExportCancellationTest() { val vm = SQLiteViewerViewModel(FakeSQLiteRepo(), db); vm.onAction(SQLiteAction.ExportTableCsv("events")); vm.onAction(SQLiteAction.ExportHandled); assertNull(vm.state.value.exportRequest) }
    @Test fun SQLiteViewerLocalSnackbarTest() { val vm = SQLiteViewerViewModel(FakeSQLiteRepo(), db); vm.onAction(SQLiteAction.OpenData("events")); assertEquals("Página cargada", vm.state.value.message) }
}

private class FakeSQLiteRepo : SQLiteRepositoryApi {
    val offsets = mutableListOf<Int>(); val limits = mutableListOf<Int>(); val closed = mutableListOf<String>()
    override suspend fun getTableRows(snapshotId: String, tableName: String, offset: Int, limit: Int): SQLitePageData { offsets += offset; limits += limit; val rows = if (offset >= 300) emptyList() else listOf(SQLiteRowData(offset.toLong(), listOf(SQLiteCellData.IntegerValue((offset+1).toLong()), SQLiteCellData.TextValue("counter=10", false)))); return SQLitePageData(tableName, listOf("id","msg"), rows, offset, limit, 301, offset > 0, offset + rows.size < 301) }
    override suspend fun closeSnapshot(snapshotId: String) { closed += snapshotId }
}
