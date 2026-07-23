package com.valcrono.virtualspace

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.View
import com.valcrono.runtime.VirtualContent
import dalvik.system.DexClassLoader
import java.io.File
import java.util.zip.ZipFile

val GENERIC_LAUNCH_PHASES = listOf("APK_PATH_VALIDATED", "DEX_LOADED", "RESOURCES_LOADED", "APPLICATION_CREATED", "GUEST_ACTIVITY_INSTANTIATED", "GUEST_ACTIVITY_ATTACHED", "GUEST_ACTIVITY_CREATED", "GUEST_ACTIVITY_RESUMED")

class GenericRuntimeAdapter(private val context: Context) : VirtualRuntimeAdapter {
    override fun start(pkg: VirtualPackageEntity): RunningVirtualApplication = GenericAndroidRuntimeAdapter(context.applicationContext).start(pkg)
}

class GenericAndroidRuntimeAdapter(private val hostContext: Context) : VirtualRuntimeAdapter {
    override fun start(pkg: VirtualPackageEntity): RunningVirtualApplication = GuestActivityController(hostContext, pkg).prepare()
}

class GenericInstrumentation : Instrumentation()

class GenericPackageContext(base: Context, private val pkg: VirtualPackageEntity, private val root: File, private val loader: ClassLoader, private val apkResources: Resources, private val appInfo: ApplicationInfo) : ContextWrapper(base) {
    override fun getPackageName(): String = pkg.packageName
    override fun getClassLoader(): ClassLoader = loader
    override fun getResources(): Resources = apkResources
    override fun getAssets(): AssetManager = apkResources.assets
    override fun getApplicationInfo(): ApplicationInfo = appInfo
    override fun getFilesDir(): File = File(root, "data/files").apply { mkdirs() }
    override fun getCacheDir(): File = File(root, "data/cache").apply { mkdirs() }
    override fun getCodeCacheDir(): File = File(root, "data/code_cache").apply { mkdirs() }
    override fun getOpPackageName(): String = pkg.packageName
    override fun getAttributionTag(): String? = null
    override fun getApplicationContext(): Context = this
    override fun getPackageManager(): PackageManager = baseContext.packageManager
    override fun getNoBackupFilesDir(): File = File(root, "data/no_backup").apply { mkdirs() }
    override fun getDataDir(): File = File(root, "data").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(File(root, "data/databases").apply { mkdirs() }, name)
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = baseContext.getSharedPreferences("virtual-${pkg.virtualUserId}-${pkg.packageName}-$name", mode)
    override fun getExternalFilesDir(type: String?): File? = File(root, "data/external/files" + (type?.let { "/$it" } ?: "")).apply { mkdirs() }
    override fun getExternalCacheDir(): File? = File(root, "data/external/cache").apply { mkdirs() }
    override fun createPackageContext(packageName: String, flags: Int): Context { require(packageName == pkg.packageName) { "VIRTUAL_PACKAGE_CONTEXT_OUT_OF_SCOPE" }; return this }
    override fun getSystemService(name: String): Any? = VirtualSystemServiceBroker(baseContext).getSystemService(name)
    override fun getContentResolver(): android.content.ContentResolver = baseContext.contentResolver
}

class GenericPackageManagerFacade(private val pkg: VirtualPackageEntity, private val appInfo: ApplicationInfo) {
    fun applicationInfo(): ApplicationInfo = ApplicationInfo(appInfo)
    fun packageName(): String = pkg.packageName
}


class VirtualPackageManagerFacade(private val pkg: VirtualPackageEntity, private val appInfo: ApplicationInfo)
class VirtualContentResolver
class VirtualSharedPreferencesManager
class VirtualSystemServiceBroker(private val host: Context) { fun getSystemService(name: String): Any? = host.getSystemService(name) }

