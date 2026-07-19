package com.valcrono.virtualspace

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Debug
import android.os.IBinder
import android.os.Process
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking

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



data class RuntimeIpcResult(val success: Boolean, val errorCode: String? = null, val sanitizedMessage: String? = null)

data class RuntimeContentResult(val result: RuntimeIpcResult, val content: com.valcrono.runtime.VirtualContent? = null)

data class RuntimeServiceStatus(
    val slotId: String,
    val processName: String,
    val pid: Int,
    val sessionId: String?,
    val launchAttemptId: String?,
    val packageName: String?,
    val virtualUserId: Int?,
    val state: RuntimeSlotState,
    val lastHeartbeatAt: Long?,
    val pssBytes: Long?,
    val activityAttached: Boolean,
    val serviceConnected: Boolean,
    val classLoaderLoaded: Boolean,
    val lastPhase: String,
    val errorCode: String?,
    val errorMessage: String?,
)

open class RuntimeProcessService : Service() {
    protected open val slotId: RuntimeSlotId = RuntimeSlotId.VAPP0
    private val binder = RuntimeBinder()
    private lateinit var repository: VirtualRepository
    private var running: RunningVirtualApp? = null
    private var host: VirtualRuntimeHost? = null
    private var request: RuntimeLaunchRequest? = null
    private var state: RuntimeSlotState = RuntimeSlotState.FREE
    private var lastPhase: String = "FREE"
    private var uiAttached: Boolean = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heartbeatTick = object : Runnable { override fun run() { heartbeat(); handler.postDelayed(this, 3_000) } }

    inner class RuntimeBinder : Binder() {
        fun startSession(request: RuntimeLaunchRequest): RuntimeIpcResult = this@RuntimeProcessService.startSession(request)
        fun attachUi(sessionId: String): RuntimeContentResult = this@RuntimeProcessService.attachUi(sessionId)
        fun detachUi(sessionId: String): RuntimeIpcResult = this@RuntimeProcessService.detachUi(sessionId)
        fun bringToForeground(sessionId: String): RuntimeIpcResult = checkSession(sessionId).also { if (it.success) setForegroundState() }
        fun getCurrentContent(sessionId: String): RuntimeContentResult = safeContent(sessionId) { it.createContent() }
        fun dispatchAction(sessionId: String, actionId: String): RuntimeContentResult = safeContent(sessionId) { it.onAction(actionId) }
        fun pauseSession(sessionId: String): RuntimeIpcResult = pause(sessionId)
        fun resumeSession(sessionId: String): RuntimeIpcResult = resume(sessionId)
        fun stopSession(sessionId: String): RuntimeIpcResult = stop(sessionId)
        fun getStatus(): RuntimeServiceStatus = status()
        fun getMemoryInfo(): SlotMemorySnapshot = currentSlotMemorySnapshot()
        fun requestHeartbeat(): RuntimeIpcResult = heartbeat()
    }

    override fun onCreate() { super.onCreate(); repository = VirtualRepository(applicationContext); state = RuntimeSlotState.FREE }
    override fun onBind(intent: Intent?): IBinder { intent?.getParcelableExtra<RuntimeLaunchRequest>("runtimeLaunchRequest")?.let { startSession(it) }; return binder }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { intent?.getParcelableExtra<RuntimeLaunchRequest>("runtimeLaunchRequest")?.let { startSession(it) }; return START_STICKY }

