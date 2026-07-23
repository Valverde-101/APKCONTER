package com.valcrono.virtualspace

import android.os.SystemClock
import android.system.Os
import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock

private const val HEARTBEAT_STALE_MS = HEARTBEAT_TIMEOUT_MS * MISSED_HEARTBEATS_REQUIRED

enum class RuntimeReclaimReason(val code: String, val message: String) {
    STALE_HEARTBEAT("STALE_HEARTBEAT", "Heartbeat vencido"),
    BINDER_DIED("BINDER_DIED", "Binder del proceso virtual murió"),
    PROCESS_LOST("PROCESS_LOST", "El proceso anterior ya no existe."),
    HEARTBEAT_DELAYED_BINDER_ALIVE("HEARTBEAT_DELAYED_BINDER_ALIVE", "Heartbeat retrasado con Binder vivo"),
    STOPPED("STOPPED", "Sesión detenida"),
    CANCELLED("LAUNCH_CANCELLED", "Inicio cancelado"),
    STATE_RECONCILED("STATE_RECONCILED", "Estado reconciliado"),
    HOST_RESTART("HOST_RESTART", "Reinicio del host reconciliado"),
    STARTUP_TIMEOUT("STARTUP_TIMEOUT", "Timeout durante startup"),
    SLOT_NORMALIZED_FREE("SLOT_NORMALIZED_FREE", "Slot vacío normalizado a FREE"),
    HOST_REMOVED_FROM_RECENTS("STOPPED_BY_HOST", "VirtualSpace fue retirada de Recientes")
}

enum class ReclaimResult { RECLAIMED, OWNERSHIP_CHANGED, SLOT_NOT_FOUND, MISSING_OWNER }

class RuntimeSlotReclaimer(private val db: ValcronoDatabase) {
    suspend fun reclaimSlot(slotId: String, expectedSessionId: String?, expectedLaunchAttemptId: String?, expectedReservationToken: String?, reason: RuntimeReclaimReason, now: Long = System.currentTimeMillis()): ReclaimResult = RuntimeSlotLocks.mutex(slotId).withLock {
        val elapsed = SystemClock.elapsedRealtime()
        db.withTransaction {
            val slot = db.runtimeSlots().get(slotId) ?: return@withTransaction ReclaimResult.SLOT_NOT_FOUND
            logSlotMutation("RECLAIM_REQUESTED", slot, reason.name)
            if (expectedSessionId == null || expectedLaunchAttemptId == null || expectedReservationToken == null) {
                logSlotMutation("RECLAIM_SKIPPED_OWNERSHIP_CHANGED", slot, "missingOwner:${reason.name}")
                return@withTransaction ReclaimResult.MISSING_OWNER
            }
            if (slot.sessionId != expectedSessionId || slot.launchAttemptId != expectedLaunchAttemptId || slot.reservationToken != expectedReservationToken) {
                logSlotMutation("RECLAIM_SKIPPED_OWNERSHIP_CHANGED", slot, "expected=${expectedSessionId.take(8)}/${expectedLaunchAttemptId.take(8)}/${expectedReservationToken.take(8)} actual=${slot.sessionId?.take(8)}/${slot.launchAttemptId?.take(8)}/${slot.reservationToken?.take(8)}")
                return@withTransaction ReclaimResult.OWNERSHIP_CHANGED
            }
            val session = db.runtime().get(expectedSessionId)
            if (session?.currentLaunchAttemptId == expectedLaunchAttemptId) {
                val state = when { reason == RuntimeReclaimReason.STOPPED || reason == RuntimeReclaimReason.CANCELLED -> "STOPPED"; session?.state == "STARTING" -> "FAILED"; session?.hasReachedActiveAck == true -> "CRASHED"; else -> "FAILED" }
                val code = when { state == "FAILED" && reason == RuntimeReclaimReason.PROCESS_LOST -> "PROCESS_DIED_DURING_START"; state == "CRASHED" -> "PROCESS_DIED"; else -> reason.code }
                val phase = when { state == "FAILED" && reason == RuntimeReclaimReason.PROCESS_LOST -> "PROCESS_DIED_DURING_START"; state == "CRASHED" -> "PROCESS_DIED"; else -> reason.name }
                db.runtime().compareAndSetState(expectedSessionId, expectedLaunchAttemptId, state, phase, now, code, reason.message)
                db.launchTokens().revokeAttempt(expectedSessionId, expectedLaunchAttemptId, now)
            }
            if (slot.packageName != null && slot.virtualUserId != null) db.messages().requeueDeliveringFor(slot.packageName, slot.virtualUserId)
            val changed = db.runtimeSlots().reclaimSlot(slotId, expectedSessionId, expectedLaunchAttemptId, expectedReservationToken, reason.name, now, elapsed, reason.name)
            val after = db.runtimeSlots().get(slotId)
            if (after != null) logSlotMutation("SLOT_RECLAIMED", after, reason.name, oldState = slot.state)
            check(changed == 1 && after != null && after.state == "FREE" && after.sessionId == null && after.packageName == null && after.hostPid == null && after.errorCode == null && after.errorMessage == null) { "SLOT_RECLAIM_INVARIANT_FAILED slotId=$slotId" }
            ReclaimResult.RECLAIMED
        }
    }

