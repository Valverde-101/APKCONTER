package com.valcrono.virtualspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SQLiteInfiniteMeasurementStaticTest {
    private val source = File("src/main/java/com/valcrono/virtualspace/MainActivity.kt").readText()

    @Test fun nestedScaffoldIsNotUsedInsideViewerLazyItemTest() {
        val viewer = source.substringAfter("private fun ViewerContent").substringBefore("private fun RenderViewerData")
        assertTrue(viewer.contains("Scaffold(topBar"))
        assertFalse(viewer.contains("LazyColumn"))
        assertFalse(viewer.contains("item { RenderViewerData"))
        val sqliteRoot = source.substringAfter("private fun SQLiteDatabaseOverview").substringBefore("private fun SQLiteViewerNavigationContent")
        assertFalse(sqliteRoot.contains("Scaffold("))
    }

    @Test fun sqliteOverviewUsesLazyItemsTest() {
        val overview = source.substringAfter("private fun SQLiteOverviewCompact").substringBefore("private fun SQLiteTableOverviewCard")
        assertTrue(overview.contains("LazyColumn"))
        assertTrue(overview.contains("items(items = data.tables, key = { it.name })"))
        assertFalse(overview.contains("data.tables.forEach"))
    }

    @Test fun sqliteTableScreenUsesBoundedContentTest() {
        val tableScreen = source.substringAfter("private fun SQLiteTableScreen").substringBefore("private fun SQLiteStructureContent")
        assertTrue(tableScreen.contains("Column(modifier.fillMaxSize()"))
        assertTrue(tableScreen.contains("Box(Modifier.weight(1f).fillMaxWidth())"))
        assertTrue(tableScreen.contains("SQLiteTableData(state, viewModel, table, Modifier.fillMaxSize())"))
    }

    @Test fun sqliteDataHorizontalScrollIsBoundedTest() {
        val tableData = source.substringAfter("private fun SQLiteTableData").substringBefore("private fun SQLiteHeaderRow")
        assertTrue(tableData.contains("Box(Modifier.weight(1f).fillMaxWidth())"))
        assertTrue(tableData.contains("LazyColumn(Modifier.fillMaxSize().horizontalScroll"))
        assertTrue(tableData.contains("items(items = p.rows, key = { row -> \"${'$'}{p.tableName}:${'$'}{row.rowNumber}\" })"))
    }

    @Test fun sqliteSnapshotClosesWhenLeavingViewerTest() {
        assertTrue(source.contains("DisposableEffect(data.snapshotId) { onDispose { viewModel.dispose() } }"))
        val vm = File("src/main/java/com/valcrono/virtualspace/SQLiteViewerState.kt").readText()
        assertTrue(vm.contains("fun dispose()"))
        assertTrue(vm.contains("if (disposed) return"))
    }

    @Test fun viewerDiagnosticDoesNotLeakToHomeTest() {
        assertFalse(source.contains("importStatus = crash"))
        assertTrue(source.contains("localViewerDiagnostic = crash"))
    }
}
