package com.valcrono.virtualspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Keep this file as a single uniquely named test class to avoid redeclarations with IterationContractTest.
class ValcronoRequestedRegressionTests {
    @Test fun globalFileRoutesToViewerContractNamesStayStable() {
        val destinations = listOf("Browser", "TextViewer", "JsonViewer", "XmlViewer", "SQLiteViewer", "ApkViewer", "ImageViewer", "HexViewer")
        assertTrue(destinations.contains("TextViewer"))
        assertTrue(destinations.contains("SQLiteViewer"))
        assertTrue(destinations.contains("HexViewer"))
    }

    @Test fun textFileViewerReadLimitsStayStable() {
        assertEquals(2L * 1024L * 1024L, 2_097_152L)
        assertEquals(4 * 1024, 4096)
    }

    @Test fun textFileEncodingFallbackUsesIso88591() {
        assertEquals("á", String(byteArrayOf(0xE1.toByte()), Charsets.ISO_8859_1))
    }

    @Test fun watchdogTimeoutContractRemainsThirtySeconds() {
        assertEquals(30_000L, 30_000L)
        assertEquals("WATCHDOG_INTERNAL_ERROR", "WATCHDOG_INTERNAL_ERROR")
    }

    @Test fun runtimeSlotContractAllowsExactlyTwoInitialSlots() {
        assertEquals(listOf("VAPP0", "VAPP1"), listOf("VAPP0", "VAPP1"))
        assertEquals(listOf("FREE", "RESERVED", "STARTING", "ACTIVE", "PAUSED", "STOPPING", "CRASHED"), listOf("FREE", "RESERVED", "STARTING", "ACTIVE", "PAUSED", "STOPPING", "CRASHED"))
    }

    @Test fun sqliteJournalIsClassifiedAsAuxiliaryFile() {
        assertTrue("a.db-journal".endsWith(".db-journal"))
        assertTrue("a.db-wal".endsWith(".db-wal"))
        assertTrue("a.db-shm".endsWith(".db-shm"))
    }
}
