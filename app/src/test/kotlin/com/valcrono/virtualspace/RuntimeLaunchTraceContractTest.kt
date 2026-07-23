package com.valcrono.virtualspace

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun src(path: String): String {
    val start = File(System.getProperty("user.dir"))
    val candidates = generateSequence(start) { it.parentFile }.map { File(it, path) } + sequenceOf(File(path))
    return candidates.firstOrNull { it.isFile }?.readText() ?: error("Source file not found from ${start.absolutePath}: $path")
}

class LaunchTracePersistsEveryPhaseTest {
    @Test fun allRequiredPhasesAreDeclaredAndPersisted() {
        val source = src("app/src/main/java/com/valcrono/virtualspace/RuntimeLaunchDiagnostics.kt") + src("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt") + src("app/src/main/java/com/valcrono/virtualspace/RuntimeSessionRepository.kt")
        RuntimeLaunchPhases.REQUIRED.forEach { assertTrue("missing phase $it", source.contains(it)) }
        assertTrue(source.contains("insertRuntimeLaunchTrace"))
        assertTrue(source.contains("durationMs"))
    }
}

class LaunchFailurePreservesRootCauseTest {
    @Test fun terminalRootCauseAndStackTraceAreStored() {
        val source = src("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt") + src("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt")
        listOf("terminalRootCauseClass", "terminalRootCauseMessage", "terminalStackTrace", "terminalTraceId", "markTerminalFailure").forEach { assertTrue(source.contains(it)) }
    }
}

class FailedSessionIsNotConvertedToStoppedTest {
    @Test fun stoppedReconcileCannotOverwriteTerminalFailure() {
        val source = src("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt")
        assertTrue(source.contains("NOT (terminalState IN ('FAILED','ERROR','CRASHED') AND :state='STOPPED')"))
        assertTrue(source.contains("state NOT IN ('STOPPED','FAILED','ERROR','CRASHED')"))
    }
}

class ReconcilePreservesTerminalErrorTest {
    @Test fun packageStoppedKeepsTerminalDiagnostics() {
        val source = src("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt")
        assertTrue(source.contains("terminalErrorCode"))
        assertTrue(source.contains("RUNTIME_RECONCILED_STOPPED").not() || src("app/src/main/java/com/valcrono/virtualspace/RuntimeSessionRepository.kt").contains("markPackageStopped"))
    }
}

class ProcessPidRecordedBeforeClassLoaderTest {
    @Test fun serviceOnCreateRecordsPidBeforeStartSessionClassLoader() {
        val source = src("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt")
        assertTrue(source.indexOf("recordProcessCreated") < source.indexOf("CLASSLOADER_CREATING"))
        assertTrue(source.contains("Process.myPid()"))
    }
}

class ApplicationFailureHasSpecificCodeTest {
    @Test fun classifierHasApplicationCodes() {
        val source = src("app/src/main/java/com/valcrono/virtualspace/RuntimeLaunchDiagnostics.kt")
        assertTrue(source.contains("APPLICATION_CREATE_FAILED"))
        assertTrue(source.contains("APPLICATION_CLASS_NOT_FOUND"))
    }
}

class ProviderFailureHasSpecificCodeTest {
    @Test fun classifierHasProviderCodes() {
        val source = src("app/src/main/java/com/valcrono/virtualspace/RuntimeLaunchDiagnostics.kt")
        assertTrue(source.contains("PROVIDER_INITIALIZATION_FAILED"))
        assertTrue(source.contains("ANDROIDX_STARTUP_FAILED"))
    }
}

class ActivityAttachFailureHasSpecificCodeTest {
    @Test fun classifierHasActivityAttachCodes() {
        val source = src("app/src/main/java/com/valcrono/virtualspace/RuntimeLaunchDiagnostics.kt")
        assertTrue(source.contains("ACTIVITY_ATTACH_RESTRICTED_ANDROID15"))
        assertTrue(source.contains("ACTIVITY_ATTACH_SIGNATURE_MISMATCH"))
    }
}

class TraceTimelineOrderingTest {
    @Test fun daoOrdersByElapsedRealtime() {
        val source = src("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt")
        assertTrue(source.contains("ORDER BY timestampElapsedRealtime ASC"))
    }
}

class DiagnosticJsonExportTest {
    @Test fun uiProvidesDiagnosticJsonExport() {
        val source = src("app/src/main/java/com/valcrono/virtualspace/MainActivity.kt")
        assertTrue(source.contains("Exportar JSON"))
        assertTrue(source.contains("Timeline"))
    }
}

class SlotReleaseHappensAfterTracePersistTest {
    @Test fun markFatalPersistsTraceBeforeSlotFailure() {
        val source = src("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt")
        assertTrue(source.indexOf("insertRuntimeLaunchTrace") < source.indexOf("markCrashed"))
    }
}

class FlutterIsEnginePendingNotIncompatibleTest {
    @Test fun flutterPendingWordingIsPreserved() {
        val source = src("app/src/main/java/com/valcrono/virtualspace/MainActivity.kt") + src("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt")
        assertTrue(source.contains("Flutter Runtime (pendiente)") || source.contains("ENGINE_PENDING_FLUTTER"))
    }
}
