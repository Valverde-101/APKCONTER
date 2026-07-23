package com.valcrono.virtualspace

import android.os.Process
import android.os.SystemClock
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

object RuntimeLaunchPhases {
    val REQUIRED = listOf(
        "LAUNCH_REQUEST_CREATED", "SLOT_RESERVED", "PROCESS_START_REQUESTED", "PROCESS_CREATED", "SERVICE_CONNECTED",
        "APK_ARTIFACT_RESOLVING", "APK_ARTIFACT_VALIDATED", "ENGINE_SELECTED", "CLASSLOADER_CREATING", "CLASSLOADER_READY",
        "RESOURCES_CREATING", "RESOURCES_READY", "APPLICATION_INSTANTIATING", "APPLICATION_ATTACHED", "PROVIDERS_INITIALIZING",
        "PROVIDERS_READY", "APPLICATION_ONCREATE", "ACTIVITY_INSTANTIATING", "ACTIVITY_ATTACHING", "ACTIVITY_ATTACHED",
        "ACTIVITY_ONCREATE", "ACTIVITY_ONSTART", "ACTIVITY_ONRESUME", "GUEST_VIEW_ATTACHED", "ACTIVE_ACKNOWLEDGED",
        "STABLE", "FAILED", "STOPPED_BY_USER"
    )
}

data class ClassifiedRuntimeLaunchError(
    val code: String,
    val exceptionClass: String?,
    val exceptionMessage: String?,
    val rootCauseClass: String?,
    val rootCauseMessage: String?,
    val stackTraceSanitized: String?
)

object RuntimeLaunchErrorClassifier {
    fun classify(t: Throwable?, phase: String? = null, defaultCode: String = "UNKNOWN_RUNTIME_FAILURE"): ClassifiedRuntimeLaunchError {
        if (t == null) return ClassifiedRuntimeLaunchError(defaultCode, null, null, null, null, null)
        val chain = generateSequence(t) { it.cause }.toList()
        val text = chain.joinToString("\n") { listOfNotNull(it::class.java.name, it.message).joinToString(":") }
        val code = when {
            text.contains("APK_ARTIFACT_MISSING") || text.contains("No such file", true) -> "APK_ARTIFACT_MISSING"
            text.contains("APK_HASH_MISMATCH") || text.contains("HASH_MISMATCH") -> "APK_ARTIFACT_HASH_MISMATCH"
            text.contains("ClassNotFoundException") && text.contains("Application", true) -> "APPLICATION_CLASS_NOT_FOUND"
            text.contains("ClassNotFoundException") && text.contains("Activity", true) -> "ACTIVITY_CLASS_NOT_FOUND"
            text.contains("ClassNotFoundException") || text.contains("NoClassDefFoundError") -> "CLASS_NOT_FOUND"
            phase == "CLASSLOADER_CREATING" || text.contains("DexClassLoader") -> "CLASSLOADER_CREATE_FAILED"
            phase == "APPLICATION_ATTACHED" -> "APPLICATION_ATTACH_FAILED"
            phase == "APPLICATION_ONCREATE" -> "APPLICATION_CREATE_FAILED"
            phase == "PROVIDERS_INITIALIZING" && text.contains("androidx.startup", true) -> "ANDROIDX_STARTUP_FAILED"
            phase == "PROVIDERS_INITIALIZING" -> "PROVIDER_INITIALIZATION_FAILED"
            phase == "ACTIVITY_ATTACHING" && text.contains("Android 15", true) -> "ACTIVITY_ATTACH_RESTRICTED_ANDROID15"
            phase == "ACTIVITY_ATTACHING" && text.contains("signature", true) -> "ACTIVITY_ATTACH_SIGNATURE_MISMATCH"
            phase == "ACTIVITY_ATTACHING" -> "ACTIVITY_ATTACH_RESTRICTED_ANDROID15"
            phase == "ACTIVITY_ONCREATE" -> "ACTIVITY_ONCREATE_FAILED"
            phase == "ACTIVITY_ONSTART" -> "ACTIVITY_ONSTART_FAILED"
            phase == "ACTIVITY_ONRESUME" -> "ACTIVITY_ONRESUME_FAILED"
            text.contains("Resources", true) -> "RESOURCE_LOAD_FAILED"
            text.contains("theme", true) -> "THEME_LOAD_FAILED"
            text.contains("UnsatisfiedLinkError") -> "NATIVE_LIBRARY_LOAD_FAILED"
            text.contains("NoClassDefFoundError") -> "DEPENDENCY_MISSING"
            text.contains("ACTIVE_ACK_REJECTED") -> "ACTIVE_ACK_REJECTED"
            text.contains("PROCESS_DIED") -> "PROCESS_DIED"
            defaultCode != "START_FAILED" -> defaultCode
            else -> "UNKNOWN_RUNTIME_FAILURE"
        }
        val root = chain.last()
        return ClassifiedRuntimeLaunchError(code, t::class.java.name, t.message?.take(500), root::class.java.name, root.message?.take(500), sanitizeStackTrace(t))
    }

    fun sanitizeStackTrace(t: Throwable): String = StringWriter().also { sw -> t.printStackTrace(PrintWriter(sw)) }.toString()
        .replace(Regex("/data/user/\\d+/[^\\s:]+"), "/data/user/<user>/<app>")
        .replace(Regex("/storage/emulated/\\d+"), "/storage/emulated/<user>")
        .take(8000)
}

suspend fun ValcronoDatabase.insertRuntimeLaunchTrace(
    sessionId: String,
    launchAttemptId: String,
    slotId: String?,
    packageName: String,
    virtualUserId: Int,
    runtimeEngine: String,
    phase: String,
    stateBefore: String?,
    stateAfter: String?,
    success: Boolean,
    throwable: Throwable? = null,
    startedElapsed: Long = SystemClock.elapsedRealtime(),
    metadataJson: String = "{}",
    pid: Int? = Process.myPid(),
    processName: String? = null,
): String {
    val classified = RuntimeLaunchErrorClassifier.classify(throwable, phase, if (success) "" else "UNKNOWN_RUNTIME_FAILURE")
    val traceId = UUID.randomUUID().toString()
    runtimeLaunchTraces().insertTrace(RuntimeLaunchTraceEntity(traceId, sessionId, launchAttemptId, slotId, packageName, virtualUserId, runtimeEngine, phase, stateBefore, stateAfter, pid, processName, Thread.currentThread().name, System.currentTimeMillis(), SystemClock.elapsedRealtime(), (SystemClock.elapsedRealtime() - startedElapsed).coerceAtLeast(0), success, classified.code.takeIf { !success }, classified.exceptionClass, classified.exceptionMessage, classified.rootCauseClass, classified.rootCauseMessage, classified.stackTraceSanitized, metadataJson))
    return traceId
}
