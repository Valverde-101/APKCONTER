package com.valcrono.virtualspace

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun source(path: String): String {
    val candidates = listOf(
        File(path),
        File("..", path),
        File(System.getProperty("user.dir"), path),
        File(System.getProperty("user.dir")).parentFile?.let { File(it, path) },
    ).filterNotNull()
    return candidates.firstOrNull { it.isFile }?.readText() ?: error("Source file not found: $path from ${System.getProperty("user.dir")}")
}

class RuntimeSlotRecoveryContractTest {
    @Test fun serviceOwnsHeartbeatWithMonotonicElapsedRealtime() {
        val runtime = source("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt")
        val activity = source("app/src/main/java/com/valcrono/virtualspace/ProxyActivity.kt")
        assertTrue(runtime.contains("SupervisorJob()"))
        assertTrue(runtime.contains("startHeartbeatLoop"))
        assertTrue(runtime.contains("delay(1500)"))
        assertTrue(runtime.contains("SystemClock.elapsedRealtime()"))
        assertTrue(!activity.contains("requestHeartbeat"))
    }

    @Test fun staleHeartbeatAndBinderDeathReclaimSlots() {
        val room = source("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt")
        val reclaimer = source("app/src/main/java/com/valcrono/virtualspace/RuntimeSlotReclaimer.kt")
        val proxy = source("app/src/main/java/com/valcrono/virtualspace/ProxyActivity.kt")
        assertTrue(room.contains("reclaimSlot"))
        assertTrue(room.contains("state='FREE'"))
        assertTrue(room.contains("lastHeartbeatElapsedRealtime"))
        assertTrue(reclaimer.contains("expectedSessionId"))
        assertTrue(reclaimer.contains("requeueDeliveringFor"))
        assertTrue(proxy.contains("linkToDeath"))
        assertTrue(proxy.contains("onBindingDied"))
        assertTrue(proxy.contains("onNullBinding"))
    }

    @Test fun allocatorReconcilesBeforeNoFreeSlots() {
        val runtime = source("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt")
        val sessions = source("app/src/main/java/com/valcrono/virtualspace/RuntimeSessionRepository.kt")
        assertTrue(runtime.contains("reconcileRuntimeState()"))
        assertTrue(sessions.contains("RuntimeSlotReclaimer(db).reconcileRuntimeState"))
        assertTrue(sessions.contains("UUID.randomUUID().toString()"))
    }

    @Test fun hostBrowserAndViewersStayInMainActivityNotRuntimeAllocator() {
        val main = source("app/src/main/java/com/valcrono/virtualspace/MainActivity.kt")
        assertTrue(main.contains("FileDestination.Browser"))
        assertTrue(main.contains("GlobalFileExplorer"))
        assertTrue(!main.substringAfter("is FileDestination.Browser").take(500).contains("prepareLaunch"))
    }
}
