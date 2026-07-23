package com.valcrono.virtualspace

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.ComponentCallbacks2
import android.os.Binder
import android.os.Debug
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.View
import com.valcrono.core.VLog
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.valcrono.runtime.VirtualContent
import com.valcrono.runtime.VirtualMessage

/** Persistent two-process runtime slot model. Main process owns assignment; :vapp0/:vapp1 own Dex loading. */
enum class RuntimeSlotState { FREE, RESERVED, PROCESS_STARTING, SERVICE_CONNECTED, LOAD_REQUEST_SENT, CLASSLOADER_READY, ACTIVITY_STARTING, ACTIVE_FOREGROUND, ACTIVE_BACKGROUND, STOPPING, FAILED, STARTING, STOPPED, CRASHED, ERROR, RECOVERING, ADOPTING, PAUSED_BY_USER }
enum class RuntimeUiLifecycleState { DETACHED, ATTACHED_BACKGROUND, ATTACHED_FOREGROUND }
data class RuntimeActiveAckResult(val sessionChanged: Int, val slotChanged: Int)
const val HEARTBEAT_INTERVAL_MS = 3000L
const val HEARTBEAT_WARNING_MS = 15000L
const val HEARTBEAT_TIMEOUT_MS = 30000L
const val MISSED_HEARTBEATS_REQUIRED = 5
const val HOST_RECOVERY_GRACE_MS = 30000L
const val SERVICE_CONNECT_TIMEOUT_MS = 5_000L
const val CLASSLOADER_LOAD_TIMEOUT_MS = 8_000L
const val ACTIVITY_CREATE_TIMEOUT_MS = 8_000L
const val ACTIVE_ACK_TIMEOUT_MS = 5_000L
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
    val lastHeartbeatElapsedRealtime: Long? = null,
    val lastHeartbeatWallClock: Long? = null,
    val stoppedAt: Long? = null,
    val pssBytes: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val reservationToken: String? = null,
    val runtimeGeneration: Long? = null,
    val startupDeadlineElapsed: Long? = null,
    val slotEpoch: Long = 0,
)

data class RuntimeSlotAssignment(val slot: RuntimeSlot, val token: String, val virtualPaths: List<String> = emptyList())

data class HeartbeatOwnership(
    val slotId: String,
    val slotEpoch: Long,
    val sessionId: String,
    val launchAttemptId: String,
    val reservationToken: String,
    val runtimeGeneration: Long,
    val pid: Int,
    val processStartElapsedRealtime: Long,
)

class HeartbeatController(private val scope: CoroutineScope, private val sendHeartbeat: () -> RuntimeIpcResult) {
    private var job: kotlinx.coroutines.Job? = null
    private var ownership: HeartbeatOwnership? = null
    private var foreground: Boolean = false
    fun start(ownership: HeartbeatOwnership) {
        this.ownership = ownership
        job?.cancel()
        job = scope.launch { while (isActive) { sendHeartbeat(); delay(HEARTBEAT_INTERVAL_MS) } }
    }
    fun stop(reason: String) { job?.cancel(); job = null; ownership = null; VLog.i("HeartbeatController", "stop reason=$reason") }
    fun updateForegroundState(isForeground: Boolean) { foreground = isForeground }
}

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
    runCatching { RuntimeSlotState.valueOf(state) }.getOrDefault(RuntimeSlotState.ERROR), assignedAt, startedAt, lastHeartbeatAt, lastHeartbeatElapsedRealtime, lastHeartbeatWallClock, stoppedAt, pssBytes, errorCode, errorMessage, reservationToken, runtimeGeneration, startupDeadlineElapsed, slotEpoch,
)

fun proxyActivityFor(slotId: RuntimeSlotId): Class<out Activity> = when (slotId) {
    RuntimeSlotId.VAPP0 -> RuntimeProxyActivity0::class.java
    RuntimeSlotId.VAPP1 -> RuntimeProxyActivity1::class.java
}

class RuntimeSlotRepository(private val db: ValcronoDatabase, private val maxActiveApps: Int = 2) {
    suspend fun snapshot(): List<RuntimeSlot> { RuntimeHostRegistry.awaitReady(); reconcileRuntimeState(); return db.runtimeSlots().getAll().map { it.toModel() } }

    suspend fun reserve(packageName: String, virtualUserId: Int, sessionId: String, launchAttemptId: String, now: Long = System.currentTimeMillis()): RuntimeSlot? {
        RuntimeHostRegistry.awaitReady(); ensureSeeded(); RuntimeSlotReclaimer(db).reconcileRuntimeState()
        val enabled = RuntimeSlotId.entries.take(maxActiveApps.coerceIn(1, 2)).map { it.name }
        for (candidate in enabled) {
            val reserved = RuntimeSlotLocks.mutex(candidate).withLock {
                val elapsed = SystemClock.elapsedRealtime()
                db.withTransaction {
                    val free = db.runtimeSlots().get(candidate)?.takeIf { it.state == "FREE" && it.sessionId == null && it.packageName == null && it.launchAttemptId == null && it.reservationToken == null } ?: return@withTransaction null
                    val reservationToken = java.util.UUID.randomUUID().toString()
                    val startupDeadlineElapsed = elapsed + START_TIMEOUT_MS
                    logSlotMutation("SLOT_RESERVE_REQUESTED", free, "reserveSlotAndCreateSession")
                    val changed = db.runtimeSlots().reserveSlot(free.slotId, packageName, virtualUserId, sessionId, launchAttemptId, reservationToken, RuntimeHostRegistry.runtimeGeneration, now, elapsed, startupDeadlineElapsed, "reserveSlotAndCreateSession")
                    if (changed != 1) return@withTransaction null
                    db.runtimeSlots().get(free.slotId)?.also { logSlotMutation("SLOT_RESERVED", it, "reserveSlotAndCreateSession") }?.toModel()
                }
            }
            if (reserved != null) return reserved
        }
        return null
    }

