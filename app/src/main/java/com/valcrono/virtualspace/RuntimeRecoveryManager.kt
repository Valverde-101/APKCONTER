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
                    val pidAlive = slot.hostPid?.let { runCatching { android.system.Os.kill(it, 0); true }.getOrDefault(false) } == true
                    val session = db.runtime().get(sid)
                    if (pidAlive && session != null && session.currentLaunchAttemptId == slot.launchAttemptId) {
                        db.withTransaction {
                            db.runtime().repairActive(sid, now, SystemClock.elapsedRealtime(), slot.hostPid!!)
                            db.runtimeSlots().heartbeat(slot.slotId, sid, slot.launchAttemptId ?: return@withTransaction, slot.reservationToken ?: return@withTransaction, slot.runtimeGeneration ?: RuntimeHostRegistry.runtimeGeneration, slot.slotEpoch, slot.hostPid, "ACTIVE_BACKGROUND", slot.pssBytes, now, SystemClock.elapsedRealtime(), "recoverRuntimeAfterHostRestart")
                            slot.packageName?.let { pkg -> slot.virtualUserId?.let { user -> db.messages().requeueDeliveringFor(pkg, user) } }
                        }
                        logLaunch("SESSION_ADOPTED", sid, slot.launchAttemptId, null, slot.packageName, slot.virtualUserId, "previousHostDetected", "ADOPTED")
                    } else {
                        logLaunch(if (pidAlive) "SESSION_REJECTED" else "STALE_PROCESS_TERMINATED", sid, slot.launchAttemptId, null, slot.packageName, slot.virtualUserId, "pidAlive=$pidAlive", "FREE")
                        reclaimer.reclaimSlot(slot.slotId, sid, slot.launchAttemptId, slot.reservationToken, RuntimeReclaimReason.HOST_RESTART, now)
                    }
                }
            }
            reclaimer.reconcileRuntimeState(now)
            db.runtime().markStaleStarting(now)
        } finally {
            RuntimeHostRegistry.completeRecovery()
        }
    }
}
