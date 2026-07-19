package com.valcrono.virtualspace

import android.os.SystemClock

class RuntimeRecoveryManager(private val db: ValcronoDatabase) {
    suspend fun recover(now: Long = System.currentTimeMillis(), staleHeartbeatMs: Long = 30_000L) = recoverRuntimeAfterHostRestart(now, staleHeartbeatMs)

    suspend fun recoverRuntimeAfterHostRestart(now: Long = System.currentTimeMillis(), staleHeartbeatMs: Long = 30_000L) {
        RuntimeHostRegistry.startRecovery()
        try {
            RuntimeSlotRepository(db).ensureSeeded()
            val reclaimer = RuntimeSlotReclaimer(db)
            db.runtimeSlots().normalizeEmptySlots(RuntimeReclaimReason.SLOT_NORMALIZED_FREE.name, now)
            db.runtimeSlots().getAll().forEach { slot ->
                val sid = slot.sessionId
                val empty = sid == null && slot.packageName == null && slot.hostPid == null && slot.launchAttemptId == null
                if (empty) {
                    db.runtimeSlots().release(slot.slotId, now)
                    logLaunch("SLOT_NORMALIZED_FREE", null, null, null, null, null, slot.slotId, "FREE")
                    return@forEach
                }
                if (sid != null) {
                    val pidAlive = slot.hostPid?.let { runCatching { android.system.Os.kill(it, 0); true }.getOrDefault(false) } == true
                    val session = db.runtime().get(sid)
                    if (pidAlive && session != null && session.currentLaunchAttemptId == slot.launchAttemptId) {
                        db.withTransaction {
                            db.runtime().repairActive(sid, now, slot.hostPid!!)
                            db.runtimeSlots().heartbeat(slot.slotId, sid, slot.hostPid, "ACTIVE_BACKGROUND", slot.pssBytes, now, SystemClock.elapsedRealtime())
                            slot.packageName?.let { pkg -> slot.virtualUserId?.let { user -> db.messages().requeueDeliveringFor(pkg, user) } }
                        }
                        logLaunch("SESSION_ADOPTED", sid, slot.launchAttemptId, null, slot.packageName, slot.virtualUserId, "previousHostDetected", "ADOPTED")
                    } else {
                        logLaunch(if (pidAlive) "SESSION_REJECTED" else "STALE_PROCESS_TERMINATED", sid, slot.launchAttemptId, null, slot.packageName, slot.virtualUserId, "pidAlive=$pidAlive", "FREE")
                        reclaimer.reclaimSlot(slot.slotId, sid, RuntimeReclaimReason.HOST_RESTART, now)
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