    private fun startSession(req: RuntimeLaunchRequest): RuntimeIpcResult = guarded("START_FAILED") {
        if (req.slotId != slotId.name) error("SLOT_MISMATCH")
        if (running != null && request?.sessionId == req.sessionId) return@guarded RuntimeIpcResult(true)
        request = req; state = RuntimeSlotState.STARTING; lastPhase = "PROCESS_CONNECTED"
        val now = System.currentTimeMillis(); val mem = currentSlotMemorySnapshot()
        runBlocking { repository.db.runtimeSlots().acknowledgeStarted(slotId.name, req.sessionId, req.launchAttemptId, mem.pid, "STARTING", mem.pssBytes, now); repository.db.runtime().heartbeat(req.sessionId, req.launchAttemptId, lastPhase, now) }
        val token = runBlocking { repository.db.launchTokens().getFresh(req.launchToken, System.currentTimeMillis()) } ?: error("LAUNCH_TOKEN_INVALID")
        require(token.sessionId == req.sessionId && token.launchAttemptId == req.launchAttemptId && token.packageName == req.virtualPackageName && token.virtualUserId == req.virtualUserId) { "LAUNCH_TOKEN_MISMATCH" }
        lastPhase = "TOKEN_VALIDATED"; runBlocking { repository.db.runtime().heartbeat(req.sessionId, req.launchAttemptId, lastPhase, System.currentTimeMillis()) }
        val pkg = runBlocking { repository.getPackage(req.virtualPackageName, req.virtualUserId) } ?: error("PACKAGE_NOT_REGISTERED")
        require(pkg.enabled && !pkg.damaged) { "PACKAGE_DISABLED_OR_DAMAGED" }
        require(runBlocking { repository.verifyPackage(pkg) }) { "APK_HASH_MISMATCH" }
        lastPhase = "APK_VERIFIED"; runBlocking { repository.db.runtime().heartbeat(req.sessionId, req.launchAttemptId, lastPhase, System.currentTimeMillis()) }
        host = VirtualRuntimeHost(this, repository); running = host!!.start(pkg)
        runBlocking { repository.db.launchTokens().consume(req.launchToken, System.currentTimeMillis()) }
        val mem2 = currentSlotMemorySnapshot(); state = RuntimeSlotState.ACTIVE_BACKGROUND; lastPhase = "RUNTIME_CREATED"
        val changed = runBlocking { repository.db.runtime().acknowledgeActive(req.sessionId, req.launchAttemptId, System.currentTimeMillis(), mem2.pid); repository.db.runtimeSlots().acknowledgeStarted(slotId.name, req.sessionId, req.launchAttemptId, mem2.pid, state.name, mem2.pssBytes, System.currentTimeMillis()) }
        require(changed > 0) { "ACTIVE_ACK_REJECTED" }
        handler.removeCallbacks(heartbeatTick); handler.post(heartbeatTick)
        RuntimeIpcResult(true)
    }