    suspend fun reconcileRuntimeState(now: Long = System.currentTimeMillis()): Int {
        RuntimeSlotRepository(db).ensureSeeded()
        var reclaimed = db.runtimeSlots().normalizeEmptySlots(RuntimeReclaimReason.SLOT_NORMALIZED_FREE.name, now, SystemClock.elapsedRealtime())
        val elapsedNow = SystemClock.elapsedRealtime()
        db.runtimeSlots().getAll().forEach { slot ->
            val sid = slot.sessionId ?: return@forEach
            // Startup states are deliberately skipped here: the launch watchdog owns their phase
            // timeouts. Keep the legacy BINDING/STARTING/WAITING_ACTIVE_ACK markers in this
            // set for migrated databases and source-level ownership contracts.
            if (slot.state in setOf("FREE", "STOPPED", "RESERVED", "BINDING", "STARTING", "WAITING_ACTIVE_ACK", "PROCESS_STARTING", "SERVICE_CONNECTED", "LOAD_REQUEST_SENT", "CLASSLOADER_READY", "ACTIVITY_STARTING", "RECOVERING", "ADOPTING", "STOPPING", "RECLAIMING")) return@forEach
            val session = db.runtime().get(sid)
            val terminal = session?.state in setOf("ERROR", "STOPPED", "CRASHED", "DEAD", "CANCELLED") || slot.state in setOf("CRASHED", "ERROR")
            if (session != null && (session.hasReachedActiveAck != true || slot.state !in setOf("ACTIVE_FOREGROUND", "ACTIVE_BACKGROUND", "PAUSED_BY_USER") || slot.binderAlive == false)) return@forEach
            val pidAlive = slot.hostPid?.let { runCatching { Os.kill(it, 0); true }.getOrDefault(false) } == true
            val fresh = slot.lastHeartbeatElapsedRealtime?.let { elapsedNow - it <= HEARTBEAT_STALE_MS } ?: ((slot.lastHeartbeatAt ?: 0L) >= now - HEARTBEAT_STALE_MS)
            if (!fresh && (pidAlive || slot.binderAlive == true)) {
                logSlotMutation(RuntimeReclaimReason.HEARTBEAT_DELAYED_BINDER_ALIVE.name, slot, "binderAlive=${slot.binderAlive} pidAlive=$pidAlive")
                return@forEach
            }
            if (session == null || terminal || !pidAlive) {
                val reason = when { !pidAlive -> RuntimeReclaimReason.PROCESS_LOST; else -> RuntimeReclaimReason.STATE_RECONCILED }
                if (reclaimSlot(slot.slotId, sid, slot.launchAttemptId, slot.reservationToken, reason, now) == ReclaimResult.RECLAIMED) reclaimed++
            }
        }
        if (reclaimed > 0) logLaunch("STATE_RECONCILED", null, null, null, null, null, "reclaimed=$reclaimed", "slotsFree")
        return reclaimed
    }
}
