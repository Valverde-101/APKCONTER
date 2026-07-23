package com.valcrono.virtualspace

interface RuntimeBackend { val id: String; fun probe(): BackendProbeResult }
interface InProcessRuntimeBackend : RuntimeBackend
interface FullVmRuntimeBackend : RuntimeBackend
class Android7VmBackend : FullVmRuntimeBackend { override val id = "ANDROID7_VM"; override fun probe() = DeviceVirtualizationProbe.probe().copy(state = "ANDROID7_VM_BACKEND_NOT_INSTALLED") }
data class BackendProbeResult(val state: String, val architecture: String, val kvmAccessible: Boolean, val memoryBytes: Long, val storageBytes: Long, val selinux: String)
object DeviceVirtualizationProbe { fun probe(): BackendProbeResult { val kvm = java.io.File("/dev/kvm"); return BackendProbeResult("PROBED", System.getProperty("os.arch") ?: "unknown", kvm.exists() && kvm.canRead() && kvm.canWrite(), Runtime.getRuntime().maxMemory(), java.io.File(".").usableSpace, runCatching { java.io.File("/sys/fs/selinux/enforce").readText().trim() }.getOrDefault("unknown")) } }
