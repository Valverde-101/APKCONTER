package com.valcrono.virtualspace

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GenericRuntimeStaticTest {
    private fun source(path: String) = File(path).readText()

    @Test fun parserDoesNotUseFirstActivityAsLauncher() {
        val s = source("app/src/main/java/com/valcrono/virtualspace/AndroidArchivePackageParser.kt")
        assertFalse(s.contains("components.firstOrNull{it.type==ComponentType.ACTIVITY}?.name"))
        assertTrue(s.contains("android.intent.action.MAIN"))
        assertTrue(s.contains("android.intent.category.LAUNCHER"))
    }

    @Test fun cooperativeAndAndroidEntryPointsAreSeparate() {
        val room = source("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt")
        assertTrue(room.contains("launcherActivityName"))
        assertTrue(room.contains("cooperativeEntryPointClass"))
        assertTrue(room.contains("genericRuntimeCapability"))
        assertTrue(room.contains("blockingReasonsJson"))
    }

    @Test fun runtimeUsesAdapterSelection() {
        val slots = source("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt")
        val adapters = source("app/src/main/java/com/valcrono/virtualspace/VirtualRuntimeAdapter.kt")
        assertTrue(adapters.contains("interface VirtualRuntimeAdapter"))
        assertTrue(adapters.contains("class CooperativeRuntimeAdapter"))
        assertTrue(adapters.contains("class GenericApkRuntimeAdapter"))
        assertTrue(slots.contains("runtimeAdapterFor(pkg)"))
    }

    @Test fun nativeLibrariesAreNotAutomaticallyBlocked() {
        val importer = source("app/src/main/java/com/valcrono/virtualspace/ApkImportV1.kt")
        assertFalse(importer.contains("REQUIRES_NATIVE_SUPPORT"))
        assertTrue(importer.contains("GENERIC_NATIVE_COMPATIBLE"))
        assertTrue(importer.contains("ABI_UNSUPPORTED"))
    }
}
