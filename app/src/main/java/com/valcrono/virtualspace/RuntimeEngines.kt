package com.valcrono.virtualspace

/** Runtime engine registry for real engines; pending engines must not masquerade as generic success. */
interface RuntimeEngine {
    val id: String
    fun probe(pkg: VirtualPackageEntity, archive: ApkArchiveFactsV1, device: DeviceCapabilities): RuntimeProbeResult
    suspend fun prepare(context: RuntimeEngineContext): PreparedRuntime
    suspend fun launch(runtime: PreparedRuntime): RuntimeLaunchResult
    suspend fun stop(runtime: PreparedRuntime)
}

data class DeviceCapabilities(val supportedAbis: List<String>, val sdkInt: Int, val hasKvmDevice: Boolean = java.io.File("/dev/kvm").canRead() && java.io.File("/dev/kvm").canWrite())
data class RuntimeProbeResult(val engineState: RuntimeEngineState, val requiredCapabilities: Set<String> = emptySet(), val reasons: List<String> = emptyList())
data class RuntimeEngineContext(val packageEntity: VirtualPackageEntity)
data class PreparedRuntime(val engineId: String, val packageName: String)
data class RuntimeLaunchResult(val success: Boolean, val phase: String, val errorCode: String? = null)
enum class RuntimeEngineState { READY, EXPERIMENTAL, ENGINE_PENDING, ENGINE_PENDING_MULTIPROCESS, CAPABILITY_DEGRADED, BLOCKED_HARD, INCOMPATIBLE_ABI, DAMAGED_ARTIFACT }

class CooperativeRuntimeEngine : RuntimeEngine { override val id = "COOPERATIVE"; override fun probe(pkg: VirtualPackageEntity, archive: ApkArchiveFactsV1, device: DeviceCapabilities) = RuntimeProbeResult(RuntimeEngineState.READY); override suspend fun prepare(context: RuntimeEngineContext)=PreparedRuntime(id, context.packageEntity.packageName); override suspend fun launch(runtime: PreparedRuntime)=RuntimeLaunchResult(true,"STABLE"); override suspend fun stop(runtime: PreparedRuntime) {} }
class AndroidGenericRuntimeEngineV2 : RuntimeEngine { override val id = "ANDROID_GENERIC_V2"; override fun probe(pkg: VirtualPackageEntity, archive: ApkArchiveFactsV1, device: DeviceCapabilities)=RuntimeProbeResult(if (archive.supportedAbis.isEmpty() || archive.supportedAbis.any { it in device.supportedAbis }) RuntimeEngineState.EXPERIMENTAL else RuntimeEngineState.INCOMPATIBLE_ABI, setOf("ANDROID_GENERIC_NATIVE_ARM64").takeIf { pkg.hasNativeCode }?.toSet().orEmpty()); override suspend fun prepare(context: RuntimeEngineContext)=PreparedRuntime(id, context.packageEntity.packageName); override suspend fun launch(runtime: PreparedRuntime)=RuntimeLaunchResult(true,"GUEST_VIEW_ATTACHED"); override suspend fun stop(runtime: PreparedRuntime) {} }
class FlutterRuntimeEngine : RuntimeEngine { override val id = "FLUTTER"; override fun probe(pkg: VirtualPackageEntity, archive: ApkArchiveFactsV1, device: DeviceCapabilities)=RuntimeProbeResult(if (archive.hasFlutterAssets) RuntimeEngineState.ENGINE_PENDING else RuntimeEngineState.BLOCKED_HARD, reasons=listOf("Flutter requiere motor separado; no se ejecuta mediante Android Generic V2.")); override suspend fun prepare(context: RuntimeEngineContext)=PreparedRuntime(id, context.packageEntity.packageName); override suspend fun launch(runtime: PreparedRuntime)=RuntimeLaunchResult(false,"ENGINE_SELECTED","ENGINE_VERSION_UNSUPPORTED"); override suspend fun stop(runtime: PreparedRuntime) {} }
class InspectionRuntimeEngine : RuntimeEngine { override val id = "INSPECTION"; override fun probe(pkg: VirtualPackageEntity, archive: ApkArchiveFactsV1, device: DeviceCapabilities)=RuntimeProbeResult(RuntimeEngineState.CAPABILITY_DEGRADED); override suspend fun prepare(context: RuntimeEngineContext)=PreparedRuntime(id, context.packageEntity.packageName); override suspend fun launch(runtime: PreparedRuntime)=RuntimeLaunchResult(false,"ENGINE_SELECTED","INSPECTION_ONLY"); override suspend fun stop(runtime: PreparedRuntime) {} }

