package com.valcrono.virtualspace

import android.os.SystemClock
import android.system.Os
import com.valcrono.core.VLog

data class RuntimeAppSnapshot(
    val packageName: String,
    val sessionId: String?,
    val slotId: String?,
    val slotState: RuntimeSlotState,
    val pid: Int?,
    val processAlive: Boolean,
    val serviceConnected: Boolean,
    val binderAlive: Boolean,
    val heartbeatAgeMs: Long?,
    val classLoaderLoaded: Boolean,
    val activityAttached: Boolean,
)

enum class DisplayedAppState { STOPPED, STARTING, ACTIVE_FOREGROUND, ACTIVE_BACKGROUND, STOPPING, CHECKING_RUNTIME, ERROR }

fun deriveDisplayedState(snapshot: RuntimeAppSnapshot?): DisplayedAppState {
    if (snapshot == null) return DisplayedAppState.STOPPED

    val runtimeAlive = snapshot.pid != null && snapshot.pid > 0 && snapshot.processAlive &&
        snapshot.serviceConnected && snapshot.binderAlive && snapshot.sessionId != null && snapshot.slotId != null

    if (!runtimeAlive) return DisplayedAppState.STOPPED

    return when (snapshot.slotState) {
        RuntimeSlotState.ACTIVE_FOREGROUND -> DisplayedAppState.ACTIVE_FOREGROUND
        RuntimeSlotState.ACTIVE_BACKGROUND, RuntimeSlotState.PAUSED_BY_USER -> DisplayedAppState.ACTIVE_BACKGROUND
        RuntimeSlotState.STARTING, RuntimeSlotState.RESERVED, RuntimeSlotState.PROCESS_STARTING,
        RuntimeSlotState.SERVICE_CONNECTED, RuntimeSlotState.LOAD_REQUEST_SENT,
        RuntimeSlotState.CLASSLOADER_READY, RuntimeSlotState.ACTIVITY_STARTING -> DisplayedAppState.STARTING
        RuntimeSlotState.STOPPING -> DisplayedAppState.STOPPING
        RuntimeSlotState.ERROR, RuntimeSlotState.FAILED -> DisplayedAppState.ERROR
        RuntimeSlotState.FREE, RuntimeSlotState.STOPPED, RuntimeSlotState.CRASHED,
        RuntimeSlotState.RECOVERING, RuntimeSlotState.ADOPTING -> DisplayedAppState.STOPPED
    }
}

private fun safeElapsedRealtime(): Long = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(System.currentTimeMillis())

fun buildRuntimeAppSnapshot(
    app: VirtualPackageEntity?,
    session: VirtualRuntimeSessionEntity?,
    slot: RuntimeSlotEntity?,
    now: Long = System.currentTimeMillis(),
    elapsedNow: Long = safeElapsedRealtime(),
): RuntimeAppSnapshot? {
    val packageName = app?.packageName ?: session?.packageName ?: slot?.packageName ?: return null
    val matchingSession = session?.takeIf { it.packageName == packageName && (app == null || it.virtualUserId == app.virtualUserId) }
    val matchingSlot = slot?.takeIf { it.packageName == packageName && (matchingSession == null || it.sessionId == matchingSession.sessionId) && (app == null || it.virtualUserId == app.virtualUserId) }
    val pid = matchingSlot?.hostPid ?: matchingSession?.hostPid
    val processAlive = pid != null && pid > 0 && runCatching { Os.kill(pid, 0); true }.getOrDefault(false)
    val heartbeatAt = matchingSlot?.lastHeartbeatElapsedRealtime ?: matchingSlot?.lastHeartbeatAt ?: matchingSession?.lastHeartbeatAt
    val heartbeatAgeMs = heartbeatAt?.let { if (matchingSlot?.lastHeartbeatElapsedRealtime != null) elapsedNow - it else now - it }
    val slotState = matchingSlot?.state?.let { runCatching { RuntimeSlotState.valueOf(it) }.getOrDefault(RuntimeSlotState.ERROR) } ?: RuntimeSlotState.FREE
    return RuntimeAppSnapshot(
        packageName = packageName,
        sessionId = matchingSession?.sessionId ?: matchingSlot?.sessionId,
        slotId = matchingSlot?.slotId,
        slotState = slotState,
        pid = pid,
        processAlive = processAlive,
        serviceConnected = matchingSlot != null && slotState != RuntimeSlotState.FREE,
        binderAlive = matchingSlot?.binderAlive == true,
        heartbeatAgeMs = heartbeatAgeMs,
        classLoaderLoaded = matchingSession?.classLoaderState == "LOADED" || slotState in setOf(RuntimeSlotState.ACTIVE_FOREGROUND, RuntimeSlotState.ACTIVE_BACKGROUND, RuntimeSlotState.PAUSED_BY_USER),
        activityAttached = matchingSlot?.activityAttached == true,
    )
}

