package com.valcrono.virtualspace

import android.os.Process
import android.os.SystemClock
import android.system.Os
import androidx.room.withTransaction
import com.valcrono.core.VLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.UUID

private const val TOKEN_TTL_MS = 5 * 60 * 1000L
const val START_TIMEOUT_MS: Long = SERVICE_CONNECT_TIMEOUT_MS + CLASSLOADER_LOAD_TIMEOUT_MS + ACTIVITY_CREATE_TIMEOUT_MS + ACTIVE_ACK_TIMEOUT_MS

data class PreparedLaunch(val sessionId: String, val launchAttemptId: String, val launchToken: String, val shouldStartActivity: Boolean, val slotId: RuntimeSlotId?, val reservationToken: String, val runtimeGeneration: Long, val slotEpoch: Long)

enum class RuntimeOpenMode { COLD_START, WARM_RESUME, RECOVER_SERVICE }
enum class RuntimeEffectiveState { STOPPED, STARTING, ACTIVE_FOREGROUND, ACTIVE_BACKGROUND, PAUSED, ERROR }
data class RuntimeAppUiState(
    val session: VirtualRuntimeSessionEntity?,
    val slot: RuntimeSlotEntity?,
    val snapshot: RuntimeAppSnapshot?,
    val displayedState: DisplayedAppState,
    val runtimeActive: Boolean,
    val activityVisible: Boolean,
    val isRefreshing: Boolean = false,
) {
    val effectiveState: RuntimeEffectiveState = when (displayedState) {
        DisplayedAppState.ACTIVE_FOREGROUND -> RuntimeEffectiveState.ACTIVE_FOREGROUND
        DisplayedAppState.ACTIVE_BACKGROUND -> RuntimeEffectiveState.ACTIVE_BACKGROUND
        DisplayedAppState.STARTING, DisplayedAppState.CHECKING_RUNTIME -> RuntimeEffectiveState.STARTING
        DisplayedAppState.ERROR -> RuntimeEffectiveState.ERROR
        DisplayedAppState.STOPPING, DisplayedAppState.STOPPED -> RuntimeEffectiveState.STOPPED
    }
}

data class RuntimeOpenDecision(
    val mode: RuntimeOpenMode,
    val sessionId: String,
    val slotId: RuntimeSlotId,
    val taskId: Int?,
    val processAlive: Boolean,
    val heartbeatFresh: Boolean,
    val activityAttached: Boolean
)

class RuntimeSessionRepository(private val db: ValcronoDatabase) {
    fun observeSessions(): Flow<List<VirtualRuntimeSessionEntity>> = db.runtime().observeSessions()

    fun observeRuntimeAppState(packageName: String, virtualUserId: Int): Flow<RuntimeAppUiState> = combine(
        db.packages().observePackage(packageName, virtualUserId),
        db.runtime().observeByPackage(packageName, virtualUserId),
        db.runtimeSlots().observeByPackage(packageName, virtualUserId),
    ) { app, session, slot ->
        buildConsistentUiState(app = app, session = session, slot = slot)
    }.distinctUntilChanged()

    private fun buildConsistentUiState(app: VirtualPackageEntity?, session: VirtualRuntimeSessionEntity?, slot: RuntimeSlotEntity?): RuntimeAppUiState {
        val snapshot = buildRuntimeAppSnapshot(app, session, slot)
        val displayed = productionSafeDisplayedState(deriveDisplayedState(snapshot), snapshot)
        val active = displayed == DisplayedAppState.ACTIVE_FOREGROUND || displayed == DisplayedAppState.ACTIVE_BACKGROUND
        return RuntimeAppUiState(
            session = session,
            slot = if (displayed == DisplayedAppState.STOPPED) null else slot,
            snapshot = snapshot,
            displayedState = displayed,
            runtimeActive = active,
            activityVisible = snapshot?.activityAttached == true,
        )
    }

