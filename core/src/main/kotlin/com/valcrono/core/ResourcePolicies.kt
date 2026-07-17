package com.valcrono.core

import kotlin.math.max
import kotlin.math.min

enum class MemoryProfile { AUTOMATIC, LOW_POWER, BALANCED, HIGH_PERFORMANCE, CUSTOM }
enum class OpenAnotherAppPolicy { ASK, PAUSE_CURRENT, STOP_CURRENT, BLOCK }
enum class TrimPolicy { RELEASE_VISUALS, REDUCE_CACHES, STOP_PAUSED, STOP_NON_ESSENTIAL }
enum class SessionState { STOPPED, STARTING, ACTIVE, PAUSED, STOPPING, ERROR, SAVING }
enum class SettingAvailability { AVAILABLE, EXPERIMENTAL, NOT_IMPLEMENTED }

data class MemoryInputs(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val memoryClassMb: Int,
    val largeMemoryClassMb: Int,
    val isLowRamDevice: Boolean,
    val processPssBytes: Long,
    val activeSessions: Int,
    val trimLevel: Int = 0,
)

data class MemoryBudget(
    val profile: MemoryProfile,
    val targetHostBytes: Long,
    val maxActiveApps: Int,
    val maxPausedSessions: Int,
    val maxCacheBytes: Long,
    val inactiveTimeoutMs: Long,
    val minAvailableBytes: Long,
    val openAnotherAppPolicy: OpenAnotherAppPolicy,
    val explanation: String = "El límite seleccionado es un objetivo de uso administrado por VirtualSpace. Android puede finalizar procesos cuando necesite memoria.",
)

class MemoryBudgetManager {
    fun budget(profile: MemoryProfile, inputs: MemoryInputs, customTargetBytes: Long? = null): MemoryBudget {
        val safeCeiling = max(64L * MB, (inputs.totalRamBytes * 0.35).toLong())
        val base = min(safeCeiling, max(64L * MB, inputs.availableRamBytes / 2))
        return when (profile) {
            MemoryProfile.AUTOMATIC -> {
                val paused = if (!inputs.isLowRamDevice && inputs.availableRamBytes > inputs.totalRamBytes * 0.30) 1 else 0
                MemoryBudget(profile, base, 1, paused, base / 6, 5 * 60_000, inputs.totalRamBytes / 10, OpenAnotherAppPolicy.PAUSE_CURRENT)
            }
            MemoryProfile.LOW_POWER -> MemoryBudget(profile, min(base, 160L * MB), 1, 0, 24L * MB, 60_000, inputs.totalRamBytes / 5, OpenAnotherAppPolicy.STOP_CURRENT)
            MemoryProfile.BALANCED -> MemoryBudget(profile, min(base, 256L * MB), 1, 0, 64L * MB, 3 * 60_000, inputs.totalRamBytes / 8, OpenAnotherAppPolicy.PAUSE_CURRENT)
            MemoryProfile.HIGH_PERFORMANCE -> MemoryBudget(profile, min(safeCeiling, max(base, 384L * MB)), 1, 1, 128L * MB, 10 * 60_000, inputs.totalRamBytes / 12, OpenAnotherAppPolicy.PAUSE_CURRENT)
            MemoryProfile.CUSTOM -> MemoryBudget(profile, min(customTargetBytes ?: base, safeCeiling), 1, if (inputs.isLowRamDevice) 0 else 1, min(base / 3, 256L * MB), 5 * 60_000, inputs.totalRamBytes / 10, OpenAnotherAppPolicy.ASK)
        }
    }

    fun trimPolicy(level: Int): TrimPolicy = when {
        level >= 80 -> TrimPolicy.STOP_NON_ESSENTIAL
        level >= 15 -> TrimPolicy.STOP_PAUSED
        level >= 10 -> TrimPolicy.REDUCE_CACHES
        else -> TrimPolicy.RELEASE_VISUALS
    }

    companion object { const val MB: Long = 1024L * 1024L }
}

data class VirtualSessionSnapshot(
    val sessionId: String,
    val packageName: String,
    val state: SessionState,
    val lastActivityAt: Long,
    val estimatedBytes: Long,
    val isSaving: Boolean = false,
)

data class EvictionDecision(val actions: List<String>, val stopSessionIds: List<String>)

class ProcessEvictionPolicy {
    fun enforce(budget: MemoryBudget, sessions: List<VirtualSessionSnapshot>, usedBytes: Long): EvictionDecision {
        val actions = mutableListOf("Liberar recursos temporales", "Vaciar cachés no esenciales")
        val stops = mutableListOf<String>()
        var projected = usedBytes
        sessions.filter { it.state == SessionState.PAUSED && !it.isSaving }.sortedBy { it.lastActivityAt }.forEach { session ->
            if (projected > budget.targetHostBytes || sessions.count { it.state == SessionState.PAUSED } - stops.size > budget.maxPausedSessions) {
                actions += "Solicitar cierre de bases y recursos: ${session.packageName}"
                actions += "Detener sesión LRU: ${session.packageName}"
                stops += session.sessionId
                projected -= session.estimatedBytes
            }
        }
        if (projected > budget.targetHostBytes) actions += "Solicitar GC como último recurso"
        return EvictionDecision(actions, stops)
    }
}

data class StorageQuota(val maxBytes: Long?)
data class StorageCheck(val allowed: Boolean, val message: String)

class StorageQuotaManager(private val safetyMarginBytes: Long = 64L * 1024L * 1024L) {
    fun check(currentBytes: Long, incomingBytes: Long, quota: StorageQuota, deviceFreeBytes: Long): StorageCheck {
        val projected = currentBytes + incomingBytes
        if (quota.maxBytes != null && projected > quota.maxBytes) return StorageCheck(false, "Cuota excedida: $projected > ${quota.maxBytes}")
        if (deviceFreeBytes - incomingBytes < safetyMarginBytes) return StorageCheck(false, "Espacio insuficiente en el dispositivo")
        return StorageCheck(true, "OK")
    }
}
