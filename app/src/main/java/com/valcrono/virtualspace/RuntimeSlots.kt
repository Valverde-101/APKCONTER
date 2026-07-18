package com.valcrono.virtualspace

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Debug
import android.os.IBinder
import android.os.Process
import androidx.room.withTransaction

/** Persistent two-process runtime slot model. Main process owns assignment; :vapp0/:vapp1 own Dex loading. */
enum class RuntimeSlotState { FREE, RESERVED, STARTING, ACTIVE_FOREGROUND, ACTIVE_BACKGROUND, PAUSED_BY_USER, STOPPING, STOPPED, CRASHED, ERROR }
enum class RuntimeSlotId(val processName: String) { VAPP0(":vapp0"), VAPP1(":vapp1") }

data class RuntimeSlot(
    val slotId: RuntimeSlotId,
    val processName: String = slotId.processName,
    val hostPid: Int? = null,
    val packageName: String? = null,
    val virtualUserId: Int? = null,
    val sessionId: String? = null,
    val launchAttemptId: String? = null,
    val state: RuntimeSlotState = RuntimeSlotState.FREE,
    val assignedAt: Long? = null,
    val startedAt: Long? = null,
    val lastHeartbeatAt: Long? = null,
    val stoppedAt: Long? = null,
    val pssBytes: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

data class RuntimeSlotAssignment(val slot: RuntimeSlot, val token: String, val virtualPaths: List<String> = emptyList())

data class SlotMemorySnapshot(
    val pid: Int,
    val totalPssKb: Int,
    val dalvikPssKb: Int,
    val nativePssKb: Int,
    val otherPssKb: Int,
    val javaHeapUsedBytes: Long,
    val nativeHeapAllocatedBytes: Long,
    val timestamp: Long,
) { val pssBytes: Long get() = totalPssKb * 1024L }

fun currentSlotMemorySnapshot(now: Long = System.currentTimeMillis()): SlotMemorySnapshot {
    val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
    val rt = Runtime.getRuntime()
    return SlotMemorySnapshot(Process.myPid(), info.totalPss, info.dalvikPss, info.nativePss, info.otherPss, rt.totalMemory() - rt.freeMemory(), Debug.getNativeHeapAllocatedSize(), now)
}

fun RuntimeSlotEntity.toModel(): RuntimeSlot = RuntimeSlot(
    RuntimeSlotId.valueOf(slotId), processName, hostPid, packageName, virtualUserId, sessionId, launchAttemptId,
    runCatching { RuntimeSlotState.valueOf(state) }.getOrDefault(RuntimeSlotState.ERROR), assignedAt, startedAt, lastHeartbeatAt, stoppedAt, pssBytes, errorCode, errorMessage,
)

fun proxyActivityFor(slotId: RuntimeSlotId): Class<out Activity> = when (slotId) {
    RuntimeSlotId.VAPP0 -> RuntimeProxyActivity0::class.java
    RuntimeSlotId.VAPP1 -> RuntimeProxyActivity1::class.java
}

class RuntimeSlotRepository(private val db: ValcronoDatabase, private val maxActiveApps: Int = 2) {
    suspend fun snapshot(): List<RuntimeSlot> = db.runtimeSlots().getAll().map { it.toModel() }

    suspend fun reserve(packageName: String, virtualUserId: Int, sessionId: String, launchAttemptId: String, now: Long = System.currentTimeMillis()): RuntimeSlot? = db.withTransaction {
        ensureSeeded()
        db.runtimeSlots().findByPackage(packageName, virtualUserId)?.takeIf { it.state !in setOf("FREE", "STOPPED", "CRASHED", "ERROR") }?.let { existing ->
            db.runtimeSlots().reserveExisting(existing.slotId, sessionId, launchAttemptId, now)
            return@withTransaction db.runtimeSlots().get(existing.slotId)?.toModel()
        }
        val enabled = RuntimeSlotId.entries.take(maxActiveApps.coerceIn(1, 2)).map { it.name }
        val free = db.runtimeSlots().firstFree(enabled) ?: return@withTransaction null
        db.runtimeSlots().reserveSlot(free.slotId, packageName, virtualUserId, sessionId, launchAttemptId, now)
        db.runtimeSlots().get(free.slotId)?.toModel()
    }

    suspend fun ensureSeeded() {
        db.runtimeSlots().insertDefaults(listOf(RuntimeSlotEntity("VAPP0", ":vapp0", "FREE", null, null, null, null, null, null, null, null, null, null, null, null), RuntimeSlotEntity("VAPP1", ":vapp1", "FREE", null, null, null, null, null, null, null, null, null, null, null, null)))
    }
}

open class RuntimeProcessService : Service() {
    private val binder = RuntimeBinder()
    inner class RuntimeBinder : Binder() {
        fun startSession(request: RuntimeLaunchRequest) = true
        fun stopSession(sessionId: String) = runCatching { stopSelf() }.isSuccess
        fun pauseSession(sessionId: String) = true
        fun resumeSession(sessionId: String) = true
        fun getStatus(): SlotMemorySnapshot = currentSlotMemorySnapshot()
        fun requestHeartbeat(): SlotMemorySnapshot = currentSlotMemorySnapshot()
        fun requestMemoryInfo(): SlotMemorySnapshot = currentSlotMemorySnapshot()
    }
    override fun onBind(intent: Intent?): IBinder = binder
}
class RuntimeSlot0Service : RuntimeProcessService()
class RuntimeSlot1Service : RuntimeProcessService()
class RuntimeProcessClient