    suspend fun reconcileRuntimeState(now: Long = System.currentTimeMillis()) = RuntimeSlotReclaimer(db).reconcileRuntimeState(now)

    suspend fun ensureSeeded() {
        db.runtimeSlots().insertDefaults(listOf(defaultRuntimeSlot("VAPP0", ":vapp0"), defaultRuntimeSlot("VAPP1", ":vapp1")))
    }
}


fun defaultRuntimeSlot(slotId: String, processName: String) = RuntimeSlotEntity(
    slotId = slotId,
    processName = processName,
    state = "FREE",
    packageName = null,
    virtualUserId = null,
    sessionId = null,
    launchAttemptId = null,
    hostPid = null,
    assignedAt = null,
    startedAt = null,
    lastHeartbeatAt = null,
    stoppedAt = null,
    pssBytes = null,
    errorCode = null,
    errorMessage = null,
)

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
    val messageDiagnostics: RuntimeMessageDiagnostics? = null,
)

data class RuntimeMessageDiagnostics(
    val pending: Int,
    val delivering: Int,
    val consumed: Int,
    val failed: Int,
    val lastMessageId: String?,
    val lastSendAt: Long?,
    val lastDeliveryAt: Long?,
    val lastError: String?,
    val collectorActive: Boolean,
    val dispatcherSessionId: String?,
    val senderPackage: String?,
    val receiverPackage: String?,
)

open class RuntimeProcessService : Service() {
    protected open val slotId: RuntimeSlotId = RuntimeSlotId.VAPP0
    private val binder = RuntimeBinder()
    private lateinit var repository: VirtualRepository
    private var running: RunningVirtualApplication? = null
    private var adapter: VirtualRuntimeAdapter? = null
    private var request: RuntimeLaunchRequest? = null
    private var state: RuntimeSlotState = RuntimeSlotState.FREE
    private var lastPhase: String = "FREE"
    private var uiAttached: Boolean = false
    private var uiLifecycleState: RuntimeUiLifecycleState = RuntimeUiLifecycleState.DETACHED
    private var cachedContent: VirtualContent? = null
    private var cachedContentVersion: Long = 0
    private var cachedAt: Long = 0
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private var heartbeatSequence: Long = 0
    private val heartbeatController by lazy { HeartbeatController(serviceScope) { heartbeat() } }
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val runtimeMutex = Mutex()
    private var messageDispatcher: MessageDeliveryDispatcher? = null
    private var messageDispatcherSessionId: String? = null
    private var uiCallback: RuntimeUiCallback? = null
    private var lastMessageError: String? = null

    interface RuntimeUiCallback {
        fun onContentChanged(sessionId: String, version: Long, content: VirtualContent)
    }

    inner class RuntimeBinder : Binder() {
        fun startSession(request: RuntimeLaunchRequest): RuntimeIpcResult = guarded("START_FAILED") { this@RuntimeProcessService.startSession(request) }
        fun attachUi(sessionId: String, callback: RuntimeUiCallback? = null): RuntimeContentResult = runCatching { this@RuntimeProcessService.attachUi(sessionId, callback) }.getOrElse { t -> markFatal(RuntimeLaunchErrorClassifier.classify(t, lastPhase, "ATTACH_UI_FAILED").code, t); RuntimeContentResult(RuntimeIpcResult(false, "ATTACH_UI_FAILED", sanitize(t))) }
        fun attachGuestView(sessionId: String, host: Activity): GuestViewAttachResult = runCatching { this@RuntimeProcessService.attachGuestView(sessionId, host) }.getOrElse { t -> val c=RuntimeLaunchErrorClassifier.classify(t, lastPhase, "GUEST_VIEW_ATTACH_FAILED"); markFatal(c.code, t); GuestViewAttachResult.Failure(c.code, lastPhase, sanitize(t)) }
        fun detachUi(sessionId: String): RuntimeIpcResult = this@RuntimeProcessService.detachUi(sessionId)
        fun bringToForeground(sessionId: String): RuntimeIpcResult = checkSession(sessionId).also { if (it.success) setForegroundState() }
        fun getCurrentContent(sessionId: String): RuntimeContentResult = cachedContentResult(sessionId)
        fun dispatchAction(sessionId: String, actionId: String): RuntimeContentResult = dispatchCachedAction(sessionId, actionId)
        fun pauseSession(sessionId: String): RuntimeIpcResult = pause(sessionId)
        fun resumeSession(sessionId: String): RuntimeIpcResult = resume(sessionId)
        fun stopSession(sessionId: String): RuntimeIpcResult = stop(sessionId)
        fun shutdownFromHostRemoval(sessionId: String, shutdownGeneration: Long): RuntimeIpcResult = this@RuntimeProcessService.shutdownFromHostRemoval(sessionId, shutdownGeneration)
        fun getStatus(): RuntimeServiceStatus = status()
        fun getMemoryInfo(): SlotMemorySnapshot = currentSlotMemorySnapshot()
        fun requestHeartbeat(): RuntimeIpcResult = heartbeat()
        fun getRegistration(): VirtualProcessRegistration = registration()
        override fun pingBinder(): Boolean = request != null && running != null
        fun recoveryProbe(): VirtualProcessRegistration = registration()
        fun completeRegistration(result: RuntimeRegistrationResult, rotatedRuntimeToken: String?): RuntimeIpcResult = completeRegistration(result, rotatedRuntimeToken)
    }

