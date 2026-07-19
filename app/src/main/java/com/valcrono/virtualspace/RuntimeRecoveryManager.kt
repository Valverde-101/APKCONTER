package com.valcrono.virtualspace

class RuntimeRecoveryManager(private val db: ValcronoDatabase) {
    suspend fun recover(now: Long = System.currentTimeMillis(), staleHeartbeatMs: Long = 15_000L) {
        RuntimeSlotRepository(db).ensureSeeded()
        db.runtimeSlots().recoverStaleSlots(now - staleHeartbeatMs, now)
        db.runtime().markStaleStarting(now)
        db.runtime().markProcessLost(now)
    }
}