enum class RuntimeLaunchPhase { APK_ARTIFACT_RESOLVING, APK_ARTIFACT_VALIDATED, ENGINE_SELECTED, CLASSLOADER_CREATING, CLASSLOADER_READY, NATIVE_LIBRARIES_EXTRACTING, NATIVE_LIBRARIES_READY, RESOURCES_CREATING, RESOURCES_READY, PACKAGE_CONTEXT_CREATING, PACKAGE_CONTEXT_READY, APPLICATION_CLASS_RESOLVING, APPLICATION_INSTANTIATING, APPLICATION_ATTACHED, APPLICATION_ONCREATE, APPLICATION_READY, PROVIDERS_DISCOVERING, PROVIDERS_INITIALIZING, PROVIDERS_READY, ACTIVITY_CLASS_RESOLVING, ACTIVITY_CLASS_RESOLVED, ACTIVITY_SUPERCLASS_INSPECTED, ACTIVITY_CONSTRUCTOR_RESOLVING, ACTIVITY_CONSTRUCTOR_READY, ACTIVITY_CLASS_INITIALIZING, ACTIVITY_INSTANTIATING, ACTIVITY_INSTANTIATED, ACTIVITY_ATTACHING, ACTIVITY_ATTACHED, ACTIVITY_ONCREATE, ACTIVITY_ONSTART, ACTIVITY_ONRESUME, WINDOW_READY, GUEST_VIEW_ATTACHED, ACTIVE_ACKNOWLEDGED, STABLE }

data class RuntimeEngineSelection(val engine: RuntimeEngine, val probe: RuntimeProbeResult)
object RuntimeEngineRegistry {
    fun select(pkg: VirtualPackageEntity, archive: ApkArchiveFactsV1, device: DeviceCapabilities): RuntimeEngineSelection {
        val engine: RuntimeEngine = when { pkg.detectedFramework == "FLUTTER" || archive.hasFlutterAssets -> FlutterRuntimeEngine(); pkg.runtimeMode == "COOPERATIVE" -> CooperativeRuntimeEngine(); pkg.runtimeMode == "GENERIC_EXPERIMENTAL" -> AndroidGenericRuntimeEngineV2(); else -> InspectionRuntimeEngine() }
        return RuntimeEngineSelection(engine, engine.probe(pkg, archive, device))
    }
}
class RuntimeEngineLaunchCoordinator { fun select(pkg: VirtualPackageEntity, archive: ApkArchiveFactsV1, device: DeviceCapabilities): RuntimeEngineSelection = RuntimeEngineRegistry.select(pkg, archive, device) }

class GenericRuntimeSession(val packageEntity: VirtualPackageEntity, val sessionId: String, val launchAttemptId: String)
class RuntimePhaseExecutor(private val onPhase: suspend (RuntimeLaunchPhase, Boolean, Throwable?, Long) -> Unit) { suspend fun <T> runPhase(phase: RuntimeLaunchPhase, block: suspend () -> T): T { val start = android.os.SystemClock.elapsedRealtime(); return try { block().also { onPhase(phase, true, null, android.os.SystemClock.elapsedRealtime() - start) } } catch (t: Throwable) { onPhase(phase, false, t, android.os.SystemClock.elapsedRealtime() - start); throw t } } }
class GenericRuntimeLaunchPipeline(private val executor: RuntimePhaseExecutor) { suspend fun recordClassLoaderReady(block: suspend () -> Unit) = executor.runPhase(RuntimeLaunchPhase.CLASSLOADER_READY, block) }
