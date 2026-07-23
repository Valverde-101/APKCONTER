package com.valcrono.virtualspace

import android.app.Activity
import android.content.Context
import android.view.View
import com.valcrono.runtime.VirtualContent

sealed interface GuestViewAttachResult {
    data class Success(val view: View) : GuestViewAttachResult
    data class Failure(val errorCode: String, val phase: String, val sanitizedMessage: String) : GuestViewAttachResult
}

interface VirtualRuntimeAdapter {
    fun start(pkg: VirtualPackageEntity): RunningVirtualApplication
    fun attachUi(sessionId: String): VirtualContent? = null
    fun attachGuestView(host: Activity, running: RunningVirtualApplication): GuestViewAttachResult = GuestViewAttachResult.Failure("GENERIC_GUEST_VIEW_UNAVAILABLE", "GUEST_VIEW_ATTACHED", "No hay vista invitada disponible.")
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
    override fun attachGuestView(host: Activity, running: RunningVirtualApplication): GuestViewAttachResult = (running as? GenericRunningVirtualApplication)?.let { runCatching { GuestViewAttachResult.Success(it.attachTo(host)) }.getOrElse { t -> GuestViewAttachResult.Failure(RuntimeLaunchErrorClassifier.classify(t, (host as? BaseRuntimeProxyActivity)?.genericPhases?.lastOrNull(), "GUEST_VIEW_ATTACH_FAILED").code, (host as? BaseRuntimeProxyActivity)?.genericPhases?.lastOrNull() ?: "GUEST_VIEW_ATTACHED", t.message?.take(500) ?: "No se pudo adjuntar la vista invitada.") } } ?: GuestViewAttachResult.Failure("GENERIC_GUEST_VIEW_UNAVAILABLE", "GUEST_VIEW_ATTACHED", "No hay vista invitada disponible.")
}