fun sanitizeDisplayedState(displayedState: DisplayedAppState, snapshot: RuntimeAppSnapshot?): DisplayedAppState {
    val invalidStopped = displayedState == DisplayedAppState.STOPPED && snapshot?.pid != null && snapshot.processAlive
    check(!invalidStopped) { "Estado inválido: app detenida con proceso vivo" }
    return displayedState
}

fun productionSafeDisplayedState(displayedState: DisplayedAppState, snapshot: RuntimeAppSnapshot?): DisplayedAppState =
    try { sanitizeDisplayedState(displayedState, snapshot) } catch (t: IllegalStateException) {
        VLog.e("RuntimeState", t.message ?: "Estado inválido", t)
        DisplayedAppState.ACTIVE_BACKGROUND
    }

fun deriveRuntimeEffectiveState(
    session: VirtualRuntimeSessionEntity?,
    slot: RuntimeSlotEntity?,
    liveService: RuntimeServiceStatus? = null,
    now: Long = System.currentTimeMillis(),
    heartbeatTimeoutMs: Long = START_TIMEOUT_MS,
    currentRuntimeGeneration: Long = RuntimeHostRegistry.runtimeGeneration,
): RuntimeEffectiveState {
    val liveMatches = liveService != null && liveService.sessionId != null && liveService.packageName != null && session != null && slot != null && liveService.sessionId == session.sessionId && liveService.slotId == slot.slotId && liveService.packageName == session.packageName && liveService.pid > 0 && liveService.classLoaderLoaded
    if (liveMatches) return liveService!!.state.toEffective()
    val slotFresh = slot?.lastHeartbeatAt?.let { it >= now - heartbeatTimeoutMs } == true
    val slotActive = slot?.state == "ACTIVE_FOREGROUND" || slot?.state == "ACTIVE_BACKGROUND"
    val trulyActive = session != null && slot != null && slotActive && slotFresh && slot.sessionId == session.sessionId && slot.packageName == session.packageName && slot.virtualUserId == session.virtualUserId && slot.hostPid != null && slot.hostPid > 0 && slot.binderAlive && slot.runtimeGeneration == currentRuntimeGeneration
    if (trulyActive) return RuntimeSlotState.valueOf(slot!!.state).toEffective()
    return when (session?.state) { "STARTING" -> RuntimeEffectiveState.STARTING; "ERROR" -> RuntimeEffectiveState.ERROR; else -> RuntimeEffectiveState.STOPPED }
}

fun calculateDisplayedState(app: VirtualPackageEntity, slot: RuntimeSlotEntity?, session: VirtualRuntimeSessionEntity?, currentRuntimeGeneration: Long = RuntimeHostRegistry.runtimeGeneration): DisplayedAppState =
    productionSafeDisplayedState(deriveDisplayedState(buildRuntimeAppSnapshot(app, session, slot)), buildRuntimeAppSnapshot(app, session, slot))

private fun RuntimeSlotState.toEffective(): RuntimeEffectiveState = when (this) {
    RuntimeSlotState.ACTIVE_FOREGROUND -> RuntimeEffectiveState.ACTIVE_FOREGROUND
    RuntimeSlotState.ACTIVE_BACKGROUND -> RuntimeEffectiveState.ACTIVE_BACKGROUND
    RuntimeSlotState.PAUSED_BY_USER -> RuntimeEffectiveState.PAUSED
    RuntimeSlotState.STARTING, RuntimeSlotState.RESERVED, RuntimeSlotState.PROCESS_STARTING, RuntimeSlotState.SERVICE_CONNECTED, RuntimeSlotState.LOAD_REQUEST_SENT, RuntimeSlotState.CLASSLOADER_READY, RuntimeSlotState.ACTIVITY_STARTING -> RuntimeEffectiveState.STARTING
    RuntimeSlotState.ERROR, RuntimeSlotState.CRASHED, RuntimeSlotState.FAILED -> RuntimeEffectiveState.ERROR
    else -> RuntimeEffectiveState.STOPPED
}
