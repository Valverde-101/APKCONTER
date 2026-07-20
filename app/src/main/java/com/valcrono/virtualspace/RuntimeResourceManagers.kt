package com.valcrono.virtualspace

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.os.Process
import com.valcrono.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class RuntimeResourceSnapshot(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val hostPssBytes: Long,
    val javaHeapUsedBytes: Long,
    val nativeHeapBytes: Long,
    val activeSessions: Int,
    val classLoaders: Int,
    val openSqliteDatabases: Int,
    val runningTasks: Int,
    val memoryThresholdBytes: Long,
    val systemLowMemory: Boolean,
    val memoryPressureState: MemoryPressureState,
    val consecutiveCriticalSamples: Int = 0,
)

data class ManagedSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val packageName: String,
    val label: String,
    val state: SessionState = SessionState.STOPPED,
    val startedAt: Long = System.currentTimeMillis(),
    val lastActivityAt: Long = System.currentTimeMillis(),
    val estimatedBytes: Long = 0,
    val basesOpen: Int = 0,
    val pendingMessages: Int = 0,
    val isSaving: Boolean = false,
)

class RuntimeResourceTracker(private val context: Context) {
    private val sessions = ConcurrentHashMap<String, ManagedSession>()
    private val _snapshot = MutableStateFlow(snapshot(emptyList()))
    val snapshot: StateFlow<RuntimeResourceSnapshot> = _snapshot

    fun upsert(session: ManagedSession) { sessions[session.sessionId] = session; refresh() }
    fun remove(sessionId: String) { sessions.remove(sessionId); refresh() }
    fun replaceMetricsForSessions(rows: List<VirtualRuntimeSessionEntity>) {
        val validIds = rows.map { it.sessionId }.toSet()
        sessions.keys.removeIf { it !in validIds }
        rows.forEach { row ->
            val previous = sessions[row.sessionId]
            sessions[row.sessionId] = ManagedSession(
                sessionId = row.sessionId,
                packageName = row.packageName,
                label = previous?.label ?: row.packageName,
                state = runCatching { SessionState.valueOf(row.state) }.getOrDefault(SessionState.ERROR),
                startedAt = row.startedAt ?: row.createdAt,
                lastActivityAt = row.lastActivityAt,
                estimatedBytes = previous?.estimatedBytes ?: 0L,
                basesOpen = previous?.basesOpen ?: 0,
                pendingMessages = previous?.pendingMessages ?: 0,
                isSaving = previous?.isSaving ?: false,
            )
        }
        refresh()
    }
    fun stop(sessionId: String) { sessions[sessionId]?.let { sessions[sessionId] = it.copy(state = SessionState.STOPPED, lastActivityAt = System.currentTimeMillis()) }; refresh() }
    fun stopAllPaused() { sessions.values.filter { it.state == SessionState.PAUSED && !it.isSaving }.forEach { stop(it.sessionId) } }
    fun allSessions(): List<ManagedSession> = sessions.values.sortedByDescending { it.lastActivityAt }
    fun clearStopped() { sessions.values.removeIf { it.state == SessionState.STOPPED }; refresh() }
    fun refresh() { _snapshot.value = snapshot(sessions.values.toList()) }

    private fun snapshot(currentSessions: List<ManagedSession>): RuntimeResourceSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pss = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()?.totalPss?.toLong()?.times(1024) ?: 0L
        return RuntimeResourceSnapshot(
            totalRamBytes = mi.totalMem,
            availableRamBytes = mi.availMem,
            hostPssBytes = pss,
            javaHeapUsedBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            activeSessions = currentSessions.count { it.state == SessionState.ACTIVE },
            classLoaders = currentSessions.count { it.state == SessionState.ACTIVE || it.state == SessionState.PAUSED },
            openSqliteDatabases = currentSessions.sumOf { it.basesOpen },
            runningTasks = 0,
            memoryThresholdBytes = mi.threshold,
            systemLowMemory = mi.lowMemory,
            memoryPressureState = MemoryBudgetManager().pressureState(MemoryInputs(mi.totalMem, mi.availMem, am.memoryClass, am.largeMemoryClass, am.isLowRamDevice, pss, currentSessions.count { it.state == SessionState.ACTIVE }, systemThresholdBytes = mi.threshold, systemLowMemory = mi.lowMemory)),
        )
    }
}

class VirtualSessionManager(
    private val tracker: RuntimeResourceTracker,
    private val budgetManager: MemoryBudgetManager = MemoryBudgetManager(),
    private val evictionPolicy: ProcessEvictionPolicy = ProcessEvictionPolicy(),
) {
    private val actions = MutableStateFlow<List<String>>(emptyList())
    private var consecutiveCriticalSamples = 0
    fun actions(): StateFlow<List<String>> = actions

    fun enforce(profile: MemoryProfile, inputs: MemoryInputs) {
        val pressure = budgetManager.pressureState(inputs, consecutiveCriticalSamples)
        consecutiveCriticalSamples = if (pressure == MemoryPressureState.CRITICAL || inputs.systemLowMemory) consecutiveCriticalSamples + 1 else 0
        val confirmed = inputs.systemLowMemory && consecutiveCriticalSamples >= 3
        val finalPressure = budgetManager.pressureState(inputs, consecutiveCriticalSamples, androidConfirmedScarcity = confirmed && profile == MemoryProfile.CONSERVATIVE)
        val budget = budgetManager.budget(profile, inputs)
        val sessions = tracker.allSessions().map { VirtualSessionSnapshot(it.sessionId, it.packageName, it.state, it.lastActivityAt, it.estimatedBytes, it.isSaving) }
        val decision = evictionPolicy.enforce(budget, sessions, inputs.processPssBytes, finalPressure)
        decision.stopSessionIds.forEach { tracker.stop(it) }
        actions.value = decision.actions + "Política=$finalPressure threshold=${inputs.systemThresholdBytes} avail=${inputs.availableRamBytes} lowMemory=${inputs.systemLowMemory}"
    }

    fun onTrimMemory(level: Int) {
        val policy = budgetManager.trimPolicy(level)
        actions.value = actions.value + "onTrimMemory($level): $policy"
        when (policy) {
            TrimPolicy.RELEASE_VISUALS, TrimPolicy.REDUCE_CACHES, TrimPolicy.COOPERATIVE_TRIM, TrimPolicy.DIAGNOSTIC_GC -> tracker.clearStopped()
        }
    }

    fun onLowMemory() { actions.value = actions.value + "onLowMemory: trim seguro; no se detienen sesiones activas"; tracker.clearStopped() }
}