    private fun attachUi(sessionId: String): RuntimeContentResult { val checked = checkSession(sessionId); if (!checked.success) return RuntimeContentResult(checked); uiAttached = true; setForegroundState(); return safeContent(sessionId) { it.createContent() } }
    private fun detachUi(sessionId: String): RuntimeIpcResult { val checked = checkSession(sessionId); if (!checked.success) return checked; uiAttached = false; state = RuntimeSlotState.ACTIVE_BACKGROUND; heartbeat(); running?.entry?.onPause(); running?.entry?.onStop(); return RuntimeIpcResult(true) }
    private fun pause(sessionId: String): RuntimeIpcResult = guarded("PAUSE_FAILED") { checkSession(sessionId).takeUnless { it.success }?.let { return@guarded it }; running?.entry?.onPause(); state = RuntimeSlotState.PAUSED_BY_USER; heartbeat(); RuntimeIpcResult(true) }
    private fun resume(sessionId: String): RuntimeIpcResult = guarded("RESUME_FAILED") { checkSession(sessionId).takeUnless { it.success }?.let { return@guarded it }; running?.entry?.onResume(); setForegroundState(); RuntimeIpcResult(true) }
    private fun stop(sessionId: String): RuntimeIpcResult = guarded("STOP_FAILED") { checkSession(sessionId).takeUnless { it.success }?.let { return@guarded it }; state = RuntimeSlotState.STOPPING; running?.entry?.onPause(); running?.entry?.onStop(); running?.entry?.onDestroy(); running = null; handler.removeCallbacks(heartbeatTick); state = RuntimeSlotState.STOPPED; request?.let { r -> runBlocking { val now=System.currentTimeMillis(); repository.db.runtime().compareAndSetState(r.sessionId, r.launchAttemptId, "STOPPED", "STOPPED", now, null, null); repository.db.runtimeSlots().release(slotId.name, now); repository.db.launchTokens().revokeAttempt(r.sessionId, r.launchAttemptId, now) } }; stopSelf(); RuntimeIpcResult(true) }
    private fun setForegroundState() { state = RuntimeSlotState.ACTIVE_FOREGROUND; running?.entry?.onStart(); running?.entry?.onResume(); heartbeat() }
    private fun heartbeat(): RuntimeIpcResult = guarded("HEARTBEAT_FAILED") { val r = request ?: return@guarded RuntimeIpcResult(false, "NO_SESSION", "No hay sesión activa"); val mem=currentSlotMemorySnapshot(); runBlocking { repository.db.runtimeSlots().heartbeat(slotId.name, r.sessionId, mem.pid, state.name, mem.pssBytes, mem.timestamp) }; RuntimeIpcResult(true) }
    private fun safeContent(sessionId: String, call: (com.valcrono.runtime.VirtualAppEntryPoint) -> com.valcrono.runtime.VirtualContent): RuntimeContentResult = try { checkSession(sessionId).takeUnless { it.success }?.let { RuntimeContentResult(it) } ?: RuntimeContentResult(RuntimeIpcResult(true), call(running!!.entry)) } catch (t: Throwable) { markFatal("RUNTIME_CRASHED", t); RuntimeContentResult(RuntimeIpcResult(false, "RUNTIME_CRASHED", sanitize(t))) }
    private fun checkSession(sessionId: String): RuntimeIpcResult = if (running == null || request?.sessionId != sessionId) RuntimeIpcResult(false, "PROCESS_LOST", "El proceso runtime no tiene esa sesión activa.") else RuntimeIpcResult(true)
    private fun status(): RuntimeServiceStatus { val mem = currentSlotMemorySnapshot(); val r=request; return RuntimeServiceStatus(slotId.name, slotId.processName, mem.pid, r?.sessionId, r?.launchAttemptId, r?.virtualPackageName, r?.virtualUserId, state, System.currentTimeMillis(), mem.pssBytes, uiAttached, true, running != null, lastPhase, null, null) }
    private fun guarded(defaultCode: String, block: () -> RuntimeIpcResult): RuntimeIpcResult = try { block() } catch (t: Throwable) { markFatal(launchErrorCode(t, defaultCode), t); RuntimeIpcResult(false, launchErrorCode(t, defaultCode), sanitize(t)) }
    private fun markFatal(code: String, t: Throwable) { val r=request; state = RuntimeSlotState.ERROR; lastPhase = code; runCatching { runBlocking { val now=System.currentTimeMillis(); if (r != null) { repository.db.runtime().compareAndSetState(r.sessionId, r.launchAttemptId, "ERROR", code, now, code, sanitize(t)); repository.db.launchTokens().revokeAttempt(r.sessionId, r.launchAttemptId, now) }; repository.db.runtimeSlots().markCrashed(slotId.name, code, sanitize(t), now) } } }
    private fun launchErrorCode(t: Throwable, defaultCode: String): String = listOf("SLOT_MISMATCH","LAUNCH_TOKEN_INVALID","LAUNCH_TOKEN_MISMATCH","SLOT_NOT_RESERVED","ACTIVE_ACK_REJECTED","PACKAGE_NOT_REGISTERED","PACKAGE_DISABLED_OR_DAMAGED","APK_HASH_MISMATCH","ENTRY_POINT_NOT_DECLARED","ENTRY_POINT_CLASS_NOT_FOUND","ENTRY_POINT_INTERFACE_MISMATCH").firstOrNull { t.message.orEmpty().contains(it) } ?: defaultCode
    private fun sanitize(t: Throwable): String = (t.message ?: "No se pudo ejecutar la operación del runtime.").take(500)
}
class RuntimeSlot0Service : RuntimeProcessService() { override val slotId = RuntimeSlotId.VAPP0 }
class RuntimeSlot1Service : RuntimeProcessService() { override val slotId = RuntimeSlotId.VAPP1 }
class RuntimeProcessClient
