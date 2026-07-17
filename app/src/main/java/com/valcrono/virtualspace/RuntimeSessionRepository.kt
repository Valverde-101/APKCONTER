package com.valcrono.virtualspace

import android.os.Process
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

class RuntimeSessionRepository(private val db: ValcronoDatabase) {
    fun observeSessions(): Flow<List<VirtualRuntimeSessionEntity>> = db.runtime().observeSessions()

    suspend fun prepareLaunch(pkg: VirtualPackageEntity, token: String, activity: String, now: Long = System.currentTimeMillis()): VirtualRuntimeSessionEntity = db.withTransaction {
        val existing = db.runtime().forPackage(pkg.packageName, pkg.virtualUserId)
        when (existing?.state) {
            "ACTIVE", "STARTING" -> existing
            "PAUSED" -> existing.copy(state = "ACTIVE", lastActivityAt = now, startedAt = existing.startedAt ?: now, stoppedAt = null, errorCode = null, sanitizedError = null).also { db.runtime().upsert(it) }
            else -> {
                val row = VirtualRuntimeSessionEntity(
                    sessionId = existing?.sessionId ?: token,
                    packageName = pkg.packageName,
                    virtualUserId = pkg.virtualUserId,
                    state = "STARTING",
                    createdAt = now,
                    startedAt = null,
                    lastActivityAt = now,
                    stoppedAt = null,
                    hostPid = Process.myPid(),
                    entryPoint = activity,
                    classLoaderState = "PENDING",
                    errorCode = null,
                    sanitizedError = null,
                )
                db.runtime().upsert(row)
                db.runtime().deleteDuplicatesFor(pkg.packageName, pkg.virtualUserId, row.sessionId)
                db.launchTokens().upsert(VirtualLaunchTokenEntity(token, pkg.virtualUserId, pkg.packageName, activity, now, null))
                row
            }
        }
    }

    suspend fun reconcileStartup(now: Long = System.currentTimeMillis()) {
        db.runtime().markStaleStarting(now)
        db.runtime().markProcessLost(now)
    }
}

class RuntimeSessionController(private val repository: RuntimeSessionRepository, private val db: ValcronoDatabase, private val metrics: RuntimeMetricsRepository) {
    suspend fun prepareLaunch(pkg: VirtualPackageEntity, token: String, activity: String): VirtualRuntimeSessionEntity = repository.prepareLaunch(pkg, token, activity)
    suspend fun stop(packageName: String, userId: Int) { db.runtime().forPackage(packageName, userId)?.let { db.runtime().updateState(it.sessionId, "STOPPED", System.currentTimeMillis(), null, null); metrics.removeMetrics(it.sessionId) } }
    suspend fun cancelStarting(sessionId: String) { db.runtime().updateState(sessionId, "ERROR", System.currentTimeMillis(), "LAUNCH_CANCELLED", "Inicio cancelado por el usuario.") ; metrics.removeMetrics(sessionId) }
    suspend fun watchdogTick(timeoutMs: Long = 15_000) {
        val now = System.currentTimeMillis()
        val stale = db.runtime().staleStarting(now - timeoutMs)
        stale.forEach { db.launchTokens().revoke(it.sessionId, now); metrics.removeMetrics(it.sessionId) }
        db.runtime().timeoutStarting(now - timeoutMs, now)
    }
}

class RuntimeMetricsRepository(private val tracker: RuntimeResourceTracker) {
    fun replaceMetricsForSessions(rows: List<VirtualRuntimeSessionEntity>) = tracker.replaceMetricsForSessions(rows)
    fun removeMetrics(sessionId: String) = tracker.remove(sessionId)
}
