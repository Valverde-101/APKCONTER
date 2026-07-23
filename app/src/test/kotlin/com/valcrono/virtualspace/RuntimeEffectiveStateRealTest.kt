package com.valcrono.virtualspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

private const val TEST_RUNTIME_GENERATION = 42L

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
    binderAlive = true,
    runtimeGeneration = TEST_RUNTIME_GENERATION,
)

class ActiveSlotOverridesStaleStartingUiTest {
    @Test fun activeSlotWinsOverStartingSession() {
        assertEquals(RuntimeEffectiveState.ACTIVE_BACKGROUND, deriveRuntimeEffectiveState(testSession("STARTING"), testSlot("ACTIVE_BACKGROUND"), now = 10_000L, currentRuntimeGeneration = TEST_RUNTIME_GENERATION))
    }
}

class AppCardActiveSlotButtonEnabledTest {
    @Test fun activeSlotIsNotStarting() {
        val effective = deriveRuntimeEffectiveState(testSession("STARTING"), testSlot("ACTIVE_FOREGROUND"), now = 10_000L, currentRuntimeGeneration = TEST_RUNTIME_GENERATION)
        assertEquals(RuntimeEffectiveState.ACTIVE_FOREGROUND, effective)
    }
}

class WatchdogDoesNotKillHealthyActiveSlotTest {
    @Test fun freshActiveSlotIsEffectiveActive() {
        assertEquals(RuntimeEffectiveState.ACTIVE_BACKGROUND, deriveRuntimeEffectiveState(testSession("STARTING"), testSlot("ACTIVE_BACKGROUND", now = 20_000L), now = 20_000L, currentRuntimeGeneration = TEST_RUNTIME_GENERATION))
    }
}

class ActivityAttachedIsClearedOnStopTest {
    @Test fun detachedSlotDoesNotReportCurrentAttachment() {
        val detached = testSlot("ACTIVE_BACKGROUND").copy(activityAttached = false)
        assertEquals(false, detached.activityAttached)
    }
}

class DisplayedStateInvariantTest {
    @Test fun freeSlotCannotDisplayActiveApp() {
        val app = VirtualPackageEntity("pkg", "TestApp A", 1, "1", 23, 35, "sha", "/tmp/app.apk", 0, 0, null, false, null, "Entry", "COOPERATIVE_SUPPORTED", true, false, 0, 10000)
        val freeSlot = testSlot("FREE").copy(packageName = null, sessionId = null, hostPid = null, binderAlive = false)
        val displayed = calculateDisplayedState(app, freeSlot, testSession("ACTIVE"), currentRuntimeGeneration = TEST_RUNTIME_GENERATION)
        assertEquals(DisplayedAppState.STOPPED, displayed)
        assertFalse(freeSlot.state == "FREE" && displayed == DisplayedAppState.ACTIVE)
    }
}
