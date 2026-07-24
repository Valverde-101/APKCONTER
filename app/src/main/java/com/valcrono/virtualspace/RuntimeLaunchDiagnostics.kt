package com.valcrono.virtualspace

import android.os.Process
import android.os.SystemClock
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ExceptionInInitializerError
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.UndeclaredThrowableException
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

object RuntimeLaunchErrorCodes {
    val ALL = setOf(
        "APK_ARTIFACT_MISSING", "APK_ARTIFACT_HASH_MISMATCH", "CLASSLOADER_CREATE_FAILED", "CLASS_NOT_FOUND",
        "APPLICATION_CLASS_NOT_FOUND", "APPLICATION_CREATE_FAILED", "APPLICATION_ATTACH_FAILED", "PROVIDER_INITIALIZATION_FAILED",
        "ANDROIDX_STARTUP_FAILED", "ACTIVITY_CLASS_NOT_FOUND", "ACTIVITY_DEPENDENCY_MISSING", "ACTIVITY_VERIFY_ERROR",
        "ACTIVITY_CONSTRUCTOR_FAILED", "ACTIVITY_STATIC_INITIALIZER_FAILED", "ACTIVITY_CLASSLOADER_COLLISION",
        "ACTIVITY_INSTANTIATION_FAILED", "ACTIVITY_ATTACH_RESTRICTED_ANDROID15", "ACTIVITY_ATTACH_SIGNATURE_MISMATCH", "ACTIVITY_ONCREATE_FAILED", "ACTIVITY_ONSTART_FAILED", "ACTIVITY_ONRESUME_FAILED",
        "RESOURCE_LOAD_FAILED", "THEME_LOAD_FAILED", "NATIVE_LIBRARY_LOAD_FAILED", "DEPENDENCY_MISSING",
        "ACTIVE_ACK_REJECTED", "PROCESS_DIED", "UNKNOWN_RUNTIME_FAILURE"
    )
}

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

data class RuntimeCauseEntry(val exceptionClass: String, val message: String?)
data class RuntimeErrorProjection(
    val code: String,
    val exceptionClass: String?,
    val exceptionMessage: String?,
    val rootCauseClass: String?,
    val rootCauseMessage: String?,
    val causeChain: List<RuntimeCauseEntry>,
    val stackTraceSanitized: String?,
    val suppressed: List<String>,
    val failedPhase: String
)

object RuntimeThrowableProjector {
    fun unwrap(t: Throwable): Throwable = when (t) {
        is InvocationTargetException -> t.targetException?.let(::unwrap) ?: t
        is ExceptionInInitializerError -> t.exception?.let(::unwrap) ?: t
        is UndeclaredThrowableException -> t.undeclaredThrowable?.let(::unwrap) ?: t
        is ExecutionException -> t.cause?.let(::unwrap) ?: t
        is CompletionException -> t.cause?.let(::unwrap) ?: t
        else -> t
    }
    fun chain(t: Throwable): List<Throwable> {
        val out = mutableListOf<Throwable>(); var cur: Throwable? = t
        while (cur != null && out.none { it === cur }) { out += cur; cur = when (cur) {
            is InvocationTargetException -> cur.targetException ?: cur.cause
            is ExceptionInInitializerError -> cur.exception ?: cur.cause
            is UndeclaredThrowableException -> cur.undeclaredThrowable ?: cur.cause
            is ExecutionException -> cur.cause
            is CompletionException -> cur.cause
            else -> cur.cause
        } }
        return out
    }
    fun project(code: String, failedPhase: String, t: Throwable): RuntimeErrorProjection {
        val c = chain(t); val root = c.lastOrNull()?.let(::unwrap) ?: unwrap(t)
        return RuntimeErrorProjection(code, t::class.java.name, t.message?.take(500), root::class.java.name, root.message?.take(500), c.map { RuntimeCauseEntry(it::class.java.name, it.message?.take(500)) }, RuntimeLaunchErrorClassifier.sanitizeStackTrace(t), t.suppressed.map { it::class.java.name + (it.message?.let { m -> ": $m" } ?: "") }, failedPhase)
    }
}

object RuntimeLaunchErrorClassifier {
    fun classify(t: Throwable?, phase: String? = null, defaultCode: String = "UNKNOWN_RUNTIME_FAILURE"): ClassifiedRuntimeLaunchError {
        if (t == null) return ClassifiedRuntimeLaunchError(defaultCode, null, null, null, null, null)
        val chain = RuntimeThrowableProjector.chain(t)
        val text = chain.joinToString("\n") { listOfNotNull(it::class.java.name, it.message).joinToString(":") }
        val code = when {
            text.contains("APK_ARTIFACT_MISSING") || text.contains("No such file", true) -> "APK_ARTIFACT_MISSING"
            text.contains("APK_HASH_MISMATCH") || text.contains("HASH_MISMATCH") -> "APK_ARTIFACT_HASH_MISMATCH"
            text.contains("VerifyError") -> "ACTIVITY_VERIFY_ERROR"
            text.contains("NoClassDefFoundError") && phase?.startsWith("ACTIVITY") == true -> "ACTIVITY_DEPENDENCY_MISSING"
            text.contains("NoClassDefFoundError") -> "DEPENDENCY_MISSING"
            text.contains("ClassNotFoundException") && text.contains("Application", true) -> "APPLICATION_CLASS_NOT_FOUND"
            text.contains("ClassNotFoundException") && text.contains("Activity", true) -> "ACTIVITY_CLASS_NOT_FOUND"
            text.contains("ClassNotFoundException") -> "CLASS_NOT_FOUND"
            phase == "ACTIVITY_CLASS_INITIALIZING" -> "ACTIVITY_STATIC_INITIALIZER_FAILED"
            phase == "ACTIVITY_INSTANTIATING" -> "ACTIVITY_CONSTRUCTOR_FAILED"
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
            text.contains("ACTIVE_ACK_REJECTED") -> "ACTIVE_ACK_REJECTED"
            text.contains("PROCESS_DIED") -> "PROCESS_DIED"
            defaultCode != "START_FAILED" -> defaultCode
            else -> "UNKNOWN_RUNTIME_FAILURE"
        }
        val root = chain.lastOrNull()?.let(RuntimeThrowableProjector::unwrap) ?: RuntimeThrowableProjector.unwrap(t)
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
