package com.valcrono.virtualspace

import androidx.test.runner.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationOnCreateFailureInstrumentedTest {
    @Test fun fixtureFailureWouldPersistApplicationOnCreateDiagnostics() {
        val root = IllegalStateException("fixture root cause")
        val failure = RuntimeException("Application.onCreate fixture", root)
        val classified = RuntimeLaunchErrorClassifier.classify(failure, "APPLICATION_ONCREATE", "START_FAILED")
        assertEquals("APPLICATION_CREATE_FAILED", classified.code)
        assertNotNull(classified.rootCauseClass)
        assertTrue(classified.stackTraceSanitized.orEmpty().isNotBlank())
    }
}
