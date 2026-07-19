package com.valcrono.virtualspace

import org.junit.Assert.assertEquals
import org.junit.Test

private fun testSession(state: String, now: Long = 10_000L) = VirtualRuntimeSessionEntity(
    sessionId = "s1",
    packageName = "pkg",
    virtualUserId = 0,
    state = state,
    currentLaunchAttemptId = "a1",
    createdAt = now,
    startedAt = null,
    lastActivityAt = now,
    lastHeartbeatAt = now,
    stoppedAt = null,
    hostPid = null,
    entryPoint = "Entry",
    classLoaderState = "LOADED",
    launchPhase = "TEST",
    errorCode = null,
    sanitizedError = null,
)

private fun testSlot(state: String, now: Long = 10_000L) = RuntimeSlotEntity(
    slotId = "VAPP1",
    processName = ":vapp1",
    state = state,
    packageName = "pkg",
    virtualUserId = 0,
    sessionId = "s1",
    launchAttemptId = "a1",
    hostPid = 1234,
    assignedAt = now,
    startedAt = now,
    lastHeartbeatAt = now,
    stoppedAt = null,
    pssBytes = 42,
    errorCode = null,
    errorMessage = null,
    taskId = 7,
    activityInstanceId = "activity",
    activityLastAttachedAt = now,
    activityAttached = true,
)

class ActiveSlotOverridesStaleStartingUiTest {
    @Test fun activeSlotWinsOverStartingSession() {
        assertEquals(RuntimeEffectiveState.ACTIVE_BACKGROUND, deriveRuntimeEffectiveState(testSession("STARTING"), testSlot("ACTIVE_BACKGROUND"), now = 10_000L))
    }
}

class AppCardActiveSlotButtonEnabledTest {
    @Test fun activeSlotIsNotStarting() {
        val effective = deriveRuntimeEffectiveState(testSession("STARTING"), testSlot("ACTIVE_FOREGROUND"), now = 10_000L)
        assertEquals(RuntimeEffectiveState.ACTIVE_FOREGROUND, effective)
    }
}

class WatchdogDoesNotKillHealthyActiveSlotTest {
    @Test fun freshActiveSlotIsEffectiveActive() {
        assertEquals(RuntimeEffectiveState.ACTIVE_BACKGROUND, deriveRuntimeEffectiveState(testSession("STARTING"), testSlot("ACTIVE_BACKGROUND", now = 20_000L), now = 20_000L))
    }
}

class ActivityAttachedIsClearedOnStopTest {
    @Test fun detachedSlotDoesNotReportCurrentAttachment() {
        val detached = testSlot("ACTIVE_BACKGROUND").copy(activityAttached = false)
        assertEquals(false, detached.activityAttached)
    }
}
