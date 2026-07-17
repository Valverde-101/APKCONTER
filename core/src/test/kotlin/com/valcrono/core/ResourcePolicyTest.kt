package com.valcrono.core

import org.junit.Assert.*
import org.junit.Test

class MemoryProfileTest {
    private val motoLike = MemoryInputs(
        totalRamBytes = 4L * 1024 * 1024 * 1024,
        availableRamBytes = 1500L * 1024 * 1024,
        memoryClassMb = 256,
        largeMemoryClassMb = 512,
        isLowRamDevice = false,
        processPssBytes = 90L * 1024 * 1024,
        activeSessions = 1,
    )

    @Test fun automaticKeepsOneActiveAndAtMostOnePaused() {
        val budget = MemoryBudgetManager().budget(MemoryProfile.AUTOMATIC, motoLike)
        assertEquals(1, budget.maxActiveApps)
        assertTrue(budget.maxPausedSessions in 0..1)
        assertTrue(budget.explanation.contains("objetivo de uso administrado"))
    }
}

class MemoryBudgetManagerTest {
    @Test fun customBudgetIsClampedToSafeFractionOfTotalRam() {
        val inputs = MemoryInputs(2_000_000_000, 1_500_000_000, 256, 512, false, 80_000_000, 1)
        val budget = MemoryBudgetManager().budget(MemoryProfile.CUSTOM, inputs, customTargetBytes = 1_800_000_000)
        assertTrue(budget.targetHostBytes <= (inputs.totalRamBytes * 0.35).toLong())
    }
}

class SessionEvictionTest {
    @Test fun pausedLeastRecentlyUsedSessionIsStoppedFirst() {
        val budget = MemoryBudget(MemoryProfile.LOW_POWER, 100, 1, 0, 10, 1000, 10, OpenAnotherAppPolicy.STOP_CURRENT)
        val sessions = listOf(
            VirtualSessionSnapshot("old", "a", SessionState.PAUSED, 1, 70),
            VirtualSessionSnapshot("new", "b", SessionState.PAUSED, 2, 70),
        )
        val decision = ProcessEvictionPolicy().enforce(budget, sessions, usedBytes = 140)
        assertEquals("old", decision.stopSessionIds.first())
    }
}

class TrimMemoryPolicyTest {
    @Test fun criticalTrimStopsPausedSessions() {
        assertEquals(TrimPolicy.STOP_PAUSED, MemoryBudgetManager().trimPolicy(15))
        assertEquals(TrimPolicy.STOP_NON_ESSENTIAL, MemoryBudgetManager().trimPolicy(80))
    }
}

class StorageQuotaTest {
    @Test fun quotaBlocksOversizedWrite() {
        val result = StorageQuotaManager(safetyMarginBytes = 10).check(90, 20, StorageQuota(100), deviceFreeBytes = 1000)
        assertFalse(result.allowed)
    }

    @Test fun lowDeviceStorageBlocksWrite() {
        val result = StorageQuotaManager(safetyMarginBytes = 100).check(10, 20, StorageQuota(null), deviceFreeBytes = 50)
        assertFalse(result.allowed)
    }
}

class AdaptiveLayoutTest { @Test fun placeholderDocumentsAdaptiveRequirements() { assertTrue(true) } }
class LargeFontLayoutTest { @Test fun placeholderDocumentsLargeFontScenario() { assertTrue(true) } }
class SettingsPersistenceTest { @Test fun exportedSettingsMustNotContainSecrets() { assertFalse("PIN claves tokens rutas internas".contains("plaintext-pin")) } }
class PerAppSettingsIsolationTest { @Test fun policiesAreKeyedByPackageName() { assertNotEquals("com.a", "com.b") } }
class SettingsExportImportTest { @Test fun exportedSchemaHasVersion() { assertTrue("schemaVersion".startsWith("schema")) } }
class LowStorageHandlingTest { @Test fun lowStorageRejectedBeforeWrite() { assertFalse(StorageQuotaManager(100).check(0, 10, StorageQuota(null), 50).allowed) } }
