package com.valcrono.virtualspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun strictSource(path: String): String {
    val candidates = listOf(File(path), File("..", path), File(System.getProperty("user.dir"), path), File(System.getProperty("user.dir")).parentFile?.let { File(it, path) }).filterNotNull()
    return candidates.firstOrNull { it.isFile }?.readText() ?: error("Source file not found: $path")
}

class StrictLaunchStateMachineContractTest {
    @Test fun strictSlotStatesAreDeclared() {
        val names = RuntimeSlotState.entries.map { it.name }
        listOf("FREE", "RESERVED", "PROCESS_STARTING", "SERVICE_CONNECTED", "LOAD_REQUEST_SENT", "CLASSLOADER_READY", "ACTIVITY_STARTING", "ACTIVE_FOREGROUND", "ACTIVE_BACKGROUND", "STOPPING", "FAILED").forEach { assertTrue(it, names.contains(it)) }
        assertFalse("OCCUPIED must not be a launch-complete state", names.contains("OCCUPIED"))
    }

    @Test fun phaseTimeoutConstantsAreIndependent() {
        val s = strictSource("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt")
        listOf("SERVICE_CONNECT_TIMEOUT_MS = 5_000L", "CLASSLOADER_LOAD_TIMEOUT_MS = 8_000L", "ACTIVITY_CREATE_TIMEOUT_MS = 8_000L", "ACTIVE_ACK_TIMEOUT_MS = 5_000L").forEach { assertTrue(it, s.contains(it)) }
    }

    @Test fun classLoaderLessHeartbeatCannotMarkSessionActive() {
        val s = strictSource("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt")
        assertTrue(s.contains("hasReachedActiveAck=1"))
        assertFalse(s.contains("'OCCUPIED') THEN 'ACTIVE'"))
    }

    @Test fun launchAttemptsAreNotReusedForStartingSessions() {
        val s = strictSource("app/src/main/java/com/valcrono/virtualspace/RuntimeSessionRepository.kt")
        assertTrue(s.contains("val attemptId = UUID.randomUUID().toString()"))
        assertFalse(s.contains("existingStartingAttemptId"))
    }

    @Test fun openDecisionRequiresConfirmedActiveRuntime() {
        val s = strictSource("app/src/main/java/com/valcrono/virtualspace/RuntimeSessionRepository.kt")
        assertTrue(s.contains("slotActive && classLoaderLoaded -> RuntimeOpenMode.RECOVER_SERVICE"))
    }
}