    suspend fun resolveOpenDecision(packageName: String, virtualUserId: Int): RuntimeOpenDecision? {
        RuntimeHostRegistry.awaitReady()
        RuntimeSlotReclaimer(db).reconcileRuntimeState()
        val now = System.currentTimeMillis(); val elapsedNow = SystemClock.elapsedRealtime()
        val session = db.runtime().forPackage(packageName, virtualUserId) ?: return null
        val slot = db.runtimeSlots().findBySession(session.sessionId) ?: return null
        val pid = slot.hostPid
        val processAlive = pid != null && runCatching { Os.kill(pid, 0); true }.getOrDefault(false)
        val heartbeatFresh = slot.lastHeartbeatElapsedRealtime?.let { it >= elapsedNow - START_TIMEOUT_MS } ?: ((slot.lastHeartbeatAt ?: session.lastHeartbeatAt) >= now - START_TIMEOUT_MS)
        val slotActive = slot.state == "ACTIVE_FOREGROUND" || slot.state == "ACTIVE_BACKGROUND"
        val classLoaderLoaded = session.classLoaderState == "LOADED"
        if (session.state == "STARTING" && slotActive && processAlive && heartbeatFresh && classLoaderLoaded) {
            logLaunch("STATE_RECONCILIATION_BEGIN", session.sessionId, session.currentLaunchAttemptId, null, packageName, virtualUserId, session.state, slot.state); db.runtime().repairActive(session.sessionId, now, SystemClock.elapsedRealtime(), pid!!); logLaunch("STATE_RECONCILIATION_SUCCESS", session.sessionId, session.currentLaunchAttemptId, null, packageName, virtualUserId, session.state, "ACTIVE")
            logLaunch("STATE_RECONCILED", session.sessionId, session.currentLaunchAttemptId, null, packageName, virtualUserId, "STARTING/${slot.state}", "ACTIVE/${slot.state}")
        }
        val mode = when {
            (session.state == "ACTIVE" || (session.state == "STARTING" && slotActive)) && slotActive && processAlive && heartbeatFresh && classLoaderLoaded -> RuntimeOpenMode.WARM_RESUME
            pid != null && processAlive && heartbeatFresh && slotActive && classLoaderLoaded -> RuntimeOpenMode.RECOVER_SERVICE
            else -> RuntimeOpenMode.COLD_START
        }
        return RuntimeOpenDecision(mode, session.sessionId, RuntimeSlotId.valueOf(slot.slotId), slot.taskId, processAlive, heartbeatFresh, slot.activityAttached)
    }

    suspend fun prepareLaunch(pkg: VirtualPackageEntity, activity: String, maxActiveApps: Int = 2, now: Long = System.currentTimeMillis()): PreparedLaunch {
        RuntimeHostRegistry.awaitReady()
        RuntimeSlotReclaimer(db).reconcileRuntimeState(now)
        return db.withTransaction {
        val before = db.runtime().forPackage(pkg.packageName, pkg.virtualUserId)
        val reusable = before?.takeUnless { it.state in setOf("ERROR", "STOPPED", "CRASHED", "DEAD", "CANCELLED") }
        val sessionId = reusable?.sessionId ?: UUID.randomUUID().toString()
        before?.takeIf { it.sessionId != sessionId }?.let { old -> old.currentLaunchAttemptId?.let { db.launchTokens().revokeAttempt(old.sessionId, it, now) }; db.runtime().deleteSession(old.sessionId) }
        val attemptId = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        logLaunch("SESSION_PREPARE", sessionId, attemptId, token, pkg.packageName, pkg.virtualUserId, before?.state, "STARTING")
        val existingActive = reusable?.takeIf { it.state in setOf("ACTIVE", "ACTIVE_FOREGROUND", "ACTIVE_BACKGROUND") }
        if (existingActive != null) {
            return@withTransaction PreparedLaunch(
                sessionId = sessionId,
                launchAttemptId = existingActive.currentLaunchAttemptId.orEmpty(),
                launchToken = token,
                shouldStartActivity = false,
                slotId = db.runtimeSlots().findBySession(sessionId)?.let { RuntimeSlotId.valueOf(it.slotId) },
                reservationToken = db.runtimeSlots().findBySession(sessionId)?.reservationToken.orEmpty(),
                runtimeGeneration = db.runtimeSlots().findBySession(sessionId)?.runtimeGeneration ?: RuntimeHostRegistry.runtimeGeneration,
                slotEpoch = db.runtimeSlots().findBySession(sessionId)?.slotEpoch ?: 0L,
            )
        }
        val row = (reusable ?: VirtualRuntimeSessionEntity(sessionId, pkg.packageName, pkg.virtualUserId, "STOPPED", null, now, null, now, now, null, Process.myPid(), activity, "PENDING", "NEW", null, null)).copy(
            state = "STARTING",
            currentLaunchAttemptId = attemptId,
            startedAt = null,
            lastActivityAt = now,
            lastHeartbeatAt = now,
            stoppedAt = null,
            hostPid = Process.myPid(),
            entryPoint = activity,
            classLoaderState = "PENDING",
            launchPhase = "PREPARED",
            errorCode = null,
            sanitizedError = null,
        )
        db.runtime().upsert(row)
        val reservedSlot = RuntimeSlotRepository(db, maxActiveApps).reserve(pkg.packageName, pkg.virtualUserId, sessionId, attemptId, now) ?: error("No hay procesos virtuales libres. diagnostics=" + allocatorDiagnostics(db.runtimeSlots().getAll()))
        logLaunch("SESSION_STARTING_INSERTED", sessionId, attemptId, token, pkg.packageName, pkg.virtualUserId, before?.state, row.state)
        db.launchTokens().upsert(VirtualLaunchTokenEntity(token, sessionId, attemptId, pkg.virtualUserId, pkg.packageName, activity, now, now + TOKEN_TTL_MS, null))
        logLaunch("TOKEN_INSERTED", sessionId, attemptId, token, pkg.packageName, pkg.virtualUserId, null, null)
        val reservedEntity = db.runtimeSlots().get(reservedSlot.slotId.name) ?: error("Reserva perdida después de SLOT_RESERVED")
        PreparedLaunch(sessionId, attemptId, token, true, reservedSlot.slotId, reservedEntity.reservationToken.orEmpty(), reservedEntity.runtimeGeneration ?: RuntimeHostRegistry.runtimeGeneration, reservedEntity.slotEpoch)
    }
    }