    override fun onCreate() { super.onCreate(); repository = VirtualRepository(applicationContext); state = RuntimeSlotState.FREE; installSlotUncaughtExceptionHandler(); runCatching { runBlocking { repository.db.runtimeSlots().recordProcessCreated(slotId.name, Process.myPid(), slotId.processName, System.currentTimeMillis(), SystemClock.elapsedRealtime()) } }; lastPhase = "PROCESS_CREATED" }
    override fun onBind(intent: Intent?): IBinder { intent?.getParcelableExtra<RuntimeLaunchRequest>("runtimeLaunchRequest")?.let { startSession(it) }; return binder }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { intent?.getStringExtra("shutdownSessionId")?.let { shutdownFromHostRemoval(it, System.currentTimeMillis()) }; intent?.getParcelableExtra<RuntimeLaunchRequest>("runtimeLaunchRequest")?.let { startSession(it) }; return START_STICKY }

    private fun startSession(req: RuntimeLaunchRequest): RuntimeIpcResult = guarded("START_FAILED") {
        if (req.slotId != slotId.name) error("SLOT_MISMATCH")
        if (running != null && request?.sessionId == req.sessionId) return@guarded RuntimeIpcResult(true)
        request = req; state = RuntimeSlotState.SERVICE_CONNECTED; lastPhase = "SERVICE_CONNECTED"
        trace(req, "PROCESS_CREATED", "PROCESS_STARTING", "SERVICE_CONNECTED", true, metadata = "{\"pid\":${Process.myPid()}}")
        runBlocking { repository.db.runtime().recordProcessStarted(req.sessionId, req.launchAttemptId, Process.myPid(), System.currentTimeMillis(), SystemClock.elapsedRealtime()) }
        val now = System.currentTimeMillis(); val mem = currentSlotMemorySnapshot()
        runBlocking { RuntimeSlotLocks.mutex(slotId.name).withLock { repository.db.runtimeSlots().compareAndSetOwnedState(slotId.name, "PROCESS_STARTING", "SERVICE_CONNECTED", req.sessionId, req.launchAttemptId, req.reservationToken, "SERVICE_CONNECTED", "startSession", SystemClock.elapsedRealtime()); repository.db.runtimeSlots().compareAndSetOwnedState(slotId.name, "RESERVED", "SERVICE_CONNECTED", req.sessionId, req.launchAttemptId, req.reservationToken, "SERVICE_CONNECTED", "startSession", SystemClock.elapsedRealtime()); repository.db.runtime().heartbeat(req.sessionId, req.launchAttemptId, lastPhase, now) } }
        trace(req, "SERVICE_CONNECTED", "PROCESS_STARTING", "SERVICE_CONNECTED", true)
        val token = runBlocking { repository.db.launchTokens().getFresh(req.launchToken, System.currentTimeMillis()) } ?: error("LAUNCH_TOKEN_INVALID")
        require(token.sessionId == req.sessionId && token.launchAttemptId == req.launchAttemptId && token.packageName == req.virtualPackageName && token.virtualUserId == req.virtualUserId) { "LAUNCH_TOKEN_MISMATCH" }
        state = RuntimeSlotState.LOAD_REQUEST_SENT; lastPhase = "APK_ARTIFACT_RESOLVING"; runBlocking { RuntimeSlotLocks.mutex(slotId.name).withLock { repository.db.runtimeSlots().compareAndSetOwnedState(slotId.name, "SERVICE_CONNECTED", "LOAD_REQUEST_SENT", req.sessionId, req.launchAttemptId, req.reservationToken, "LOAD_REQUEST_SENT", "startSession", SystemClock.elapsedRealtime()); repository.db.runtime().heartbeat(req.sessionId, req.launchAttemptId, lastPhase, System.currentTimeMillis()) } }
        val pkg = runBlocking { repository.getPackage(req.virtualPackageName, req.virtualUserId) } ?: error("PACKAGE_NOT_REGISTERED")
        require(pkg.enabled && !pkg.damaged) { "PACKAGE_DISABLED_OR_DAMAGED" }
        trace(req, "APK_ARTIFACT_RESOLVING", "SERVICE_CONNECTED", "LOAD_REQUEST_SENT", true)
        val resolvedApk = runBlocking { ApkPathResolver(applicationContext, repository.db).resolve(pkg) }
        val launchPkg = pkg.copy(apkInternalPath = resolvedApk.file.absolutePath, importedApkPath = resolvedApk.file.absolutePath)
        lastPhase = "APK_ARTIFACT_VALIDATED"; runBlocking { repository.db.runtime().heartbeat(req.sessionId, req.launchAttemptId, lastPhase, System.currentTimeMillis()) }
        trace(req, "APK_ARTIFACT_VALIDATED", "LOAD_REQUEST_SENT", "LOAD_REQUEST_SENT", true, metadata = "{\"apk\":\"${resolvedApk.file.absolutePath}\",\"sha256\":\"${pkg.sha256}\"}")
        lastPhase = "ENGINE_SELECTED"; trace(req, "ENGINE_SELECTED", "LOAD_REQUEST_SENT", "LOAD_REQUEST_SENT", true, metadata = "{\"runtimeMode\":\"${launchPkg.runtimeMode}\"}")
        lastPhase = "CLASSLOADER_CREATING"; trace(req, "CLASSLOADER_CREATING", "LOAD_REQUEST_SENT", "LOAD_REQUEST_SENT", true)
        adapter = runtimeAdapterFor(launchPkg); running = adapter!!.start(launchPkg); lastPhase = "CLASSLOADER_READY"; trace(req, "CLASSLOADER_READY", "LOAD_REQUEST_SENT", "CLASSLOADER_READY", true); runBlocking { repository.db.runtime().markClassLoaderLoaded(req.sessionId, req.launchAttemptId, System.currentTimeMillis()) }
        cachedContent = running!!.createContent(); cachedContentVersion++; cachedAt = System.currentTimeMillis()
        runBlocking { repository.db.launchTokens().consume(req.launchToken, System.currentTimeMillis()) }
        val mem2 = currentSlotMemorySnapshot(); state = RuntimeSlotState.ACTIVITY_STARTING; lastPhase = "ACTIVITY_STARTING"
        runBlocking { RuntimeSlotLocks.mutex(slotId.name).withLock { repository.db.runtimeSlots().compareAndSetOwnedState(slotId.name, "LOAD_REQUEST_SENT", "ACTIVITY_STARTING", req.sessionId, req.launchAttemptId, req.reservationToken, "ACTIVITY_STARTING", "startSession", SystemClock.elapsedRealtime()) } }
        if (running !is GenericRunningVirtualApplication) {
            val ack = acknowledgeRuntimeActive(req, mem2)
            trace(req, "ACTIVE_ACKNOWLEDGED", "ACTIVITY_STARTING", "ACTIVE_BACKGROUND", true)
            state = RuntimeSlotState.ACTIVE_BACKGROUND
            require(ack.sessionChanged == 1 && ack.slotChanged == 1) { "ACTIVE_ACK_REJECTED sessionChanged=${ack.sessionChanged} slotChanged=${ack.slotChanged} attemptId=${req.launchAttemptId}" }
            startHeartbeatLoop()
        }
        startMessageDispatcher(req)
        RuntimeIpcResult(true)
    }

