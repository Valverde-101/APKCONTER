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
import dalvik.system.DelegateLastClassLoader
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.util.zip.ZipFile

val GENERIC_LAUNCH_PHASES = RuntimeLaunchPhase.values().map { it.name }

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


class VirtualPackageManagerFacade(private val pkg: VirtualPackageEntity, private val appInfo: ApplicationInfo) {
    val virtualUid: Int = java.util.Objects.hash(pkg.virtualUserId, pkg.packageName).let { if (it < 0) -it else it }
    fun getPackageInfo(packageName: String): android.content.pm.PackageInfo { require(packageName == pkg.packageName) { "VIRTUAL_PACKAGE_NOT_VISIBLE:$packageName" }; return android.content.pm.PackageInfo().apply { this.packageName = pkg.packageName; applicationInfo = ApplicationInfo(appInfo) } }
    fun getApplicationInfo(packageName: String): ApplicationInfo = getPackageInfo(packageName).applicationInfo!!
    fun getLaunchIntentForPackage(packageName: String): Intent? { if (packageName != pkg.packageName) return null; val activity = pkg.launcherTargetActivity ?: pkg.launcherActivityName ?: return null; return Intent().setClassName(pkg.packageName, activity) }
    fun checkPermission(permission: String, packageName: String): Int = if (packageName == pkg.packageName) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    fun getPackagesForUid(uid: Int): Array<String>? = if (uid == virtualUid) arrayOf(pkg.packageName) else null
}
class VirtualContentResolver(private val host: android.content.ContentResolver? = null)
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
class VirtualProviderManager {
    fun initializeProviders(context: Context, loader: ClassLoader, providers: List<android.content.pm.ProviderInfo>, phases: MutableList<String>): List<String> {
        phases += RuntimeLaunchPhase.PROVIDERS_DISCOVERING.name
        val initialized = mutableListOf<String>()
        phases += RuntimeLaunchPhase.PROVIDERS_INITIALIZING.name
        providers.forEach { info ->
            val name = info.name ?: return@forEach
            try {
                val provider = Class.forName(name, true, loader).getDeclaredConstructor().newInstance() as android.content.ContentProvider
                provider.attachInfo(context, info)
                if (!provider.onCreate()) VLog.w("VirtualProviderManager", "Provider onCreate returned false: $name authorities=${info.authority}")
                initialized += "${name}:${info.authority}"
            } catch (t: Throwable) { throw IllegalStateException("PROVIDER_INITIALIZATION_FAILED provider=$name authorities=${info.authority}", t) }
        }
        phases += RuntimeLaunchPhase.PROVIDERS_READY.name
        return initialized
    }
}
class AndroidXStartupBridge { fun recordInitializationProvider(providers: List<android.content.pm.ProviderInfo>): List<String> = providers.filter { it.name == "androidx.startup.InitializationProvider" }.mapNotNull { it.authority } }

enum class ClassSource { ANDROID_BOOT, HOST_APPLICATION, GUEST_BASE_APK, GUEST_SPLIT_APK, UNKNOWN }
data class LoadedClassOrigin(val className: String, val classLoaderName: String?, val dexPath: String?, val source: ClassSource)
object ClassLoaderCollisionDetector {
    private val watched = listOf("androidx.activity", "androidx.appcompat", "androidx.lifecycle", "androidx.savedstate", "androidx.fragment", "androidx.core", "kotlin", "kotlinx.coroutines")
    fun isWatched(name: String) = watched.any { name == it || name.startsWith("$it.") }
}
object GuestClassLoaderFactory {
    fun create(apk: File, opt: File, nativeDir: File?, parent: ClassLoader): ClassLoader = if (Build.VERSION.SDK_INT >= 27) DelegateLastClassLoader(apk.absolutePath, nativeDir?.absolutePath, parent) else DexClassLoader(apk.absolutePath, opt.absolutePath, nativeDir?.absolutePath, parent)
    fun origin(clazz: Class<*>, apk: File, host: Context): LoadedClassOrigin {
        val loaderName = clazz.classLoader?.javaClass?.name ?: "BOOT"
        val source = when { clazz.classLoader == null -> ClassSource.ANDROID_BOOT; clazz.protectionDomain?.codeSource?.location?.path?.contains(apk.name) == true -> ClassSource.GUEST_BASE_APK; clazz.classLoader === host.classLoader -> ClassSource.HOST_APPLICATION; else -> ClassSource.UNKNOWN }
        return LoadedClassOrigin(clazz.name, loaderName, clazz.protectionDomain?.codeSource?.location?.path, source)
    }
}

