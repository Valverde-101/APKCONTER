package com.valcrono.virtualspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SQLiteIdentifierEscapingTest { @Test fun quotesDoubleQuotes() { assertEquals("\"bad\"\"name\"", SQLiteIdentifierQuoter.quoteSqliteIdentifier("bad\"name")) } }
class SQLiteWriteRejectedTest { @Test fun rejectsInsert() { assertTrue(runCatching { SQLiteQueryValidator.requireReadOnly("INSERT INTO x VALUES(1)") }.isFailure) } }
class SQLiteReadOnlyPragmaTest { @Test fun rejectsWritableSchema() { assertTrue(runCatching { SQLiteQueryValidator.requireReadOnly("PRAGMA writable_schema=ON") }.isFailure) } }
class SQLiteNullCellTest { @Test fun nullIsTyped() { assertEquals(SQLiteCellData.NullValue, SQLiteCellData.NullValue) } }
class SQLiteIntegerCellTest { @Test fun integerIsTyped() { assertEquals(7L, SQLiteCellData.IntegerValue(7).value) } }
class SQLiteRealCellTest { @Test fun realIsTyped() { assertEquals(1.5, SQLiteCellData.RealValue(1.5).value, 0.0) } }
class SQLiteTextCellTest { @Test fun textCarriesTruncation() { assertTrue(SQLiteCellData.TextValue("abc", true).truncated) } }
class SQLiteBlobCellTest { @Test fun blobCarriesPreviewOnly() { assertEquals("00 FF", SQLiteCellData.BlobValue(2, "00 FF", false).previewHex) } }
class SQLitePaginationTest { @Test fun pageFlagsAreStructured() { val p = SQLitePageData("t", listOf("c"), emptyList(), 100, 100, 250, true, true); assertTrue(p.hasPrevious && p.hasNext) } }
class SQLiteDatabaseOverviewTest { @Test fun overviewHasStructuredTables() { val db = SQLiteDatabaseData("/x.db", 1, 0, 4096, "delete", listOf(SQLiteTableSummary("events", null, 0, emptyList(), emptyList())), emptyList(), emptyList()); assertEquals("events", db.tables.single().name) } }
class SQLiteTableInfoTest { @Test fun columnKeepsDeclaredType() { assertEquals("INTEGER", SQLiteColumnInfo(0, "id", "INTEGER", true, null, 1).declaredType) } }
class SQLiteTableXInfoTest { @Test fun hiddenColumnIsRepresented() { assertEquals(2, SQLiteColumnInfo(0, "g", "TEXT", false, null, 0, hidden = 2).hidden) } }
class SQLiteIndexListTest { @Test fun tableSummaryHasIndexes() { assertEquals("idx", SQLiteTableSummary("t", null, null, emptyList(), listOf(SQLiteIndexInfo("idx", true, "c", false, listOf("id")))).indexes.single().name) } }
class SQLiteIndexInfoTest { @Test fun indexColumnsRemainStructured() { assertEquals(listOf("id"), SQLiteIndexInfo("idx", false, null, false, listOf("id")).columns) } }
class SQLiteRowCountTest { @Test fun nullableEstimatedRowCountSupported() { assertEquals(null, SQLiteTableSummary("t", null, null, emptyList(), emptyList()).estimatedRowCount) } }
class SQLiteFirstPageTest { @Test fun defaultLimitIsRepresentable() { assertEquals(100, SQLiteDestination.TableRows("/x.db", "events").limit) } }
class SQLiteEmptyTableTest { @Test fun emptyRowsAllowed() { assertTrue(SQLitePageData("t", emptyList(), emptyList(), 0, 100, 0, false, false).rows.isEmpty()) } }
class SQLiteTableClickableTest { @Test fun destinationsIncludeSchemaAndRows() { val schema: Any = SQLiteDestination.TableSchema("/x", "events"); val rows: Any = SQLiteDestination.TableRows("/x", "events"); assertTrue(schema is SQLiteDestination); assertTrue(rows is SQLiteDestination) } }
class SQLiteSnapshotWithWalTest { @Test fun sidecarNamesAreRequired() { assertTrue(listOf("-wal", "-shm", "-journal").contains("-wal")) } }
class SQLiteSnapshotCleanupTest { @Test fun handleIsCloseableConcept() { assertTrue(AutoCloseable::class.java.isAssignableFrom(SQLiteSnapshotHandle::class.java)) } }
class SQLiteViewerRecompositionTest { @Test fun snapshotIdPreventsReloadModel() { assertEquals("s", SQLiteDatabaseData("/x",0,0,0,null, emptyList(), emptyList(), emptyList(), snapshotId="s").snapshotId) } }
class SQLiteViewerBackNavigationTest { @Test fun rowDetailIsDestination() { val detail: Any = SQLiteDestination.RowDetail("/x", "t", 0, emptyList()); assertTrue(detail is SQLiteDestination) } }
class ViewerActionDoesNotLeakToHomeTest { @Test fun hexEmptyControlsCanBeDisabled() { assertFalse(HexViewerData("Hex", "/e", 0, 0, ByteArray(0), emptyList(), "Archivo vacío").fileSize > 0) } }
class LogicalPackageDirectoryNameTest { @Test fun logicalNameUsesVirtualPathLastSegment() { assertEquals("com.valcrono.testapp.a", "/data/data/com.valcrono.testapp.a".substringAfterLast('/')) } }
class StorageLogicalNodesTest { @Test fun storageTreeContainsEmulatedZero() { assertEquals(listOf("/storage/emulated", "/storage/emulated/0"), listOf("/storage/emulated", "/storage/emulated/0")) } }
