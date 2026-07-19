package com.valcrono.virtualspace

fun deriveRuntimeEffectiveState(
    session: VirtualRuntimeSessionEntity?,
    slot: RuntimeSlotEntity?,
    liveService: RuntimeServiceStatus? = null,
    now: Long = System.currentTimeMillis(),
    heartbeatTimeoutMs: Long = START_TIMEOUT_MS,
): RuntimeEffectiveState {
    val liveMatches = liveService != null &&
        liveService.sessionId != null &&
        (session == null || liveService.sessionId == session.sessionId) &&
        (slot == null || liveService.slotId == slot.slotId) &&
        liveService.classLoaderLoaded
    if (liveMatches) return liveService!!.state.toEffective()

    val slotFresh = slot?.lastHeartbeatAt?.let { it >= now - heartbeatTimeoutMs } == true
    if (slot != null && slotFresh && slot.hostPid != null) {
        val slotEffective = runCatching { RuntimeSlotState.valueOf(slot.state).toEffective() }.getOrNull()
        if (slotEffective == RuntimeEffectiveState.ACTIVE_FOREGROUND || slotEffective == RuntimeEffectiveState.ACTIVE_BACKGROUND || slotEffective == RuntimeEffectiveState.PAUSED) return slotEffective
    }

    return when (session?.state) {
        "STARTING" -> RuntimeEffectiveState.STARTING
        "ACTIVE" -> RuntimeEffectiveState.ACTIVE_BACKGROUND
        "PAUSED" -> RuntimeEffectiveState.PAUSED
        "ERROR" -> RuntimeEffectiveState.ERROR
        else -> RuntimeEffectiveState.STOPPED
    }
}

private fun RuntimeSlotState.toEffective(): RuntimeEffectiveState = when (this) {
    RuntimeSlotState.ACTIVE_FOREGROUND -> RuntimeEffectiveState.ACTIVE_FOREGROUND
    RuntimeSlotState.ACTIVE_BACKGROUND -> RuntimeEffectiveState.ACTIVE_BACKGROUND
    RuntimeSlotState.PAUSED_BY_USER -> RuntimeEffectiveState.PAUSED
    RuntimeSlotState.STARTING, RuntimeSlotState.RESERVED -> RuntimeEffectiveState.STARTING
    RuntimeSlotState.ERROR, RuntimeSlotState.CRASHED -> RuntimeEffectiveState.ERROR
    else -> RuntimeEffectiveState.STOPPED
}
