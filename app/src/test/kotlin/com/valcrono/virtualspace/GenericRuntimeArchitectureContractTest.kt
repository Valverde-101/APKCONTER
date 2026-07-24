package com.valcrono.virtualspace

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GenericRuntimeArchitectureContractTest {
    private fun src(path: String): String {
        val userDir = File(System.getProperty("user.dir"))
        val candidates = listOf(File(path), File(userDir, path), File(userDir.parentFile ?: userDir, path), File(userDir.parentFile?.parentFile ?: userDir, path))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("Source file not found: $path from ${System.getProperty("user.dir")}")
    }

    @Test fun genericRuntimeDoesNotReturnDiagnosticAsSuccess() {
        val s = src("app/src/main/java/com/valcrono/virtualspace/GenericActivityHost.kt")
        assertTrue(s.contains("Instrumentation().not()") || !s.contains("GenericActivityHost activo"))
        listOf("GenericAndroidRuntimeAdapter", "GenericInstrumentation", "GenericPackageContext", "GenericPackageManagerFacade", "GuestActivityController").forEach { assertTrue(s.contains(it)) }
    }

    @Test fun requiredLaunchPhasesAreDeclared() {
        val host = src("app/src/main/java/com/valcrono/virtualspace/GenericActivityHost.kt")
        val engines = src("app/src/main/java/com/valcrono/virtualspace/RuntimeEngines.kt")
        listOf(
            "APK_ARTIFACT_VALIDATED",
            "CLASSLOADER_READY",
            "RESOURCES_CREATING",
            "RESOURCES_READY",
            "APPLICATION_CLASS_RESOLVING",
            "APPLICATION_INSTANTIATING",
            "APPLICATION_ATTACHED",
            "APPLICATION_ONCREATE",
            "APPLICATION_READY",
            "PROVIDERS_DISCOVERING",
            "PROVIDERS_INITIALIZING",
            "PROVIDERS_READY",
            "ACTIVITY_CLASS_RESOLVING",
            "ACTIVITY_CLASS_RESOLVED",
            "ACTIVITY_SUPERCLASS_INSPECTED",
            "ACTIVITY_CONSTRUCTOR_RESOLVING",
            "ACTIVITY_CONSTRUCTOR_READY",
            "ACTIVITY_CLASS_INITIALIZING",
            "ACTIVITY_INSTANTIATING",
            "ACTIVITY_INSTANTIATED",
            "ACTIVITY_ATTACHED",
            "ACTIVITY_ONCREATE",
            "ACTIVITY_ONRESUME",
        ).forEach { assertTrue(it, host.contains(it)) }
        listOf("ENGINE_SELECTED", "GUEST_VIEW_ATTACHED", "ACTIVE_ACKNOWLEDGED", "STABLE").forEach { assertTrue(it, engines.contains(it)) }
    }

    @Test fun apkPathResolverCodesAreDeclared() {
        val s = src("app/src/main/java/com/valcrono/virtualspace/ApkPathResolver.kt")
        listOf("APK_FILE_MISSING", "APK_PATH_REPAIRED", "APK_HASH_MISMATCH", "APK_CANONICALIZATION_FAILED").forEach { assertTrue(it, s.contains(it)) }
    }
}
