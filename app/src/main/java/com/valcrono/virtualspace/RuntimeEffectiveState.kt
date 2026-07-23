package com.valcrono.virtualspace

fun deriveRuntimeEffectiveState(
    session: VirtualRuntimeSessionEntity?,
    slot: RuntimeSlotEntity?,
    liveService: RuntimeServiceStatus? = null,
    now: Long = System.currentTimeMillis(),
    heartbeatTimeoutMs: Long = START_TIMEOUT_MS,
    currentRuntimeGeneration: Long = RuntimeHostRegistry.runtimeGeneration,
): RuntimeEffectiveState {
    val liveMatches = liveService != null &&
        liveService.sessionId != null &&
        liveService.packageName != null &&
        session != null &&
        slot != null &&
        liveService.sessionId == session.sessionId &&
        liveService.slotId == slot.slotId &&
        liveService.packageName == session.packageName &&
        liveService.pid > 0 &&
        liveService.classLoaderLoaded
    if (liveMatches) return liveService!!.state.toEffective()

    val slotFresh = slot?.lastHeartbeatAt?.let { it >= now - heartbeatTimeoutMs } == true
    val slotActive = slot?.state == "ACTIVE_FOREGROUND" || slot?.state == "ACTIVE_BACKGROUND"
    val trulyActive = session != null && slot != null && slotActive && slotFresh &&
        slot.sessionId == session.sessionId && slot.packageName == session.packageName &&
        slot.virtualUserId == session.virtualUserId && slot.hostPid != null && slot.hostPid > 0 &&
        slot.binderAlive && slot.runtimeGeneration == currentRuntimeGeneration
    if (trulyActive) return RuntimeSlotState.valueOf(slot!!.state).toEffective()

    return when (session?.state) {
        "STARTING" -> RuntimeEffectiveState.STARTING
        "ERROR" -> RuntimeEffectiveState.ERROR
        else -> RuntimeEffectiveState.STOPPED
    }
}

enum class DisplayedAppState { ACTIVE, STOPPED }

fun calculateDisplayedState(
    app: VirtualPackageEntity,
    slot: RuntimeSlotEntity?,
    session: VirtualRuntimeSessionEntity?,
    currentRuntimeGeneration: Long = RuntimeHostRegistry.runtimeGeneration,
): DisplayedAppState {
    val trulyActive = slot != null && session != null &&
        slot.state in setOf("ACTIVE_BACKGROUND", "ACTIVE_FOREGROUND") &&
        slot.sessionId == session.sessionId &&
        slot.packageName == app.packageName &&
        slot.virtualUserId == app.virtualUserId &&
        session.packageName == app.packageName &&
        session.virtualUserId == app.virtualUserId &&
        slot.hostPid != null && slot.hostPid > 0 &&
        slot.binderAlive &&
        slot.runtimeGeneration == currentRuntimeGeneration
    return if (trulyActive) DisplayedAppState.ACTIVE else DisplayedAppState.STOPPED
}

private fun RuntimeSlotState.toEffective(): RuntimeEffectiveState = when (this) {
    RuntimeSlotState.ACTIVE_FOREGROUND -> RuntimeEffectiveState.ACTIVE_FOREGROUND
    RuntimeSlotState.ACTIVE_BACKGROUND -> RuntimeEffectiveState.ACTIVE_BACKGROUND
    RuntimeSlotState.PAUSED_BY_USER -> RuntimeEffectiveState.PAUSED
    RuntimeSlotState.STARTING, RuntimeSlotState.RESERVED -> RuntimeEffectiveState.STARTING
    RuntimeSlotState.ERROR, RuntimeSlotState.CRASHED -> RuntimeEffectiveState.ERROR
    else -> RuntimeEffectiveState.STOPPED
}
