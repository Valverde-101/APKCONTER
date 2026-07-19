package com.valcrono.virtualspace

import android.os.SystemClock
import android.system.Os
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val slotReclaimMutex = Mutex()
private const val HEARTBEAT_STALE_MS = 30_000L

enum class RuntimeReclaimReason(val code: String, val message: String) {
    STALE_HEARTBEAT("STALE_HEARTBEAT", "Heartbeat vencido"),
    BINDER_DIED("BINDER_DIED", "Binder del proceso virtual murió"),
    PROCESS_LOST("PROCESS_LOST", "El proceso anterior ya no existe."),
    STOPPED("STOPPED", "Sesión detenida"),
    CANCELLED("LAUNCH_CANCELLED", "Inicio cancelado"),
    STATE_RECONCILED("STATE_RECONCILED", "Estado reconciliado"),
    HOST_RESTART("HOST_RESTART", "Reinicio del host reconciliado"),
    SLOT_NORMALIZED_FREE("SLOT_NORMALIZED_FREE", "Slot vacío normalizado a FREE")
}

class RuntimeSlotReclaimer(private val db: ValcronoDatabase) {
    suspend fun reclaimSlot(slotId: String, expectedSessionId: String, reason: RuntimeReclaimReason, now: Long = System.currentTimeMillis()): Boolean = slotReclaimMutex.withLock {
        db.withTransaction {
            val slot = db.runtimeSlots().get(slotId) ?: return@withTransaction false
            if (slot.sessionId != expectedSessionId) return@withTransaction false
            val session = db.runtime().get(expectedSessionId)
            if (session?.currentLaunchAttemptId != null) {
                val state = if (reason == RuntimeReclaimReason.STOPPED || reason == RuntimeReclaimReason.CANCELLED) "STOPPED" else "DEAD"
                db.runtime().compareAndSetState(expectedSessionId, session.currentLaunchAttemptId, state, reason.name, now, reason.code, reason.message)
                db.launchTokens().revokeAttempt(expectedSessionId, session.currentLaunchAttemptId, now)
            }
            if (slot.packageName != null && slot.virtualUserId != null) db.messages().requeueDeliveringFor(slot.packageName, slot.virtualUserId)
            val changed = db.runtimeSlots().reclaimSlot(slotId, expectedSessionId, reason.name, reason.code, reason.message, now) > 0
            val after = db.runtimeSlots().get(slotId)
            check(after?.state == "FREE" && after.sessionId == null && after.packageName == null && after.hostPid == null) { "SLOT_RECLAIM_INVARIANT_FAILED slotId=$slotId" }
            changed
        }
    }

    suspend fun reconcileRuntimeState(now: Long = System.currentTimeMillis()): Int = slotReclaimMutex.withLock {
        RuntimeSlotRepository(db).ensureSeeded()
        var reclaimed = db.runtimeSlots().normalizeEmptySlots(RuntimeReclaimReason.SLOT_NORMALIZED_FREE.name, now)
        val elapsedNow = SystemClock.elapsedRealtime()
        db.runtimeSlots().getAll().forEach { slot ->
            val sid = slot.sessionId
            if (slot.state in setOf("FREE", "STOPPED") || sid == null) return@forEach
            val session = db.runtime().get(sid)
            val terminal = session?.state in setOf("ERROR", "STOPPED", "CRASHED", "DEAD", "CANCELLED") || slot.state in setOf("CRASHED", "ERROR")
            val pidAlive = slot.hostPid?.let { runCatching { Os.kill(it, 0); true }.getOrDefault(false) } == true
            val fresh = slot.lastHeartbeatElapsedRealtime?.let { elapsedNow - it <= HEARTBEAT_STALE_MS } ?: ((slot.lastHeartbeatAt ?: 0L) >= now - HEARTBEAT_STALE_MS)
            if (session == null || terminal || !pidAlive || !fresh) {
                val reason = when {
                    !pidAlive -> RuntimeReclaimReason.PROCESS_LOST
                    !fresh -> RuntimeReclaimReason.STALE_HEARTBEAT
                    else -> RuntimeReclaimReason.STATE_RECONCILED
                }
                db.withTransaction {
                    if (slot.packageName != null && slot.virtualUserId != null) db.messages().requeueDeliveringFor(slot.packageName, slot.virtualUserId)
                    db.runtimeSlots().reclaimSlot(slot.slotId, sid, reason.name, reason.code, reason.message, now)
                }
                reclaimed++
            }
        }
        if (reclaimed > 0) logLaunch("STATE_RECONCILED", null, null, null, null, null, "reclaimed=$reclaimed", "slotsFree")
        reclaimed
    }
}
