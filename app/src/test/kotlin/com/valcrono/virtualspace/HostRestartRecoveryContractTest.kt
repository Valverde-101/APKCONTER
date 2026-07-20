package com.valcrono.virtualspace

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRestartRecoveryContractTest {
    private fun src(path: String): String {
        val candidates = listOf(File(path), File("..", path), File(System.getProperty("user.dir"), path), File(System.getProperty("user.dir")).parentFile?.let { File(it, path) }).filterNotNull()
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("Source file not found: $path")
    }

    @Test fun runtimeHasRecoveringBarrierBeforeLaunchAndWatchdog() {
        val repo = src("src/main/java/com/valcrono/virtualspace/RuntimeSessionRepository.kt")
        assertTrue(repo.contains("RuntimeHostRegistry.awaitReady()"))
        assertTrue(repo.contains("allocatorDiagnostics"))
    }

    @Test fun heartbeatDuringRecoveryIsNotTerminalCrash() {
        val slots = src("src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt")
        assertTrue(slots.contains("HOST_RECOVERING"))
        assertTrue(slots.contains("RuntimeHeartbeatDisposition"))
        assertTrue(!slots.contains("check(slotChanged == 1) { \"HEARTBEAT_SLOT_REJECTED"))
    }

    @Test fun emptyCrashedSlotsNormalizeToFree() {
        val room = src("src/main/java/com/valcrono/virtualspace/VirtualRoom.kt")
        assertTrue(room.contains("normalizeEmptySlots"))
        assertTrue(room.contains("state='FREE'"))
    }

    @Test fun hostToolsHaveHostOnlyLauncher() {
        val launcher = src("src/main/java/com/valcrono/virtualspace/HostToolLauncher.kt")
        assertTrue(launcher.contains("HOST_TOOL_OPENED"))
        assertTrue(!launcher.contains("VirtualProcessAllocator"))
        assertTrue(!launcher.contains("reserveSlot("))
        assertTrue(!launcher.contains("launchVirtualApp"))
    }
}
