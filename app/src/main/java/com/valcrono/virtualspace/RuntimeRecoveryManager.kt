package com.valcrono.virtualspace

import android.os.SystemClock
import androidx.room.withTransaction

class RuntimeRecoveryManager(private val db: ValcronoDatabase) {
    suspend fun recover(now: Long = System.currentTimeMillis(), staleHeartbeatMs: Long = HOST_RECOVERY_GRACE_MS) = recoverRuntimeAfterHostRestart(now, staleHeartbeatMs)

    suspend fun recoverRuntimeAfterHostRestart(now: Long = System.currentTimeMillis(), staleHeartbeatMs: Long = HOST_RECOVERY_GRACE_MS) {
        RuntimeHostRegistry.startRecovery()
        try {
            RuntimeSlotRepository(db).ensureSeeded()
            val reclaimer = RuntimeSlotReclaimer(db)
            db.runtimeSlots().normalizeEmptySlots(RuntimeReclaimReason.SLOT_NORMALIZED_FREE.name, now, SystemClock.elapsedRealtime())
            db.runtimeSlots().getAll().forEach { slot ->
                val sid = slot.sessionId
                val empty = sid == null && slot.packageName == null && slot.hostPid == null && slot.launchAttemptId == null
                if (empty) {
                    db.runtimeSlots().release(slot.slotId, now, SystemClock.elapsedRealtime())
                    logLaunch("SLOT_NORMALIZED_FREE", null, null, null, null, null, slot.slotId, "FREE")
                    return@forEach
                }
                if (sid != null) {
                    slot.hostPid?.let { pid -> runCatching { android.os.Process.killProcess(pid) } }
                    logLaunch("PREVIOUS_HOST_SESSION_INVALIDATED", sid, slot.launchAttemptId, null, slot.packageName, slot.virtualUserId, "hostInstanceId/PID/runtimeGeneration anterior", "FREE")
                    reclaimer.reclaimSlot(slot.slotId, sid, slot.launchAttemptId, slot.reservationToken, RuntimeReclaimReason.HOST_RESTART, now)
                }
            }
            reclaimer.reconcileRuntimeState(now)
            db.runtime().markStaleStarting(now)
        } finally {
            RuntimeHostRegistry.completeRecovery()
        }
    }
}
