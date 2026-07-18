package com.valcrono.virtualspace

import android.os.Process
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSlotPidInstrumentedTest {
    @Test fun mainProcessPidIsAvailableForSlotComparison() {
        val mainPid = Process.myPid()
        assertTrue("main pid must be a real Android pid before comparing :vapp0/:vapp1 callbacks", mainPid > 0)
    }
}
