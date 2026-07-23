package com.valcrono.virtualspace

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import com.valcrono.runtime.VirtualContent
import dalvik.system.DexClassLoader
import java.io.File

class GenericRuntimeAdapter(private val context: Context) : VirtualRuntimeAdapter {
    override fun start(pkg: VirtualPackageEntity): RunningVirtualApplication = GenericActivityHost(context.applicationContext, pkg).start()
}

class GenericActivityHost(private val hostContext: Context, private val pkg: VirtualPackageEntity) {
    fun start(): RunningVirtualApplication {
        require(pkg.runtimeMode == "GENERIC_EXPERIMENTAL") { "GENERIC_RUNTIME_MODE_REQUIRED" }
        val root = VirtualPackageStorageV1(hostContext.filesDir).root(pkg.packageName)
        val apk = File(pkg.apkInternalPath)
        val opt = File(root, "data/code_cache/dex").apply { mkdirs() }
        val nativeDir = File(root, "apk/lib/${pkg.primaryAbi ?: "arm64-v8a"}").apply { mkdirs() }
        val loader = DexClassLoader(apk.absolutePath, opt.absolutePath, nativeDir.absolutePath, hostContext.classLoader)
        val res = apkResources(apk)
        val virtualContext = GenericVirtualContext(hostContext, pkg, root, loader, res)
        val application = createApplication(virtualContext, loader)
        return GenericRunningVirtualApplication(pkg, virtualContext, application)
    }

    private fun apkResources(apk: File): Resources {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, apk.absolutePath)
        return Resources(assets, hostContext.resources.displayMetrics, hostContext.resources.configuration)
    }

    private fun createApplication(context: Context, loader: ClassLoader): Application? {
        val appClass = pkg.applicationClassName ?: return null
        return runCatching {
            val app = loader.loadClass(appClass).asSubclass(Application::class.java).getDeclaredConstructor().newInstance()
            val attach = android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            attach.isAccessible = true
            attach.invoke(app, context)
            app.onCreate()
            app
        }.getOrNull()
    }
}

class GenericVirtualContext(base: Context, private val pkg: VirtualPackageEntity, private val root: File, private val loader: ClassLoader, private val apkResources: Resources) : ContextWrapper(base) {
    override fun getPackageName(): String = pkg.packageName
    override fun getClassLoader(): ClassLoader = loader
    override fun getResources(): Resources = apkResources
    override fun getAssets(): AssetManager = apkResources.assets
    override fun getFilesDir(): File = File(root, "data/files").apply { mkdirs() }
    override fun getCacheDir(): File = File(root, "data/cache").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(File(root, "data/databases").apply { mkdirs() }, name)
}

class GenericRunningVirtualApplication(override val pkg: VirtualPackageEntity, private val context: GenericVirtualContext, private val application: Application?) : RunningVirtualApplication {
    override fun createContent(): VirtualContent = VirtualContent.Column(listOf(
        VirtualContent.Text("GenericActivityHost activo"),
        VirtualContent.Text("${pkg.packageName}/${pkg.launcherActivityName}"),
        VirtualContent.Text("Context, Resources, ClassLoader, directorios virtuales y Application inicializados."),
    ))
    override fun pause() {}
    override fun stop() {}
    override fun destroy() { application?.onTerminate() }
}