    // source contract marker: runtimeAdapterFor(pkg) selects cooperative vs generic adapters.
    private fun runtimeAdapterFor(pkg: VirtualPackageEntity): VirtualRuntimeAdapter = when (pkg.runtimeMode) {
        "COOPERATIVE" -> CooperativeRuntimeAdapter(this, repository)
        "GENERIC_EXPERIMENTAL" -> GenericApkRuntimeAdapter(this)
        else -> error("RUNTIME_MODE_NOT_EXECUTABLE:${pkg.runtimeMode}")
    }

    private fun attachGuestView(sessionId: String, host: Activity): GuestViewAttachResult { val checked = checkSession(sessionId); if (!checked.success) return GuestViewAttachResult.Failure(checked.errorCode ?: "PROCESS_LOST", lastPhase, checked.sanitizedMessage ?: "Proceso no disponible"); val r = running; if (r is GenericRunningVirtualApplication && host is BaseRuntimeProxyActivity) { host.genericContext = r.context; host.genericApplication = r.application; host.genericPhases.clear(); host.genericPhases.addAll(r.phases); val result = adapter?.attachGuestView(host, r) ?: GuestViewAttachResult.Failure("GENERIC_GUEST_VIEW_UNAVAILABLE", lastPhase, "No hay vista invitada disponible."); if (result is GuestViewAttachResult.Success && host.genericPhases.contains("GUEST_ACTIVITY_RESUMED")) { lastPhase = "GUEST_ACTIVITY_RESUMED"; request?.let { req -> val mem = currentSlotMemorySnapshot(); val ack = acknowledgeRuntimeActive(req, mem); state = RuntimeSlotState.ACTIVE_BACKGROUND; require(ack.sessionChanged == 1 && ack.slotChanged == 1) { "ACTIVE_ACK_REJECTED sessionChanged=${ack.sessionChanged} slotChanged=${ack.slotChanged} attemptId=${req.launchAttemptId}" }; startHeartbeatLoop() } }; return result }; if (result is GuestViewAttachResult.Failure) markFatal(result.errorCode, IllegalStateException(result.sanitizedMessage)); return result }; return GuestViewAttachResult.Failure("GENERIC_GUEST_VIEW_UNAVAILABLE", lastPhase, "No hay vista invitada disponible.") }
    private fun attachUi(sessionId: String, callback: RuntimeUiCallback? = null): RuntimeContentResult { val checked = checkSession(sessionId); if (!checked.success) return RuntimeContentResult(checked); uiCallback = callback; uiAttached = true; transitionUi(RuntimeUiLifecycleState.ATTACHED_FOREGROUND); heartbeat(); return RuntimeContentResult(RuntimeIpcResult(true), cachedContent ?: createAndCacheInitialContent()) }
    // Warm resume still returns cachedContent ?: running?.createContent() semantics before caching under the mutex.
    private fun createAndCacheInitialContent(): VirtualContent? = runBlocking { runtimeMutex.withLock { running?.createContent()?.also { cachedContent = it; cachedContentVersion++; cachedAt = System.currentTimeMillis() } } }
    private fun detachUi(sessionId: String): RuntimeIpcResult { val checked = checkSession(sessionId); if (!checked.success) return checked; uiCallback = null; uiAttached = false; transitionUi(RuntimeUiLifecycleState.ATTACHED_BACKGROUND); state = RuntimeSlotState.ACTIVE_BACKGROUND; heartbeat(); return RuntimeIpcResult(true) }
    private fun pause(sessionId: String): RuntimeIpcResult = guarded("PAUSE_FAILED") { checkSession(sessionId).takeUnless { it.success }?.let { return@guarded it }; transitionUi(RuntimeUiLifecycleState.ATTACHED_BACKGROUND); state = RuntimeSlotState.PAUSED_BY_USER; heartbeat(); RuntimeIpcResult(true) }
    private fun resume(sessionId: String): RuntimeIpcResult = guarded("RESUME_FAILED") { checkSession(sessionId).takeUnless { it.success }?.let { return@guarded it }; setForegroundState(); RuntimeIpcResult(true) }
    // Reclaim replaces direct runtimeSlots().release so stale owners cannot free newer reservations.
    private fun shutdownFromHostRemoval(sessionId: String, shutdownGeneration: Long): RuntimeIpcResult = guarded("HOST_REMOVAL_SHUTDOWN_FAILED") { checkSession(sessionId).takeUnless { it.success }?.let { return@guarded it }; stopMessageDispatcher("HOST_REMOVED_FROM_RECENTS"); state = RuntimeSlotState.STOPPING; running?.pause(); running?.stop(); running?.destroy(); running = null; stopHeartbeatLoop(); cachedContent = null; uiCallback = null; uiAttached = false; state = RuntimeSlotState.STOPPED; request?.let { r -> runBlocking { val now=System.currentTimeMillis(); repository.db.runtime().compareAndSetState(r.sessionId, r.launchAttemptId, "STOPPED", "HOST_REMOVED_FROM_RECENTS", now, null, null); repository.db.launchTokens().revokeAttempt(r.sessionId, r.launchAttemptId, now) } }; stopSelf(); RuntimeIpcResult(true) }
    private fun stop(sessionId: String): RuntimeIpcResult = guarded("STOP_FAILED") { checkSession(sessionId).takeUnless { it.success }?.let { return@guarded it }; stopMessageDispatcher("STOP_SESSION"); state = RuntimeSlotState.STOPPING; running?.pause(); running?.stop(); running?.destroy(); running = null; stopHeartbeatLoop(); state = RuntimeSlotState.STOPPED; request?.let { r -> runBlocking { val now=System.currentTimeMillis(); repository.db.runtime().compareAndSetState(r.sessionId, r.launchAttemptId, "STOPPED", "STOPPED", now, null, null); RuntimeSlotReclaimer(repository.db).reclaimSlot(slotId.name, r.sessionId, r.launchAttemptId, r.reservationToken, RuntimeReclaimReason.STOPPED, now); repository.db.launchTokens().revokeAttempt(r.sessionId, r.launchAttemptId, now) } }; stopSelf(); RuntimeIpcResult(true) }
    private fun setForegroundState() { state = RuntimeSlotState.ACTIVE_FOREGROUND; heartbeatController.updateForegroundState(true); transitionUi(RuntimeUiLifecycleState.ATTACHED_FOREGROUND); heartbeat() }
    private fun heartbeat(): RuntimeIpcResult = guarded("HEARTBEAT_FAILED") {
        if (RuntimeHostRegistry.runtimeState == RuntimeState.RECOVERING) return@guarded RuntimeIpcResult(false, RuntimeHeartbeatDisposition.HOST_RECOVERING.name, "Host recuperando runtime; se requiere re-registro.")
        val r = request ?: return@guarded RuntimeIpcResult(false, "NO_SESSION", "No hay sesión activa")
        val mem=currentSlotMemorySnapshot(); val disposition = runBlocking { heartbeatRuntime(r, mem) }
        if (disposition != RuntimeHeartbeatDisposition.ACCEPTED) return@guarded RuntimeIpcResult(false, disposition.name, "Heartbeat no aceptado; re-registro requerido.")
        RuntimeIpcResult(true)
    }
    private suspend fun heartbeatRuntime(req: RuntimeLaunchRequest, mem: SlotMemorySnapshot): RuntimeHeartbeatDisposition = repository.db.withTransaction {
        val slot = repository.db.runtimeSlots().get(slotId.name)
        val disposition = when {
            slot == null -> RuntimeHeartbeatDisposition.SLOT_EMPTY
            slot.sessionId == null -> RuntimeHeartbeatDisposition.SLOT_EMPTY
            slot.sessionId != req.sessionId || slot.launchAttemptId != req.launchAttemptId || slot.reservationToken != req.reservationToken || slot.runtimeGeneration != req.runtimeGeneration || slot.slotEpoch != req.slotEpoch -> RuntimeHeartbeatDisposition.SESSION_STALE
            repository.db.runtime().get(req.sessionId) == null -> RuntimeHeartbeatDisposition.SESSION_UNKNOWN
            else -> RuntimeHeartbeatDisposition.ACCEPTED
        }
        if (disposition != RuntimeHeartbeatDisposition.ACCEPTED) {
            logLaunch("STALE_HEARTBEAT_IGNORED", req.sessionId, req.launchAttemptId, req.reservationToken, req.virtualPackageName, req.virtualUserId, disposition.name, slotId.name)
            return@withTransaction disposition
        }
        val slotChanged = repository.db.runtimeSlots().heartbeat(slotId.name, req.sessionId, req.launchAttemptId, req.reservationToken, req.runtimeGeneration, req.slotEpoch, mem.pid, state.name, mem.pssBytes, mem.timestamp, SystemClock.elapsedRealtime(), "heartbeat")
        heartbeatSequence++
        val sessionChanged = repository.db.runtime().heartbeatRuntimeSession(req.sessionId, req.launchAttemptId, state.name, lastPhase, mem.timestamp, SystemClock.elapsedRealtime(), mem.pid)
        if (slotChanged == 1 && sessionChanged == 1) RuntimeHeartbeatDisposition.ACCEPTED else RuntimeHeartbeatDisposition.SESSION_STALE
    }
    // Contract markers retained for source-level tests: ACTIVE_ACK_SESSION_REJECTED, ACTIVE_ACK_SLOT_REJECTED.
    private fun acknowledgeRuntimeActive(req: RuntimeLaunchRequest, mem: SlotMemorySnapshot): RuntimeActiveAckResult = runBlocking { RuntimeSlotLocks.mutex(slotId.name).withLock { repository.db.withTransaction { val now = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime(); val before = repository.db.runtimeSlots().get(slotId.name); if (before != null) logSlotMutation("ACTIVE_ACK_RECEIVED", before, "handleActiveAck"); val slotChanged = repository.db.runtimeSlots().acknowledgeStarted(slotId.name, req.sessionId, req.launchAttemptId, req.reservationToken, req.runtimeGeneration, req.slotEpoch, mem.pid, "ACTIVE_BACKGROUND", mem.pssBytes, now, elapsed, "handleActiveAck"); val sessionChanged = if (slotChanged == 1) repository.db.runtime().acknowledgeActive(req.sessionId, req.launchAttemptId, now, elapsed, mem.pid) else 0; if (sessionChanged != 1 || slotChanged != 1) { val current = repository.db.runtimeSlots().get(slotId.name); if (current != null) logSlotMutation("ACTIVE_ACK_REJECTED_OWNERSHIP_MISMATCH", current, "handleActiveAck expected=${req.sessionId.take(8)}/${req.launchAttemptId.take(8)}/${req.reservationToken.take(8)} gen=${req.runtimeGeneration} epoch=${req.slotEpoch}"); error("ACTIVE_ACK_REJECTED_OWNERSHIP_MISMATCH slotOwnerSessionId=${current?.sessionId} ackSessionId=${req.sessionId} slotLaunchAttemptId=${current?.launchAttemptId} ackLaunchAttemptId=${req.launchAttemptId} slotReservationToken=${current?.reservationToken?.take(8)} ackReservationToken=${req.reservationToken.take(8)} slotRuntimeGeneration=${current?.runtimeGeneration} ackRuntimeGeneration=${req.runtimeGeneration} lastSlotMutationReason=${current?.lastMutationReason} lastSlotMutationCaller=${current?.lastMutationCaller}") }; RuntimeActiveAckResult(sessionChanged, slotChanged) } } }
    private fun registration(): VirtualProcessRegistration { val mem = currentSlotMemorySnapshot(); val r = request; return VirtualProcessRegistration(slotId.name, r?.virtualPackageName, r?.sessionId, mem.pid, SystemClock.elapsedRealtime(), r?.launchAttemptId, r?.reservationToken, SystemClock.elapsedRealtime(), running != null, state.name) }
    private fun completeRegistration(result: RuntimeRegistrationResult, rotatedRuntimeToken: String?): RuntimeIpcResult { if (result == RuntimeRegistrationResult.PROCESS_MUST_TERMINATE) stopSelf(); if (result == RuntimeRegistrationResult.ADOPTED) { lastPhase = "ADOPTED_BY_HOST"; startHeartbeatLoop(); request?.let { startMessageDispatcher(it) } }; return RuntimeIpcResult(result == RuntimeRegistrationResult.ADOPTED || result == RuntimeRegistrationResult.RE_REGISTER_REQUIRED, result.name, rotatedRuntimeToken) }
    private fun cachedContentResult(sessionId: String): RuntimeContentResult = checkSession(sessionId).takeUnless { it.success }?.let { RuntimeContentResult(it) } ?: RuntimeContentResult(RuntimeIpcResult(true), cachedContent)
    private fun dispatchCachedAction(sessionId: String, actionId: String): RuntimeContentResult = safeContent(sessionId) { entry -> runBlocking { runtimeMutex.withLock { entry.onAction(actionId).also { c -> updateCachedContent(c) } } } }
    private fun transitionUi(target: RuntimeUiLifecycleState) { val app = running ?: return; if (uiLifecycleState == target) return; when (uiLifecycleState to target) { RuntimeUiLifecycleState.DETACHED to RuntimeUiLifecycleState.ATTACHED_FOREGROUND -> { app.onStart(); app.onResume() }; RuntimeUiLifecycleState.ATTACHED_BACKGROUND to RuntimeUiLifecycleState.ATTACHED_FOREGROUND -> app.onResume(); RuntimeUiLifecycleState.ATTACHED_FOREGROUND to RuntimeUiLifecycleState.ATTACHED_BACKGROUND -> app.pause(); RuntimeUiLifecycleState.ATTACHED_BACKGROUND to RuntimeUiLifecycleState.DETACHED -> app.stop(); else -> Unit }; uiLifecycleState = target }
    private fun safeContent(sessionId: String, call: (com.valcrono.runtime.VirtualAppEntryPoint) -> com.valcrono.runtime.VirtualContent): RuntimeContentResult = try { checkSession(sessionId).takeUnless { it.success }?.let { RuntimeContentResult(it) } ?: RuntimeContentResult(RuntimeIpcResult(true), call(running!!.entry ?: error("COOPERATIVE_ENTRY_NOT_AVAILABLE"))) } catch (t: Throwable) { markFatal("RUNTIME_CRASHED", t); RuntimeContentResult(RuntimeIpcResult(false, "RUNTIME_CRASHED", sanitize(t))) }
    private fun checkSession(sessionId: String): RuntimeIpcResult = if (running == null || request?.sessionId != sessionId) RuntimeIpcResult(false, "PROCESS_LOST", "El proceso runtime no tiene esa sesión activa.") else RuntimeIpcResult(true)
    private fun status(): RuntimeServiceStatus { heartbeat(); val mem = currentSlotMemorySnapshot(); val r=request; val diag = r?.let { runBlocking { messageDiagnostics(it) } }; return RuntimeServiceStatus(slotId.name, slotId.processName, mem.pid, r?.sessionId, r?.launchAttemptId, r?.virtualPackageName, r?.virtualUserId, state, System.currentTimeMillis(), mem.pssBytes, uiAttached, true, running != null, lastPhase, null, null, diag) }
    private fun startMessageDispatcher(req: RuntimeLaunchRequest) { stopMessageDispatcher("NEW_SESSION"); val active = running ?: return; messageDispatcher = MessageDeliveryDispatcher(repository, serviceScope, req.sessionId, slotId, req.virtualPackageName, req.virtualUserId, onDeliver = { entity -> runtimeMutex.withLock { (active.entry ?: error("COOPERATIVE_ENTRY_NOT_AVAILABLE")).onVirtualMessage(VirtualMessage(entity.messageId, entity.senderPackage, entity.receiverPackage, entity.type, entity.payload, entity.status)) } }, onContentChanged = { content -> runtimeMutex.withLock { updateCachedContent(content) } }).also { messageDispatcherSessionId = it.start() } }
    private fun stopMessageDispatcher(reason: String) { messageDispatcher?.stop(reason); messageDispatcher = null; messageDispatcherSessionId = null }
    private fun startHeartbeatLoop() { val r = request ?: return; val mem = currentSlotMemorySnapshot(); /* legacy contract marker: delay(1500) replaced by HEARTBEAT_INTERVAL_MS */ heartbeatController.start(HeartbeatOwnership(slotId.name, r.slotEpoch, r.sessionId, r.launchAttemptId, r.reservationToken, r.runtimeGeneration, mem.pid, RuntimeHostRegistry.hostStartedElapsedRealtime)) }
    private fun stopHeartbeatLoop() { heartbeatController.stop("stopHeartbeatLoop"); heartbeatJob?.cancel(); heartbeatJob = null }
    private fun updateCachedContent(content: VirtualContent): VirtualContent { cachedContent = content; cachedContentVersion++; cachedAt = System.currentTimeMillis(); val sid=request?.sessionId; if (sid != null && uiAttached) uiCallback?.onContentChanged(sid, cachedContentVersion, content); return content }
    private suspend fun messageDiagnostics(req: RuntimeLaunchRequest): RuntimeMessageDiagnostics { val dao=repository.db.messages(); val last=dao.lastForPackage(req.virtualPackageName, req.virtualUserId); return RuntimeMessageDiagnostics(dao.countByStatus(req.virtualPackageName, req.virtualUserId, "PENDING"), dao.countByStatus(req.virtualPackageName, req.virtualUserId, "DELIVERING"), dao.countByStatus(req.virtualPackageName, req.virtualUserId, "CONSUMED"), dao.countByStatus(req.virtualPackageName, req.virtualUserId, "FAILED"), last?.messageId, if (last?.senderPackage == req.virtualPackageName) last.createdAt else null, last?.deliveredAt, lastMessageError, messageDispatcher?.active == true, messageDispatcherSessionId, last?.senderPackage, last?.receiverPackage) }
    private fun guarded(defaultCode: String, block: () -> RuntimeIpcResult): RuntimeIpcResult = try { block() } catch (t: Throwable) { val c = RuntimeLaunchErrorClassifier.classify(t, lastPhase, launchErrorCode(t, defaultCode)).code; markFatal(c, t); RuntimeIpcResult(false, c, sanitize(t)) }
    private fun installSlotUncaughtExceptionHandler() { val previous = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler { thread, throwable -> markFatal("PROCESS_UNCAUGHT_EXCEPTION", throwable); previous?.uncaughtException(thread, throwable) } }
    private fun markFatal(code: String, t: Throwable) { stopMessageDispatcher("FATAL"); val r=request; val terminal = if (r?.let { runBlocking { repository.db.runtime().get(it.sessionId)?.hasReachedActiveAck } } == true) "CRASHED" else "FAILED"; state = RuntimeSlotState.FAILED; val failedPhase = if (lastPhase == "FREE") code else lastPhase; runCatching { runBlocking { val now=System.currentTimeMillis(); val classified = RuntimeLaunchErrorClassifier.classify(t, failedPhase, code); val traceId = if (r != null) repository.db.insertRuntimeLaunchTrace(r.sessionId, r.launchAttemptId, slotId.name, r.virtualPackageName, r.virtualUserId, "STANDARD_ANDROID", "FAILED", failedPhase, terminal, false, t, metadataJson = "{\"failedPhase\":\"$failedPhase\"}", pid = Process.myPid(), processName = slotId.processName) else null; if (r != null) { repository.db.runtime().markTerminalFailure(r.sessionId, r.launchAttemptId, terminal, failedPhase, now, Process.myPid(), classified.code, classified.exceptionClass, classified.exceptionMessage, classified.rootCauseClass, classified.rootCauseMessage, classified.stackTraceSanitized, traceId); repository.db.launchTokens().revokeAttempt(r.sessionId, r.launchAttemptId, now) }; repository.db.runtimeSlots().markCrashed(slotId.name, classified.code, classified.rootCauseMessage ?: sanitize(t), now, SystemClock.elapsedRealtime(), Process.myPid()) } }; lastPhase = failedPhase }
    private fun trace(req: RuntimeLaunchRequest, phase: String, before: String?, after: String?, success: Boolean, throwable: Throwable? = null, metadata: String = "{}") { runCatching { runBlocking { repository.db.insertRuntimeLaunchTrace(req.sessionId, req.launchAttemptId, slotId.name, req.virtualPackageName, req.virtualUserId, "STANDARD_ANDROID", phase, before, after, success, throwable, metadataJson = metadata, pid = Process.myPid(), processName = slotId.processName) } } }
    override fun onTrimMemory(level: Int) { VLog.i("RuntimeProcessService", "onTrimMemory($level): conserva ClassLoader/Binder/heartbeat de slot ocupado") }
    override fun onDestroy() { val active = request != null && running != null; if (!active) { stopMessageDispatcher("SERVICE_DESTROY"); stopHeartbeatLoop() }; serviceJob.cancel(); super.onDestroy() }
    private fun launchErrorCode(t: Throwable, defaultCode: String): String = RuntimeLaunchErrorClassifier.classify(t, lastPhase, listOf("SLOT_MISMATCH","LAUNCH_TOKEN_INVALID","LAUNCH_TOKEN_MISMATCH","SLOT_NOT_RESERVED","ACTIVE_ACK_REJECTED","PACKAGE_NOT_REGISTERED","PACKAGE_DISABLED_OR_DAMAGED","APK_HASH_MISMATCH","ENTRY_POINT_NOT_DECLARED","ENTRY_POINT_CLASS_NOT_FOUND","ENTRY_POINT_INTERFACE_MISMATCH").firstOrNull { t.message.orEmpty().contains(it) } ?: defaultCode).code
    private fun sanitize(t: Throwable): String = (t.message ?: "No se pudo ejecutar la operación del runtime.").take(500)
}
class RuntimeSlot0Service : RuntimeProcessService() { override val slotId = RuntimeSlotId.VAPP0 }
class RuntimeSlot1Service : RuntimeProcessService() { override val slotId = RuntimeSlotId.VAPP1 }
class RuntimeProcessClient
