package com.valcrono.virtualspace

import android.os.Process
import android.system.Os
import androidx.room.withTransaction
import com.valcrono.core.VLog
import kotlinx.coroutines.flow.Flow
import java.util.UUID

private const val TOKEN_TTL_MS = 5 * 60 * 1000L
const val START_TIMEOUT_MS: Long = 30_000L

data class PreparedLaunch(val sessionId: String, val launchAttemptId: String, val launchToken: String, val shouldStartActivity: Boolean, val slotId: RuntimeSlotId?)

enum class RuntimeOpenMode { COLD_START, WARM_RESUME, RECOVER_SERVICE }
enum class RuntimeEffectiveState { STOPPED, STARTING, ACTIVE_FOREGROUND, ACTIVE_BACKGROUND, PAUSED, ERROR }
data class RuntimeAppUiState(val session: VirtualRuntimeSessionEntity?, val slot: RuntimeSlotEntity?, val effectiveState: RuntimeEffectiveState)

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

    suspend fun resolveOpenDecision(packageName: String, virtualUserId: Int): RuntimeOpenDecision? {
        val now = System.currentTimeMillis()
        val session = db.runtime().forPackage(packageName, virtualUserId) ?: return null
        val slot = db.runtimeSlots().findBySession(session.sessionId) ?: return null
        val pid = slot.hostPid
        val processAlive = pid != null && runCatching { Os.kill(pid, 0); true }.getOrDefault(false)
        val heartbeatFresh = (slot.lastHeartbeatAt ?: session.lastHeartbeatAt) >= now - START_TIMEOUT_MS
        val slotActive = slot.state == "ACTIVE_FOREGROUND" || slot.state == "ACTIVE_BACKGROUND"
        val classLoaderLoaded = session.classLoaderState == "LOADED"
        if (session.state == "STARTING" && slotActive && processAlive && heartbeatFresh && classLoaderLoaded) {
            db.runtime().repairActive(session.sessionId, now, pid!!)
            logLaunch("STATE_RECONCILED", session.sessionId, session.currentLaunchAttemptId, null, packageName, virtualUserId, "STARTING/${slot.state}", "ACTIVE/${slot.state}")
        }
        val mode = when {
            (session.state == "ACTIVE" || (session.state == "STARTING" && slotActive)) && slotActive && processAlive && heartbeatFresh && classLoaderLoaded -> RuntimeOpenMode.WARM_RESUME
            pid != null && processAlive && heartbeatFresh -> RuntimeOpenMode.RECOVER_SERVICE
            else -> RuntimeOpenMode.COLD_START
        }
        return RuntimeOpenDecision(mode, session.sessionId, RuntimeSlotId.valueOf(slot.slotId), slot.taskId, processAlive, heartbeatFresh, slot.activityLastAttachedAt != null)
    }

    suspend fun prepareLaunch(pkg: VirtualPackageEntity, activity: String, maxActiveApps: Int = 2, now: Long = System.currentTimeMillis()): PreparedLaunch = db.withTransaction {
        val before = db.runtime().forPackage(pkg.packageName, pkg.virtualUserId)
        val sessionId = before?.sessionId ?: UUID.randomUUID().toString()
        val existingStartingAttemptId = before?.takeIf { it.state == "STARTING" }?.currentLaunchAttemptId
        val attemptId = existingStartingAttemptId ?: UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        logLaunch("SESSION_PREPARE", sessionId, attemptId, token, pkg.packageName, pkg.virtualUserId, before?.state, "STARTING")
        val existingActive = before?.takeIf { it.state in setOf("ACTIVE", "ACTIVE_FOREGROUND", "ACTIVE_BACKGROUND") }
        if (existingActive != null) {
            return@withTransaction PreparedLaunch(
                sessionId = sessionId,
                launchAttemptId = existingActive.currentLaunchAttemptId.orEmpty(),
                launchToken = token,
                shouldStartActivity = false,
                slotId = db.runtimeSlots().findBySession(sessionId)?.let { RuntimeSlotId.valueOf(it.slotId) },
            )
        }
        val row = (before ?: VirtualRuntimeSessionEntity(sessionId, pkg.packageName, pkg.virtualUserId, "STOPPED", null, now, null, now, now, null, Process.myPid(), activity, "PENDING", "NEW", null, null)).copy(
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
        val reservedSlot = RuntimeSlotRepository(db, maxActiveApps).reserve(pkg.packageName, pkg.virtualUserId, sessionId, attemptId, now) ?: error("No hay procesos virtuales libres.")
        logLaunch("SESSION_STARTING_INSERTED", sessionId, attemptId, token, pkg.packageName, pkg.virtualUserId, before?.state, row.state)
        db.launchTokens().upsert(VirtualLaunchTokenEntity(token, sessionId, attemptId, pkg.virtualUserId, pkg.packageName, activity, now, now + TOKEN_TTL_MS, null))
        logLaunch("TOKEN_INSERTED", sessionId, attemptId, token, pkg.packageName, pkg.virtualUserId, null, null)
        PreparedLaunch(sessionId, attemptId, token, true, reservedSlot.slotId)
    }

    suspend fun reconcileStartup(now: Long = System.currentTimeMillis()) { RuntimeRecoveryManager(db).recover(now) }
}

