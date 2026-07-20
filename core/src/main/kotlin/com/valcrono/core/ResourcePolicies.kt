package com.valcrono.core

import kotlin.math.max
import kotlin.math.min

enum class MemoryProfile { AUTOMATIC, MAXIMUM_PERSISTENCE, CONSERVATIVE, LOW_POWER, BALANCED, HIGH_PERFORMANCE, CUSTOM }
enum class OpenAnotherAppPolicy { ASK, PAUSE_CURRENT, STOP_CURRENT, BLOCK }
enum class TrimPolicy { RELEASE_VISUALS, REDUCE_CACHES, COOPERATIVE_TRIM, DIAGNOSTIC_GC }
enum class MemoryPressureState { NORMAL, ELEVATED, CRITICAL, EMERGENCY }
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
    val systemThresholdBytes: Long = 0,
    val systemLowMemory: Boolean = false,
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
            MemoryProfile.MAXIMUM_PERSISTENCE -> MemoryBudget(profile, safeCeiling, 2, 2, base / 6, Long.MAX_VALUE, inputs.systemThresholdBytes, OpenAnotherAppPolicy.PAUSE_CURRENT, "Máxima permanencia: no se aplica eviction interna mientras Android no reporte lowMemory.")
            MemoryProfile.AUTOMATIC -> MemoryBudget(profile, base, 2, 2, base / 6, Long.MAX_VALUE, inputs.systemThresholdBytes, OpenAnotherAppPolicy.PAUSE_CURRENT, "Automática: usa threshold/lowMemory del sistema y conserva dos sesiones salvo presión crítica persistente.")
            MemoryProfile.CONSERVATIVE -> MemoryBudget(profile, base, 2, 1, base / 8, Long.MAX_VALUE, inputs.systemThresholdBytes, OpenAnotherAppPolicy.PAUSE_CURRENT, "Conservadora: permite cerrar LRU solo ante presión real confirmada.")
            MemoryProfile.LOW_POWER -> MemoryBudget(profile, base, 2, 1, 24L * MB, Long.MAX_VALUE, inputs.systemThresholdBytes, OpenAnotherAppPolicy.PAUSE_CURRENT)
            MemoryProfile.BALANCED -> MemoryBudget(profile, base, 2, 2, 64L * MB, Long.MAX_VALUE, inputs.systemThresholdBytes, OpenAnotherAppPolicy.PAUSE_CURRENT)
            MemoryProfile.HIGH_PERFORMANCE -> MemoryBudget(profile, safeCeiling, 2, 2, 128L * MB, Long.MAX_VALUE, inputs.systemThresholdBytes, OpenAnotherAppPolicy.PAUSE_CURRENT)
            MemoryProfile.CUSTOM -> MemoryBudget(profile, min(customTargetBytes ?: base, safeCeiling), 2, 2, min(base / 3, 256L * MB), Long.MAX_VALUE, inputs.systemThresholdBytes, OpenAnotherAppPolicy.ASK)
        }
    }

    fun pressureState(inputs: MemoryInputs, consecutiveCriticalSamples: Int = 0, androidConfirmedScarcity: Boolean = false): MemoryPressureState = when {
        androidConfirmedScarcity && (inputs.systemLowMemory || inputs.availableRamBytes <= inputs.systemThresholdBytes) && consecutiveCriticalSamples >= 3 -> MemoryPressureState.EMERGENCY
        inputs.systemLowMemory || (inputs.systemThresholdBytes > 0 && inputs.availableRamBytes <= inputs.systemThresholdBytes && consecutiveCriticalSamples >= 3) -> MemoryPressureState.CRITICAL
        inputs.systemThresholdBytes > 0 && inputs.availableRamBytes <= inputs.systemThresholdBytes * 2 -> MemoryPressureState.ELEVATED
        else -> MemoryPressureState.NORMAL
    }

    fun trimPolicy(level: Int): TrimPolicy = when {
        level >= 80 -> TrimPolicy.COOPERATIVE_TRIM
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
    fun enforce(budget: MemoryBudget, sessions: List<VirtualSessionSnapshot>, usedBytes: Long, pressureState: MemoryPressureState = MemoryPressureState.NORMAL): EvictionDecision {
        val actions = mutableListOf("Actualizar métricas PSS/RSS diagnósticas", "Liberar cachés reconstruibles")
        if (pressureState != MemoryPressureState.EMERGENCY || budget.profile == MemoryProfile.MAXIMUM_PERSISTENCE || sessions.size <= 1) return EvictionDecision(actions, emptyList())
        val victim = sessions.filter { it.state != SessionState.ACTIVE && !it.isSaving }.minByOrNull { it.lastActivityAt }
        return if (victim != null) EvictionDecision(actions + "Eviction interna confirmada por presión real: ${victim.packageName}", listOf(victim.sessionId)) else EvictionDecision(actions + "Sin víctima segura para eviction", emptyList())
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
