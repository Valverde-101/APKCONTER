package com.valcrono.virtualspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisplayedStateInvariantInstrumentedTest {
    @Test fun freeSlotCannotRenderActiveApplication() {
        val now = System.currentTimeMillis()
        val app = VirtualPackageEntity("pkg", "TestApp A", 1, "1", 23, 35, "sha", "/tmp/app.apk", now, now, null, false, null, "Entry", "COOPERATIVE_SUPPORTED", true, false, 0, 10000)
        val session = VirtualRuntimeSessionEntity("s1", "pkg", 0, "ACTIVE", "a1", now, now, now, now, null, 1234, "Entry", "LOADED", "TEST", null, null)
        val freeSlot = RuntimeSlotEntity("VAPP0", "com.valcrono.virtualspace:vapp0", "FREE", null, null, null, null, null, null, null, null, null, null, null, null, null, null)
        val displayed = calculateDisplayedState(app, freeSlot, session, currentRuntimeGeneration = 42L)
        assertEquals(DisplayedAppState.STOPPED, displayed)
        assertFalse(freeSlot.state == "FREE" && displayed == DisplayedAppState.ACTIVE)
    }
}