    suspend fun reconcileActiveSlots(now: Long = System.currentTimeMillis()) {
        db.runtimeSlots().getAll().forEach { slot ->
            val sid = slot.sessionId ?: return@forEach
            val session = db.runtime().get(sid) ?: return@forEach
            val pid = slot.hostPid
            val activeSlot = slot.state == "ACTIVE_FOREGROUND" || slot.state == "ACTIVE_BACKGROUND"
            val alive = pid != null && runCatching { Os.kill(pid, 0); true }.getOrDefault(false)
            val fresh = slot.lastHeartbeatElapsedRealtime?.let { it >= SystemClock.elapsedRealtime() - START_TIMEOUT_MS } ?: ((slot.lastHeartbeatAt ?: 0L) >= now - START_TIMEOUT_MS)
            if (activeSlot && alive && fresh) {
                logLaunch("STATE_RECONCILIATION_BEGIN", sid, slot.launchAttemptId, null, slot.packageName, slot.virtualUserId, session.state, slot.state)
                if (session.currentLaunchAttemptId == slot.launchAttemptId) {
                    db.withTransaction { db.runtime().repairActive(sid, now, SystemClock.elapsedRealtime(), pid!!) }
                    logLaunch("STATE_RECONCILIATION_SUCCESS", sid, slot.launchAttemptId, null, slot.packageName, slot.virtualUserId, session.state, "ACTIVE")
                } else logLaunch("STATE_RECONCILIATION_REJECTED", sid, slot.launchAttemptId, null, slot.packageName, slot.virtualUserId, session.currentLaunchAttemptId, slot.launchAttemptId)
            }
        }
    }
    suspend fun reconcileStartup(now: Long = System.currentTimeMillis()) { reconcileActiveSlots(now); RuntimeRecoveryManager(db).recover(now) }
}

data class WatchdogDiagnostics(val active: Boolean = false, val lastTickAt: Long = 0, val startingFound: Int = 0, val deadline: Long = 0, val lastHeartbeatAgeMs: Long? = null, val dbInstanceId: String = "unknown", val internalError: String? = null)

