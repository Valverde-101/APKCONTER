package com.valcrono.virtualspace

import android.content.Context
import com.valcrono.runtime.VirtualContent

interface VirtualRuntimeAdapter {
    fun start(pkg: VirtualPackageEntity): RunningVirtualApplication
    fun attachUi(sessionId: String): VirtualContent? = null
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

class GenericApkRuntimeAdapter(private val context: Context) : VirtualRuntimeAdapter by GenericRuntimeAdapter(context)
