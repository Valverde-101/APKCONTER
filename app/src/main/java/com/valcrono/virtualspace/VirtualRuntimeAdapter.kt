package com.valcrono.virtualspace

import android.app.Activity
import android.content.Context
import android.view.View
import com.valcrono.runtime.VirtualContent

data class GuestLaunchMetadata(val values: Map<String, String?> = emptyMap())

sealed interface GuestViewAttachResult {
    data class Success(val view: View, val completedPhase: RuntimeLaunchPhase = RuntimeLaunchPhase.GUEST_VIEW_ATTACHED) : GuestViewAttachResult
    data class Failure(
        val classifiedError: ClassifiedRuntimeLaunchError,
        val failedPhase: RuntimeLaunchPhase,
        val throwable: Throwable,
        val metadata: GuestLaunchMetadata = GuestLaunchMetadata(),
    ) : GuestViewAttachResult {
        val errorCode: String get() = classifiedError.code
        val phase: String get() = failedPhase.name
        val sanitizedMessage: String get() = RuntimeThrowableProjector.project(classifiedError.code, failedPhase.name, throwable).let { projection ->
            listOfNotNull(projection.exceptionClass, projection.exceptionMessage, projection.rootCauseClass, projection.rootCauseMessage).joinToString(" | ").take(500)
        }
        val projection: RuntimeErrorProjection get() = RuntimeThrowableProjector.project(classifiedError.code, failedPhase.name, throwable)
    }
}

fun guestViewFailure(code: String, phase: RuntimeLaunchPhase, throwable: Throwable, metadata: GuestLaunchMetadata = GuestLaunchMetadata()): GuestViewAttachResult.Failure =
    GuestViewAttachResult.Failure(RuntimeLaunchErrorClassifier.classify(throwable, phase.name, code), phase, throwable, metadata)

interface VirtualRuntimeAdapter {
    fun start(pkg: VirtualPackageEntity): RunningVirtualApplication
    fun attachUi(sessionId: String): VirtualContent? = null
    fun attachGuestView(host: Activity, running: RunningVirtualApplication): GuestViewAttachResult = guestViewFailure("GENERIC_GUEST_VIEW_UNAVAILABLE", RuntimeLaunchPhase.GUEST_VIEW_ATTACHED, IllegalStateException("No hay vista invitada disponible."))
    fun resume(sessionId: String) {}
    fun pause(sessionId: String) {}
    fun stop(sessionId: String) {}
    fun destroy(sessionId: String) { stop(sessionId) }
}

interface RunningVirtualApplication {
    val pkg: VirtualPackageEntity
    val entry: com.valcrono.runtime.VirtualAppEntryPoint? get() = null
    fun createContent(): VirtualContent?
    fun onStart() {}
    fun onResume() {}
    fun pause()
    fun stop()
    fun destroy()
}

class CooperativeRuntimeAdapter(context: Context, repository: VirtualRepository) : VirtualRuntimeAdapter {
    private val host = VirtualRuntimeHost(context, repository)
    override fun start(pkg: VirtualPackageEntity): RunningVirtualApplication = CooperativeRunningVirtualApplication(host.start(pkg))
}

class CooperativeRunningVirtualApplication(private val app: RunningVirtualApp) : RunningVirtualApplication {
    override val pkg: VirtualPackageEntity get() = app.pkg
    override val entry get() = app.entry
    override fun createContent(): VirtualContent = app.entry.createContent()
    override fun onStart() { app.entry.onStart() }
    override fun onResume() { app.entry.onResume() }
    override fun pause() { app.entry.onPause() }
    override fun stop() { app.entry.onStop() }
    override fun destroy() { app.entry.onDestroy() }
}

class GenericApkRuntimeAdapter(private val context: Context) : VirtualRuntimeAdapter {
    private val delegate = GenericRuntimeAdapter(context)
    override fun start(pkg: VirtualPackageEntity): RunningVirtualApplication = delegate.start(pkg)
    override fun attachGuestView(host: Activity, running: RunningVirtualApplication): GuestViewAttachResult = (running as? GenericRunningVirtualApplication)?.let { runCatching { GuestViewAttachResult.Success(it.attachTo(host)) }.getOrElse { t -> val phase = (host as? BaseRuntimeProxyActivity)?.genericPhases?.lastOrNull()?.let { p -> runCatching { RuntimeLaunchPhase.valueOf(p) }.getOrNull() } ?: RuntimeLaunchPhase.GUEST_VIEW_ATTACHED; guestViewFailure("GUEST_VIEW_ATTACH_FAILED", phase, t) } } ?: guestViewFailure("GENERIC_GUEST_VIEW_UNAVAILABLE", RuntimeLaunchPhase.GUEST_VIEW_ATTACHED, IllegalStateException("No hay vista invitada disponible."))
}