interface ActivityAttachBridge { fun attach(activity: Activity, context: Context, app: Application?, instrumentation: Instrumentation, intent: Intent, title: String)
    companion object { fun select(): ActivityAttachBridge = when { Build.VERSION.SDK_INT >= 35 -> ActivityAttachBridgeApi35(); Build.VERSION.SDK_INT >= 28 -> ActivityAttachBridgeApi28To34(); else -> ActivityAttachBridgeApi24To27() } }
}
abstract class ReflectiveActivityAttachBridge(private val minParams: Int) : ActivityAttachBridge {
    override fun attach(activity: Activity, context: Context, app: Application?, instrumentation: Instrumentation, intent: Intent, title: String) { try { val method = Activity::class.java.declaredMethods.singleOrNull { m -> m.name == "attach" && m.parameterTypes.size >= minParams && m.parameterTypes.any { it == Instrumentation::class.java } && m.parameterTypes.any { it == Application::class.java } } ?: throw NoSuchMethodException("ACTIVITY_ATTACH_SIGNATURE_MISMATCH sdk=${Build.VERSION.SDK_INT}"); method.isAccessible = true; val args = method.parameterTypes.map { p -> when { p == Context::class.java -> context; p == Instrumentation::class.java -> instrumentation; p == Application::class.java -> app; p == Intent::class.java -> intent; p == CharSequence::class.java -> title; p == Int::class.javaPrimitiveType -> 0; p == Boolean::class.javaPrimitiveType -> false; p == android.content.res.Configuration::class.java -> context.resources.configuration; p == android.os.IBinder::class.java -> android.os.Binder(); else -> null } }.toTypedArray(); if (args.any { it == null }) throw IllegalStateException("ACTIVITY_ATTACH_SIGNATURE_HAS_UNSUPPORTED_PARAMETER sdk=${Build.VERSION.SDK_INT} params=${method.parameterTypes.joinToString { it.name }}"); method.invoke(activity, *args) } catch (t: Throwable) { val code = if (Build.VERSION.SDK_INT >= 35) "ACTIVITY_ATTACH_RESTRICTED_ANDROID15" else "ACTIVITY_ATTACH_SIGNATURE_MISMATCH"; throw IllegalStateException("$code activity=${activity::class.java.name}", t) } }
}
class ActivityAttachBridgeApi24To27 : ReflectiveActivityAttachBridge(18)
class ActivityAttachBridgeApi28To34 : ReflectiveActivityAttachBridge(20)
class ActivityAttachBridgeApi35 : ReflectiveActivityAttachBridge(20)
class VirtualProviderManager
class AndroidXStartupBridge