class GuestActivityController(private val hostContext: Context, private val pkg: VirtualPackageEntity) {
    private val instrumentation = GenericInstrumentation()
    private val phases = mutableListOf<String>()
    fun prepare(): GenericRunningVirtualApplication {
        require(pkg.runtimeMode == "GENERIC_EXPERIMENTAL") { "GENERIC_RUNTIME_MODE_REQUIRED" }
        if (pkg.detectedFramework == "FLUTTER") throw IllegalStateException("FLUTTER_RUNTIME_NOT_IMPLEMENTED packageName=${pkg.packageName}")
        val root = VirtualPackageStorageV1(hostContext.filesDir).root(pkg.packageName)
        val apk = File(pkg.apkInternalPath)
        require(apk.isFile) { "APK_FILE_MISSING packageName=${pkg.packageName} apkPath=${apk.absolutePath}" }; phases += RuntimeLaunchPhase.APK_ARTIFACT_VALIDATED.name
        val nativeDir = NativeLibraryExtractor.extract(apk, File(root, "data/code_cache/native")); val opt = File(root, "data/code_cache/dex").apply { mkdirs() }
        val loader = GuestClassLoaderFactory.create(apk, opt, nativeDir, hostContext.classLoader); phases += RuntimeLaunchPhase.CLASSLOADER_READY.name
        phases += RuntimeLaunchPhase.RESOURCES_CREATING.name; val res = apkResources(apk); phases += RuntimeLaunchPhase.RESOURCES_READY.name
        val appInfo = ApplicationInfo().apply { packageName = pkg.packageName; sourceDir = apk.absolutePath; publicSourceDir = apk.absolutePath; dataDir = File(root, "data").absolutePath; nativeLibraryDir = nativeDir?.absolutePath ?: File(root, "data/code_cache/native/empty").apply { mkdirs() }.absolutePath; className = pkg.applicationClassName }
        val gctx = GenericPackageContext(hostContext, pkg, root, loader, res, appInfo)
        val app = createApplication(gctx, loader); phases += RuntimeLaunchPhase.APPLICATION_READY.name; VirtualProviderManager().initializeProviders(gctx, loader, emptyList(), phases)
        return GenericRunningVirtualApplication(pkg, this, gctx, app, phases)
    }
    fun launchInto(host: Activity): View {
        val running = host as? BaseRuntimeProxyActivity ?: error("HOST_ACTIVITY_REQUIRED")
        val gctx = running.genericContext ?: error("GENERIC_CONTEXT_MISSING")
        val app = running.genericApplication
        val loader = gctx.classLoader
        val activityName = pkg.launcherTargetActivity ?: pkg.launcherActivityName ?: throw IllegalStateException("ACTIVITY_CLASS_NOT_FOUND")
        val intent = Intent().setClassName(pkg.packageName, activityName)
        val activity = instantiateActivity(activityName, loader, apk = File(pkg.apkInternalPath), hostContext = hostContext, phases = running.genericPhases)
        ActivityAttachBridge.select().attach(activity, gctx, app, instrumentation, intent, pkg.label); running.genericPhases += RuntimeLaunchPhase.ACTIVITY_ATTACHED.name
        try { instrumentation.callActivityOnCreate(activity, Bundle()) } catch (t: Throwable) { throw IllegalStateException("ACTIVITY_ONCREATE_FAILED activityName=$activityName", t) }; running.genericPhases += RuntimeLaunchPhase.ACTIVITY_ONCREATE.name
        try { instrumentation.callActivityOnStart(activity); instrumentation.callActivityOnResume(activity) } catch (t: Throwable) { throw IllegalStateException("ACTIVITY_ONRESUME_FAILED activityName=$activityName", t) }; running.genericPhases += RuntimeLaunchPhase.ACTIVITY_ONRESUME.name
        running.genericGuestActivity = activity
        return activity.window.decorView
    }
    private fun apkResources(apk: File): Resources = try { val assets = AssetManager::class.java.getDeclaredConstructor().newInstance(); AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, apk.absolutePath); Resources(assets, hostContext.resources.displayMetrics, hostContext.resources.configuration) } catch (t: Throwable) { throw IllegalStateException("RESOURCE_LOAD_FAILED apkPath=${apk.absolutePath}", t) }
    private fun createApplication(context: Context, loader: ClassLoader): Application {
        val name = pkg.applicationClassName ?: Application::class.java.name
        phases += RuntimeLaunchPhase.APPLICATION_CLASS_RESOLVING.name
        val app = try { phases += RuntimeLaunchPhase.APPLICATION_INSTANTIATING.name; instrumentation.newApplication(loader, name, context) } catch (e: ClassNotFoundException) { throw IllegalStateException("APPLICATION_CLASS_NOT_FOUND applicationClass=$name", e) } catch (t: Throwable) { throw IllegalStateException("APPLICATION_CREATE_FAILED applicationClass=$name", t) }
        phases += RuntimeLaunchPhase.APPLICATION_ATTACHED.name
        try { phases += RuntimeLaunchPhase.APPLICATION_ONCREATE.name; app.onCreate() } catch (t: Throwable) { throw IllegalStateException("APPLICATION_ONCREATE_FAILED applicationClass=$name", t) }
        return app
    }
    private fun instantiateActivity(activityName: String, loader: ClassLoader, apk: File, hostContext: Context, phases: MutableList<String>): Activity {
        phases += RuntimeLaunchPhase.ACTIVITY_CLASS_RESOLVING.name
        val activityClass = try { Class.forName(activityName, false, loader) } catch (e: ClassNotFoundException) { throw IllegalStateException("ACTIVITY_CLASS_NOT_FOUND activityName=$activityName classLoader=${loader.javaClass.name}", e) } catch (e: NoClassDefFoundError) { throw IllegalStateException("ACTIVITY_DEPENDENCY_MISSING activityName=$activityName classLoader=${loader.javaClass.name}", e) } catch (e: VerifyError) { throw IllegalStateException("ACTIVITY_VERIFY_ERROR activityName=$activityName", e) } catch (e: LinkageError) { throw IllegalStateException("ACTIVITY_LINKAGE_ERROR activityName=$activityName", e) }
        phases += RuntimeLaunchPhase.ACTIVITY_CLASS_RESOLVED.name
        val origin = GuestClassLoaderFactory.origin(activityClass, apk, hostContext)
        val superOrigin = activityClass.superclass?.let { GuestClassLoaderFactory.origin(it, apk, hostContext) }
        phases += RuntimeLaunchPhase.ACTIVITY_SUPERCLASS_INSPECTED.name
        require(Activity::class.java.isAssignableFrom(activityClass)) { "ACTIVITY_CLASS_NOT_ACTIVITY activityName=$activityName resolved=${activityClass.name} origin=$origin superclass=$superOrigin" }
        val modifiers = activityClass.modifiers
        require(!Modifier.isAbstract(modifiers) && !Modifier.isInterface(modifiers)) { "ACTIVITY_CLASS_NOT_CONCRETE activityName=$activityName modifiers=$modifiers" }
        @Suppress("UNCHECKED_CAST")
        val constructor: Constructor<out Activity> = try { phases += RuntimeLaunchPhase.ACTIVITY_CONSTRUCTOR_RESOLVING.name; activityClass.asSubclass(Activity::class.java).getDeclaredConstructor() } catch (e: NoSuchMethodException) { throw IllegalStateException("ACTIVITY_NO_EMPTY_CONSTRUCTOR activityName=$activityName origin=$origin", e) }
        phases += RuntimeLaunchPhase.ACTIVITY_CONSTRUCTOR_READY.name
        if (!Modifier.isPublic(constructor.modifiers) || !constructor.canAccess(null)) constructor.isAccessible = true
        try { phases += RuntimeLaunchPhase.ACTIVITY_CLASS_INITIALIZING.name; Class.forName(activityName, true, loader) } catch (e: ExceptionInInitializerError) { throw IllegalStateException("ACTIVITY_STATIC_INITIALIZER_FAILED activityName=$activityName origin=$origin", e) } catch (e: LinkageError) { throw IllegalStateException("ACTIVITY_LINKAGE_ERROR activityName=$activityName origin=$origin", e) }
        return try { phases += RuntimeLaunchPhase.ACTIVITY_INSTANTIATING.name; constructor.newInstance().also { phases += RuntimeLaunchPhase.ACTIVITY_INSTANTIATED.name } } catch (e: java.lang.reflect.InvocationTargetException) { throw IllegalStateException("ACTIVITY_CONSTRUCTOR_FAILED activityName=$activityName origin=$origin constructorModifiers=${constructor.modifiers}", e) } catch (e: InstantiationException) { throw IllegalStateException("ACTIVITY_INSTANTIATION_FAILED activityName=$activityName", e) } catch (e: IllegalAccessException) { throw IllegalStateException("ACTIVITY_CONSTRUCTOR_INACCESSIBLE activityName=$activityName", e) } catch (e: SecurityException) { throw IllegalStateException("ACTIVITY_CONSTRUCTOR_SECURITY_FAILED activityName=$activityName", e) } catch (e: LinkageError) { throw IllegalStateException("ACTIVITY_LINKAGE_ERROR activityName=$activityName", e) }
    }

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
