package com.valcrono.virtualspace

import android.os.SystemClock

class RuntimeRecoveryManager(private val db: ValcronoDatabase) {
    suspend fun recover(now: Long = System.currentTimeMillis(), staleHeartbeatMs: Long = 30_000L) {
        RuntimeSlotRepository(db).ensureSeeded()
        RuntimeSlotReclaimer(db).reconcileRuntimeState(now)
        db.runtimeSlots().recoverStaleSlots(SystemClock.elapsedRealtime() - staleHeartbeatMs, now)
        db.runtime().markStaleStarting(now)
        db.runtime().markProcessLost(now)
    }
}