data class WatchdogDiagnostics(val active: Boolean = false, val lastTickAt: Long = 0, val startingFound: Int = 0, val deadline: Long = 0, val lastHeartbeatAgeMs: Long? = null, val dbInstanceId: String = "unknown", val internalError: String? = null)

class RuntimeSessionController(private val repository: RuntimeSessionRepository, private val db: ValcronoDatabase, private val metrics: RuntimeMetricsRepository) {
    @Volatile var diagnostics: WatchdogDiagnostics = WatchdogDiagnostics(dbInstanceId = System.identityHashCode(db).toString(16)); private set
    suspend fun prepareLaunch(pkg: VirtualPackageEntity, activity: String, maxActiveApps: Int = 2): PreparedLaunch = repository.prepareLaunch(pkg, activity, maxActiveApps)
    suspend fun stop(packageName: String, userId: Int) { db.runtime().forPackage(packageName, userId)?.let { row -> val now = System.currentTimeMillis(); row.currentLaunchAttemptId?.let { a -> db.runtime().compareAndSetState(row.sessionId, a, "STOPPED", "STOPPED", now, null, null); db.launchTokens().revokeAttempt(row.sessionId, a, now) }; db.runtimeSlots().findBySession(row.sessionId)?.let { db.runtimeSlots().release(it.slotId, now) }; metrics.removeMetrics(row.sessionId) } }
    suspend fun cancelStarting(sessionId: String) { db.runtime().get(sessionId)?.currentLaunchAttemptId?.let { val now = System.currentTimeMillis(); db.runtime().compareAndSetState(sessionId, it, "ERROR", "CANCELLED", now, "LAUNCH_CANCELLED", "Inicio cancelado por el usuario."); db.launchTokens().revokeAttempt(sessionId, it, now); db.runtimeSlots().findBySession(sessionId)?.let { slot -> db.runtimeSlots().release(slot.slotId, now) }; metrics.removeMetrics(sessionId) } }
    suspend fun watchdogTick(timeoutMs: Long = START_TIMEOUT_MS) {
        val now = System.currentTimeMillis(); val deadline = now - timeoutMs
        val stale = db.runtime().staleStarting(deadline)
        diagnostics = WatchdogDiagnostics(true, now, stale.size, deadline, stale.minOfOrNull { now - it.lastHeartbeatAt }, System.identityHashCode(db).toString(16), null)
        logLaunch("WATCHDOG_TICK", null, null, null, null, null, "deadline=$deadline rows=${stale.size}", "db=${diagnostics.dbInstanceId}")
        stale.forEach { row ->
            val attempt = row.currentLaunchAttemptId ?: return@forEach
            logLaunch("WATCHDOG_CHECK", row.sessionId, attempt, null, row.packageName, row.virtualUserId, row.state, row.state)
            val changed = db.runtime().timeoutLaunch(row.sessionId, attempt, deadline, now)
            if (changed > 0) { logLaunch("WATCHDOG_TIMEOUT", row.sessionId, attempt, null, row.packageName, row.virtualUserId, "STARTING", "ERROR"); db.launchTokens().revokeAttempt(row.sessionId, attempt, now); db.runtimeSlots().findBySession(row.sessionId)?.let { db.runtimeSlots().release(it.slotId, now) }; metrics.removeMetrics(row.sessionId) }
        }
        db.launchTokens().cleanup(now, now - 24 * 60 * 60 * 1000L)
    }
}

class RuntimeMetricsRepository(private val tracker: RuntimeResourceTracker) {
    fun replaceMetricsForSessions(rows: List<VirtualRuntimeSessionEntity>) = tracker.replaceMetricsForSessions(rows)
    fun removeMetrics(sessionId: String) = tracker.remove(sessionId)
}

fun logLaunch(event: String, sessionId: String?, attemptId: String?, token: String?, packageName: String?, userId: Int?, before: String?, after: String?) {
    VLog.i("RuntimeLaunch", "$event sessionId=${sessionId?.take(8)} launchAttemptId=${attemptId?.take(8)} tokenPrefix=${token?.take(8)} packageName=$packageName virtualUserId=$userId stateBefore=$before stateAfter=$after timestamp=${System.currentTimeMillis()} thread=${Thread.currentThread().name} hostPid=${Process.myPid()}")
}
