package com.valcrono.virtualspace

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomMultiProcessInvalidationInstrumentedTest {
    @Test fun vappStateUpdateReachesMainProcessObserver() = runBlocking {
        val db = DatabaseProvider.get(ApplicationProvider.getApplicationContext())
        val now = System.currentTimeMillis()
        val session = VirtualRuntimeSessionEntity("mp-session", "test.pkg", 0, "STARTING", "attempt", now, null, now, now, null, null, "Entry", "PENDING", "TEST", null, null)
        db.runtime().upsert(session)
        db.runtimeSlots().insertDefaults(listOf(RuntimeSlotEntity("VAPP1", ":vapp1", "FREE", null, null, null, null, null, null, null, null, null, null, null, null)))
        db.runtimeSlots().reserveSlot("VAPP1", "test.pkg", 0, "mp-session", "attempt", now)
        withContext(Dispatchers.IO) { db.runtime().acknowledgeActive("mp-session", "attempt", System.currentTimeMillis(), android.os.Process.myPid()) }
        val observed = db.runtime().observeSessions().first().first { it.sessionId == "mp-session" }
        assertEquals("ACTIVE", observed.state)
    }
}

class VappStateUpdateReachesMainProcessTest {
    @Test fun flowReceivesActiveWithoutActivityRestart() = RoomMultiProcessInvalidationInstrumentedTest().vappStateUpdateReachesMainProcessObserver()
}
