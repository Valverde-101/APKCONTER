package com.valcrono.virtualspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLaunchErrorClassifierTest {
    @Test fun applicationOnCreateGetsSpecificCodeAndRootCause() {
        val root = IllegalStateException("real root")
        val failure = RuntimeException("Application.onCreate", root)
        val classified = RuntimeLaunchErrorClassifier.classify(failure, "APPLICATION_ONCREATE", "START_FAILED")
        assertEquals("APPLICATION_CREATE_FAILED", classified.code)
        assertEquals(root::class.java.name, classified.rootCauseClass)
        assertTrue(classified.stackTraceSanitized.orEmpty().contains("real root"))
    }

    @Test fun noClassDefIsDependencyMissingNotGenericStartFailed() {
        val classified = RuntimeLaunchErrorClassifier.classify(NoClassDefFoundError("androidx/startup/Initializer"), "APPLICATION_ONCREATE", "START_FAILED")
        assertEquals("DEPENDENCY_MISSING", classified.code)
    }

    @Test fun allAcceptanceCodesAreDeclared() {
        listOf(
            "APK_ARTIFACT_MISSING", "APK_ARTIFACT_HASH_MISMATCH", "CLASSLOADER_CREATE_FAILED", "CLASS_NOT_FOUND",
            "APPLICATION_CLASS_NOT_FOUND", "APPLICATION_CREATE_FAILED", "APPLICATION_ATTACH_FAILED", "PROVIDER_INITIALIZATION_FAILED",
            "ANDROIDX_STARTUP_FAILED", "ACTIVITY_CLASS_NOT_FOUND", "ACTIVITY_INSTANTIATION_FAILED", "ACTIVITY_ATTACH_RESTRICTED_ANDROID15",
            "ACTIVITY_ATTACH_SIGNATURE_MISMATCH", "ACTIVITY_ONCREATE_FAILED", "ACTIVITY_ONSTART_FAILED", "ACTIVITY_ONRESUME_FAILED",
            "RESOURCE_LOAD_FAILED", "THEME_LOAD_FAILED", "NATIVE_LIBRARY_LOAD_FAILED", "DEPENDENCY_MISSING",
            "ACTIVE_ACK_REJECTED", "PROCESS_DIED", "UNKNOWN_RUNTIME_FAILURE"
        ).forEach { assertTrue("missing $it", RuntimeLaunchErrorCodes.ALL.contains(it)) }
    }
}
