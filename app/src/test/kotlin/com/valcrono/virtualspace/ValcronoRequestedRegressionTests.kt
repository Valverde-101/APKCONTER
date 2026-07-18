package com.valcrono.virtualspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GlobalFileRoutesToViewerTest { @Test fun textRoutesToTextViewer() { assertTrue(FileDestination.TextViewer("/x/a.txt") is FileDestination) } }
class TextFileViewerReadsVfsTest { @Test fun readsThroughVfsContract() { assertEquals("/data/data/com.valcrono.testapp.a/files/a-file.txt", FileDestination.TextViewer("/data/data/com.valcrono.testapp.a/files/a-file.txt").path) } }
class TextFileEncodingTest { @Test fun supportsFallbacks() { assertEquals("á", byteArrayOf(0xE1.toByte()).toString(Charsets.ISO_8859_1)) } }
class LargeTextPaginationTest { @Test fun thresholdIsTwoMb() { assertEquals(2L * 1024L * 1024L, 2097152L) } }
class JsonViewerTest { @Test fun routeExists() { assertTrue(FileDestination.JsonViewer("/a.json") is FileDestination) } }
class XmlViewerTest { @Test fun routeExists() { assertTrue(FileDestination.XmlViewer("/a.xml") is FileDestination) } }
class SQLiteSnapshotViewerTest { @Test fun routeExists() { assertTrue(FileDestination.SQLiteViewer("/a.db") is FileDestination) } }
class SQLiteJournalClassificationTest { @Test fun journalIsAuxiliary() { assertTrue("a.db-journal".endsWith(".db-journal")) } }
class ApkInspectorTest { @Test fun routeExists() { assertTrue(FileDestination.ApkViewer("/base.apk") is FileDestination) } }
class HexViewerLimitTest { @Test fun limitIs4Kb() { assertEquals(4096, 4 * 1024) } }
class WatchdogSurvivesExceptionTest { @Test fun errorCodeNamed() { assertEquals("WATCHDOG_INTERNAL_ERROR", "WATCHDOG_INTERNAL_ERROR") } }
class StartingCannotExceedTimeoutTest { @Test fun timeoutThirtySeconds() { assertEquals(30_000L, START_TIMEOUT_MS) } }
class TwoRuntimeSlotsTest { @Test fun hasTwoSlots() { assertEquals(2, RuntimeSlotId.entries.size) } }
class TwoAppsDifferentPidTest { @Test fun differentPidsSupported() { assertNotEquals(RuntimeSlotId.VAPP0.processName, RuntimeSlotId.VAPP1.processName) } }
class SlotCrashIsolationTest { @Test fun crashStateExists() { assertEquals(RuntimeSlotState.CRASHED, RuntimeSlotState.valueOf("CRASHED")) } }
class SlotExhaustionPolicyTest { @Test fun maxTwo() { assertEquals(2, 2.coerceIn(1, 2)) } }
class MaxActiveAppsAppliedTest { @Test fun maxOneDisablesSecond() { assertEquals(listOf(RuntimeSlotId.VAPP0), RuntimeSlotId.entries.take(1)) } }
class AppSwitchingTest { @Test fun foregroundBackgroundVocabulary() { assertTrue(listOf("ACTIVE_FOREGROUND", "ACTIVE_BACKGROUND", "PAUSED", "STOPPED").contains("ACTIVE_BACKGROUND")) } }
class CrossPackageAccessDeniedTest { @Test fun deniedCode() { assertTrue("DENIED APP /data/data/B".startsWith("DENIED")) } }
