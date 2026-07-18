package com.valcrono.virtualspace

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TextViewerActionsDoNotUseImportStatusTest {
    @Test fun textViewerBranchDoesNotAssignImportStatus() {
        val source = File("src/main/java/com/valcrono/virtualspace/MainActivity.kt").readText()
        val branch = source.substringAfter("private fun TextViewerActions").substringBefore("private fun SQLiteDatabaseOverview")
        assertFalse(branch.contains("importStatus ="))
    }
}

class MemoryAlertExpiresTest {
    @Test fun memoryAlertUsesTimestampAndUiHiddenGuard() {
        val source = File("src/main/java/com/valcrono/virtualspace/MainActivity.kt").readText()
        assertTrue(source.contains("recentMemoryAlertAt"))
        assertTrue(source.contains("10_000"))
        assertTrue(source.contains("TRIM_MEMORY_UI_HIDDEN"))
    }
}

class EmptyCallbackStaticTest {
    @Test fun mainActivityHasNoEmptyOnClickCallbacks() {
        val source = File("src/main/java/com/valcrono/virtualspace/MainActivity.kt").readText()
        assertFalse(source.contains("onClick = {}"))
    }
}