class RuntimeSessionController(private val repository: RuntimeSessionRepository, private val db: ValcronoDatabase, private val metrics: RuntimeMetricsRepository) {
    @Volatile var diagnostics: WatchdogDiagnostics = WatchdogDiagnostics(dbInstanceId = System.identityHashCode(db).toString(16)); private set
    suspend fun prepareLaunch(pkg: VirtualPackageEntity, activity: String, maxActiveApps: Int = 2): PreparedLaunch = repository.prepareLaunch(pkg, activity, maxActiveApps)
    suspend fun stop(packageName: String, userId: Int) { db.runtime().forPackage(packageName, userId)?.let { row -> val now = System.currentTimeMillis(); db.runtimeSlots().findBySession(row.sessionId)?.let { RuntimeSlotReclaimer(db).reclaimSlot(it.slotId, row.sessionId, it.launchAttemptId, it.reservationToken, RuntimeReclaimReason.STOPPED, now) } ?: row.currentLaunchAttemptId?.let { a -> db.runtime().compareAndSetState(row.sessionId, a, "STOPPED", "STOPPED", now, null, null); db.launchTokens().revokeAttempt(row.sessionId, a, now) }; metrics.removeMetrics(row.sessionId) } }
    suspend fun cancelStarting(sessionId: String) { db.runtime().get(sessionId)?.currentLaunchAttemptId?.let { val now = System.currentTimeMillis(); db.runtime().compareAndSetState(sessionId, it, "ERROR", "CANCELLED", now, "LAUNCH_CANCELLED", "Inicio cancelado por el usuario."); db.launchTokens().revokeAttempt(sessionId, it, now); db.runtimeSlots().findBySession(sessionId)?.let { slot -> RuntimeSlotReclaimer(db).reclaimSlot(slot.slotId, sessionId, slot.launchAttemptId, slot.reservationToken, RuntimeReclaimReason.CANCELLED, now) }; metrics.removeMetrics(sessionId) } }
    suspend fun watchdogTick(timeoutMs: Long = START_TIMEOUT_MS) {
        RuntimeHostRegistry.awaitReady()
        val now = System.currentTimeMillis(); val deadline = now - timeoutMs
        RuntimeSlotReclaimer(db).reconcileRuntimeState(now); repository.reconcileActiveSlots(now); val stale = db.runtime().staleStarting(deadline)
        diagnostics = WatchdogDiagnostics(true, now, stale.size, deadline, stale.minOfOrNull { now - it.lastHeartbeatAt }, System.identityHashCode(db).toString(16), null)
        logLaunch("WATCHDOG_TICK", null, null, null, null, null, "deadline=$deadline rows=${stale.size}", "db=${diagnostics.dbInstanceId}")
        stale.forEach { row ->
            val attempt = row.currentLaunchAttemptId ?: return@forEach
            logLaunch("WATCHDOG_CHECK", row.sessionId, attempt, null, row.packageName, row.virtualUserId, row.state, row.state)
            val changed = db.runtime().timeoutLaunch(row.sessionId, attempt, deadline, now)
            if (changed > 0) { logLaunch("WATCHDOG_TIMEOUT", row.sessionId, attempt, null, row.packageName, row.virtualUserId, "STARTING", "ERROR"); db.launchTokens().revokeAttempt(row.sessionId, attempt, now); db.runtimeSlots().findBySession(row.sessionId)?.let { RuntimeSlotReclaimer(db).reclaimSlot(it.slotId, row.sessionId, it.launchAttemptId, it.reservationToken, RuntimeReclaimReason.STARTUP_TIMEOUT, now) }; metrics.removeMetrics(row.sessionId) }
        }
        db.launchTokens().cleanup(now, now - 24 * 60 * 60 * 1000L)
    }
}

private fun allocatorDiagnostics(slots: List<RuntimeSlotEntity>): String = slots.joinToString { slot ->
    val reservable = slot.state == "FREE" && slot.sessionId == null && slot.packageName == null && slot.hostPid == null
    "${slot.slotId}{slotState=${slot.state},occupied=${slot.sessionId != null},reservable=$reservable,sessionId=${slot.sessionId},pid=${slot.hostPid},reservationOwner=${slot.packageName},launchAttemptId=${slot.launchAttemptId},reclaimInProgress=${slot.reclaimInProgress}}"
}

class RuntimeMetricsRepository(private val tracker: RuntimeResourceTracker) {
    fun replaceMetricsForSessions(rows: List<VirtualRuntimeSessionEntity>) = tracker.replaceMetricsForSessions(rows)
    fun removeMetrics(sessionId: String) = tracker.remove(sessionId)
}

fun logLaunch(event: String, sessionId: String?, attemptId: String?, token: String?, packageName: String?, userId: Int?, before: String?, after: String?) {
    VLog.i("RuntimeLaunch", "$event sessionId=${sessionId?.take(8)} launchAttemptId=${attemptId?.take(8)} tokenPrefix=${token?.take(8)} packageName=$packageName virtualUserId=$userId stateBefore=$before stateAfter=$after timestamp=${System.currentTimeMillis()} thread=${Thread.currentThread().name} hostPid=${Process.myPid()}")
}
