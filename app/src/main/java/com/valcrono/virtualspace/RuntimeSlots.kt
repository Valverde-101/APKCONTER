package com.valcrono.virtualspace

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process

enum class RuntimeSlotState { FREE, RESERVED, STARTING, ACTIVE, PAUSED, STOPPING, CRASHED }
enum class RuntimeSlotId(val processName: String) { VAPP0(":vapp0"), VAPP1(":vapp1") }

data class RuntimeSlot(
    val slotId: RuntimeSlotId,
    val processName: String = slotId.processName,
    val hostPid: Int? = null,
    val packageName: String? = null,
    val virtualUserId: Int = 0,
    val sessionId: String? = null,
    val launchAttemptId: String? = null,
    val state: RuntimeSlotState = RuntimeSlotState.FREE,
    val assignedAt: Long? = null,
    val lastHeartbeatAt: Long? = null,
    val pssBytes: Long? = null,
    val errorCode: String? = null,
)

data class RuntimeSlotAssignment(val slot: RuntimeSlot, val token: String, val virtualPaths: List<String> = emptyList())

class RuntimeSlotRepository {
    private val slots = linkedMapOf(RuntimeSlotId.VAPP0 to RuntimeSlot(RuntimeSlotId.VAPP0), RuntimeSlotId.VAPP1 to RuntimeSlot(RuntimeSlotId.VAPP1))
    @Synchronized fun snapshot(): List<RuntimeSlot> = slots.values.toList()
    @Synchronized fun reserve(packageName: String, virtualUserId: Int, sessionId: String, launchAttemptId: String, maxActiveApps: Int = 2): RuntimeSlot? {
        slots.values.firstOrNull { it.packageName == packageName && it.virtualUserId == virtualUserId && it.state in setOf(RuntimeSlotState.ACTIVE, RuntimeSlotState.PAUSED, RuntimeSlotState.STARTING) }?.let { return it }
        val enabled = RuntimeSlotId.entries.take(maxActiveApps.coerceIn(1, 2)).toSet()
        val free = slots.values.firstOrNull { it.slotId in enabled && it.state == RuntimeSlotState.FREE } ?: return null
        val reserved = free.copy(packageName = packageName, virtualUserId = virtualUserId, sessionId = sessionId, launchAttemptId = launchAttemptId, state = RuntimeSlotState.RESERVED, assignedAt = System.currentTimeMillis())
        slots[free.slotId] = reserved; return reserved
    }
    @Synchronized fun heartbeat(slotId: RuntimeSlotId, pid: Int = Process.myPid(), pssBytes: Long? = null) { slots[slotId]?.let { slots[slotId] = it.copy(hostPid = pid, state = RuntimeSlotState.ACTIVE, lastHeartbeatAt = System.currentTimeMillis(), pssBytes = pssBytes) } }
    @Synchronized fun release(slotId: RuntimeSlotId) { slots[slotId] = RuntimeSlot(slotId) }
    @Synchronized fun crash(slotId: RuntimeSlotId, code: String) { slots[slotId]?.let { slots[slotId] = it.copy(state = RuntimeSlotState.CRASHED, errorCode = code) } }
}

class RuntimeSlotManager(private val repository: RuntimeSlotRepository = RuntimeSlotRepository()) {
    fun open(packageName: String, virtualUserId: Int, sessionId: String, launchAttemptId: String, maxActiveApps: Int): RuntimeSlot = repository.reserve(packageName, virtualUserId, sessionId, launchAttemptId, maxActiveApps) ?: error("No hay procesos virtuales libres.")
    fun slots(): List<RuntimeSlot> = repository.snapshot()
}

open class RuntimeProcessService : Service() { private val binder = LocalBinder(); inner class LocalBinder : Binder() { fun service() = this@RuntimeProcessService }; override fun onBind(intent: Intent?): IBinder = binder }
class RuntimeSlot0Service : RuntimeProcessService()
class RuntimeSlot1Service : RuntimeProcessService()
class RuntimeProcessClient
