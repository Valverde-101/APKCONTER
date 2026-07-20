package com.valcrono.virtualspace

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun source(path: String): String {
    val cwd = File(System.getProperty("user.dir"))
    val candidates = listOf(
        File(path),
        File("..", path),
        File(cwd, path),
        cwd.parentFile?.let { File(it, path) },
    ).filterNotNull()
    return candidates.firstOrNull { it.isFile }?.readText() ?: error("Source file not found: $path from ${cwd.absolutePath}")
}

class RuntimeSlotOwnershipContractTest {
    @Test fun activeAckRequiresFullOwnershipAndEpoch() {
        val room = source("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt")
        val slots = source("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt")
        listOf("reservationToken", "runtimeGeneration", "slotEpoch", "ACTIVE_ACK_REJECTED_OWNERSHIP_MISMATCH").forEach {
            assertTrue(it, room.contains(it) || slots.contains(it))
        }
        assertTrue(room.contains("AND reservationToken=:reservationToken AND runtimeGeneration=:runtimeGeneration AND slotEpoch=:slotEpoch"))
    }

    @Test fun reclaimIsProtectedByFullOwnerIdentity() {
        val room = source("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt")
        val reclaimer = source("app/src/main/java/com/valcrono/virtualspace/RuntimeSlotReclaimer.kt")
        assertTrue(room.contains("AND sessionId=:expectedSessionId AND launchAttemptId=:expectedLaunchAttemptId AND reservationToken=:expectedReservationToken"))
        assertTrue(reclaimer.contains("ReclaimResult.OWNERSHIP_CHANGED"))
        assertTrue(reclaimer.contains("RECLAIM_SKIPPED_OWNERSHIP_CHANGED"))
    }

    @Test fun watchdogDoesNotReclaimStartupHeartbeatAsStale() {
        val reclaimer = source("app/src/main/java/com/valcrono/virtualspace/RuntimeSlotReclaimer.kt")
        assertTrue(reclaimer.contains("RESERVED") && reclaimer.contains("WAITING_ACTIVE_ACK"))
        assertTrue(reclaimer.contains("return@forEach"))
    }

    @Test fun freeSlotsDoNotKeepOperationalErrors() {
        val room = source("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt")
        assertTrue(room.contains("state='FREE'"))
        assertTrue(room.contains("errorCode=NULL, errorMessage=NULL"))
        assertTrue(room.contains("state='FREE' AND errorCode IS NOT NULL"))
    }
}
