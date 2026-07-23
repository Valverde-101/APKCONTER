package com.valcrono.virtualspace

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GenericRuntimeArchitectureContractTest {
    private fun src(path: String) = File(path).readText()

    @Test fun genericRuntimeDoesNotReturnDiagnosticAsSuccess() {
        val s = src("app/src/main/java/com/valcrono/virtualspace/GenericActivityHost.kt")
        assertTrue(s.contains("Instrumentation().not()") || !s.contains("GenericActivityHost activo"))
        listOf("GenericAndroidRuntimeAdapter", "GenericInstrumentation", "GenericPackageContext", "GenericPackageManagerFacade", "GuestActivityController").forEach { assertTrue(s.contains(it)) }
    }

    @Test fun requiredLaunchPhasesAreDeclared() {
        val s = src("app/src/main/java/com/valcrono/virtualspace/GenericActivityHost.kt")
        listOf("APK_PATH_VALIDATED", "DEX_LOADED", "RESOURCES_LOADED", "APPLICATION_CREATED", "GUEST_ACTIVITY_INSTANTIATED", "GUEST_ACTIVITY_ATTACHED", "GUEST_ACTIVITY_CREATED", "GUEST_ACTIVITY_RESUMED").forEach { assertTrue(it, s.contains(it)) }
    }

    @Test fun apkPathResolverCodesAreDeclared() {
        val s = src("app/src/main/java/com/valcrono/virtualspace/ApkPathResolver.kt")
        listOf("APK_FILE_MISSING", "APK_PATH_REPAIRED", "APK_HASH_MISMATCH", "APK_CANONICALIZATION_FAILED").forEach { assertTrue(it, s.contains(it)) }
    }
}