class GuestActivityController(private val hostContext: Context, private val pkg: VirtualPackageEntity) {
    private val instrumentation = GenericInstrumentation()
    private val phases = mutableListOf<String>()
    fun prepare(): GenericRunningVirtualApplication {
        require(pkg.runtimeMode == "GENERIC_EXPERIMENTAL") { "GENERIC_RUNTIME_MODE_REQUIRED" }
        if (pkg.detectedFramework == "FLUTTER") throw IllegalStateException("FLUTTER_RUNTIME_NOT_IMPLEMENTED packageName=${pkg.packageName}")
        val root = VirtualPackageStorageV1(hostContext.filesDir).root(pkg.packageName)
        val apk = File(pkg.apkInternalPath)
        require(apk.isFile) { "APK_FILE_MISSING packageName=${pkg.packageName} apkPath=${apk.absolutePath}" }; phases += "APK_PATH_VALIDATED"
        val nativeDir = NativeLibraryExtractor.extract(apk, File(root, "data/code_cache/native")); val opt = File(root, "data/code_cache/dex").apply { mkdirs() }
        val loader = DexClassLoader(apk.absolutePath, opt.absolutePath, nativeDir?.absolutePath, hostContext.classLoader); phases += "DEX_LOADED"
        val res = apkResources(apk); phases += "RESOURCES_LOADED"
        val appInfo = ApplicationInfo().apply { packageName = pkg.packageName; sourceDir = apk.absolutePath; publicSourceDir = apk.absolutePath; dataDir = File(root, "data").absolutePath; nativeLibraryDir = nativeDir?.absolutePath ?: File(root, "data/code_cache/native/empty").apply { mkdirs() }.absolutePath; className = pkg.applicationClassName }
        val gctx = GenericPackageContext(hostContext, pkg, root, loader, res, appInfo)
        val app = createApplication(gctx, loader); phases += "APPLICATION_CREATED"
        return GenericRunningVirtualApplication(pkg, this, gctx, app, phases)
    }
    fun launchInto(host: Activity): View {
        val running = host as? BaseRuntimeProxyActivity ?: error("HOST_ACTIVITY_REQUIRED")
        val gctx = running.genericContext ?: error("GENERIC_CONTEXT_MISSING")
        val app = running.genericApplication
        val loader = gctx.classLoader
        val activityName = pkg.launcherTargetActivity ?: pkg.launcherActivityName ?: throw IllegalStateException("ACTIVITY_CLASS_NOT_FOUND")
        val intent = Intent().setClassName(pkg.packageName, activityName)
        val activity = try { instrumentation.newActivity(loader, activityName, intent) } catch (e: ClassNotFoundException) { throw IllegalStateException("ACTIVITY_CLASS_NOT_FOUND activityName=$activityName", e) } catch (t: Throwable) { throw IllegalStateException("ACTIVITY_INSTANTIATION_FAILED activityName=$activityName", t) }
        running.genericPhases += "GUEST_ACTIVITY_INSTANTIATED"
        ActivityAttachBridge.select().attach(activity, gctx, app, instrumentation, intent, pkg.label); running.genericPhases += "GUEST_ACTIVITY_ATTACHED"
        try { instrumentation.callActivityOnCreate(activity, Bundle()) } catch (t: Throwable) { throw IllegalStateException("ACTIVITY_ONCREATE_FAILED activityName=$activityName", t) }; running.genericPhases += "GUEST_ACTIVITY_CREATED"
        try { instrumentation.callActivityOnStart(activity); instrumentation.callActivityOnResume(activity) } catch (t: Throwable) { throw IllegalStateException("ACTIVITY_ONRESUME_FAILED activityName=$activityName", t) }; running.genericPhases += "GUEST_ACTIVITY_RESUMED"
        running.genericGuestActivity = activity
        return activity.window.decorView
    }
    private fun apkResources(apk: File): Resources = try { val assets = AssetManager::class.java.getDeclaredConstructor().newInstance(); AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, apk.absolutePath); Resources(assets, hostContext.resources.displayMetrics, hostContext.resources.configuration) } catch (t: Throwable) { throw IllegalStateException("RESOURCE_LOAD_FAILED apkPath=${apk.absolutePath}", t) }
    private fun createApplication(context: Context, loader: ClassLoader): Application { val name = pkg.applicationClassName ?: Application::class.java.name; return try { instrumentation.newApplication(loader, name, context).also { it.onCreate() } } catch (e: ClassNotFoundException) { throw IllegalStateException("APPLICATION_CLASS_NOT_FOUND applicationClass=$name", e) } catch (t: Throwable) { throw IllegalStateException("APPLICATION_CREATE_FAILED applicationClass=$name", t) } }

}

object NativeLibraryExtractor {
    fun extract(apk: File, root: File): File? { var chosen: String? = null; ZipFile(apk).use { zip -> chosen = Build.SUPPORTED_ABIS.firstOrNull { abi -> zip.getEntry("lib/$abi/libdummy.so") != null || zip.entries().asSequence().any { it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") } }; val abi = chosen ?: return null; val outDir = File(root, abi).apply { mkdirs() }; zip.entries().asSequence().filter { it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") && !it.name.contains("..") }.forEach { e -> val out = File(outDir, e.name.substringAfterLast('/')).canonicalFile; require(out.parentFile == outDir.canonicalFile) { "NATIVE_LIB_PATH_INVALID" }; zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }; out.setReadable(true, false); out.setExecutable(true, false); require(out.length() == e.size) { "NATIVE_LIB_HASH_MISMATCH" } }; return outDir } }
}

class GenericRunningVirtualApplication(override val pkg: VirtualPackageEntity, private val controller: GuestActivityController, val context: GenericPackageContext, val application: Application, val phases: MutableList<String>) : RunningVirtualApplication {
    override fun createContent(): VirtualContent? = null
    fun attachTo(host: Activity): View = controller.launchInto(host)
    override fun pause() {}
    override fun stop() {}
    override fun destroy() { application.onTerminate() }
}

class FlutterGenericRuntimeAdapter { fun start(): Nothing = throw IllegalStateException("FLUTTER_RUNTIME_NOT_IMPLEMENTED") }
