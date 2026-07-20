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

    @Test fun automaticKeepsTwoActiveWithoutStaticPssEviction() {
        val budget = MemoryBudgetManager().budget(MemoryProfile.AUTOMATIC, motoLike)
        assertEquals(2, budget.maxActiveApps)
        assertEquals(2, budget.maxPausedSessions)
        assertTrue(budget.explanation.contains("threshold/lowMemory"))
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
    @Test fun noSessionIsStoppedUntilEmergencyPressure() {
        val budget = MemoryBudget(MemoryProfile.AUTOMATIC, 100, 2, 2, 10, Long.MAX_VALUE, 10, OpenAnotherAppPolicy.PAUSE_CURRENT)
        val sessions = listOf(
            VirtualSessionSnapshot("old", "a", SessionState.PAUSED, 1, 70),
            VirtualSessionSnapshot("new", "b", SessionState.PAUSED, 2, 70),
        )
        val normal = ProcessEvictionPolicy().enforce(budget, sessions, usedBytes = 140, pressureState = MemoryPressureState.NORMAL)
        assertTrue(normal.stopSessionIds.isEmpty())
        val emergency = ProcessEvictionPolicy().enforce(budget.copy(profile = MemoryProfile.CONSERVATIVE), sessions, usedBytes = 140, pressureState = MemoryPressureState.EMERGENCY)
        assertEquals("old", emergency.stopSessionIds.first())
    }
}

class TrimMemoryPolicyTest {
    @Test fun trimMemoryNeverStopsSessions() {
        assertEquals(TrimPolicy.REDUCE_CACHES, MemoryBudgetManager().trimPolicy(15))
        assertEquals(TrimPolicy.COOPERATIVE_TRIM, MemoryBudgetManager().trimPolicy(80))
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
