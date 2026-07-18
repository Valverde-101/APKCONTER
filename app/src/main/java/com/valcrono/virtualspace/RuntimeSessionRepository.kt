package com.valcrono.virtualspace

import android.os.Process
import androidx.room.withTransaction
import com.valcrono.core.VLog
import kotlinx.coroutines.flow.Flow
import java.util.UUID

private const val TOKEN_TTL_MS = 5 * 60 * 1000L
const val START_TIMEOUT_MS: Long = 30_000L

data class PreparedLaunch(val sessionId: String, val launchAttemptId: String, val launchToken: String, val shouldStartActivity: Boolean, val slotId: RuntimeSlotId?)

class RuntimeSessionRepository(private val db: ValcronoDatabase) {
    fun observeSessions(): Flow<List<VirtualRuntimeSessionEntity>> = db.runtime().observeSessions()

    suspend fun prepareLaunch(pkg: VirtualPackageEntity, activity: String, now: Long = System.currentTimeMillis()): PreparedLaunch = db.withTransaction {
        val before = db.runtime().forPackage(pkg.packageName, pkg.virtualUserId)
        val sessionId = before?.sessionId ?: UUID.randomUUID().toString()
        val attemptId = if (before?.state == "STARTING" && before.currentLaunchAttemptId != null) before.currentLaunchAttemptId else UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        logLaunch("SESSION_PREPARE", sessionId, attemptId, token, pkg.packageName, pkg.virtualUserId, before?.state, "STARTING")
        if (before?.state in setOf("ACTIVE", "ACTIVE_FOREGROUND", "ACTIVE_BACKGROUND")) return@withTransaction PreparedLaunch(sessionId, before.currentLaunchAttemptId.orEmpty(), token, false, db.runtimeSlots().findBySession(sessionId)?.let { RuntimeSlotId.valueOf(it.slotId) })
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
        val reservedSlot = RuntimeSlotRepository(db).reserve(pkg.packageName, pkg.virtualUserId, sessionId, attemptId, now) ?: error("No hay procesos virtuales libres.")
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
    suspend fun prepareLaunch(pkg: VirtualPackageEntity, activity: String): PreparedLaunch = repository.prepareLaunch(pkg, activity)
    suspend fun stop(packageName: String, userId: Int) { db.runtime().forPackage(packageName, userId)?.let { it.currentLaunchAttemptId?.let { a -> db.runtime().compareAndSetState(it.sessionId, a, "STOPPED", "STOPPED", System.currentTimeMillis(), null, null) }; metrics.removeMetrics(it.sessionId) } }
    suspend fun cancelStarting(sessionId: String) { db.runtime().get(sessionId)?.currentLaunchAttemptId?.let { db.runtime().compareAndSetState(sessionId, it, "ERROR", "CANCELLED", System.currentTimeMillis(), "LAUNCH_CANCELLED", "Inicio cancelado por el usuario.") ; metrics.removeMetrics(sessionId) } }
    suspend fun watchdogTick(timeoutMs: Long = START_TIMEOUT_MS) {
        val now = System.currentTimeMillis(); val deadline = now - timeoutMs
        val stale = db.runtime().staleStarting(deadline)
        diagnostics = WatchdogDiagnostics(true, now, stale.size, deadline, stale.minOfOrNull { now - it.lastHeartbeatAt }, System.identityHashCode(db).toString(16), null)
        logLaunch("WATCHDOG_TICK", null, null, null, null, null, "deadline=$deadline rows=${stale.size}", "db=${diagnostics.dbInstanceId}")
        stale.forEach { row ->
            val attempt = row.currentLaunchAttemptId ?: return@forEach
            logLaunch("WATCHDOG_CHECK", row.sessionId, attempt, null, row.packageName, row.virtualUserId, row.state, row.state)
            val changed = db.runtime().timeoutLaunch(row.sessionId, attempt, deadline, now)
            if (changed > 0) { logLaunch("WATCHDOG_TIMEOUT", row.sessionId, attempt, null, row.packageName, row.virtualUserId, "STARTING", "ERROR"); db.launchTokens().revokeAttempt(row.sessionId, attempt, now); metrics.removeMetrics(row.sessionId) }
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
