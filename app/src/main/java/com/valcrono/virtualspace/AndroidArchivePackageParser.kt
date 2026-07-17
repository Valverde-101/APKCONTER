package com.valcrono.virtualspace

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.valcrono.vpm.ComponentType
import com.valcrono.vpm.VirtualComponent
import com.valcrono.vpm.VirtualPermission
import java.io.File
import java.util.zip.ZipFile

class AndroidArchivePackageParser(private val context: Context) {
    data class AndroidParsedPackage(val packageName:String,val label:String,val versionCode:Long,val versionName:String,val minSdk:Int,val targetSdk:Int,val components:List<VirtualComponent>,val permissions:List<VirtualPermission>,val primaryAbi:String?,val hasNativeLibraries:Boolean,val mainActivity:String?,val entryPointClass:String?)
    fun parse(apk: File): AndroidParsedPackage {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS or PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES
        val info: PackageInfo = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags) ?: error("APK_METADATA_UNAVAILABLE")
        val appInfo = info.applicationInfo ?: error("APK_APPLICATION_INFO_MISSING")
        appInfo.sourceDir = apk.absolutePath; appInfo.publicSourceDir = apk.absolutePath
        val pkg = info.packageName ?: error("APK_PACKAGE_MISSING")
        val label = runCatching { context.packageManager.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg.substringAfterLast('.'))
        val components = mutableListOf<VirtualComponent>()
        info.activities?.forEach { components += VirtualComponent(pkg, it.name, ComponentType.ACTIVITY, it.exported, it.processName) }
        info.services?.forEach { components += VirtualComponent(pkg, it.name, ComponentType.SERVICE, it.exported, it.processName) }
        info.providers?.forEach { components += VirtualComponent(pkg, it.name, ComponentType.PROVIDER, it.exported, it.processName) }
        info.receivers?.forEach { components += VirtualComponent(pkg, it.name, ComponentType.RECEIVER, it.exported, it.processName) }
        val permissions = info.requestedPermissions?.map { VirtualPermission(pkg, it) }.orEmpty()
        val entry = appInfo.metaData?.getString("com.valcrono.virtualspace.ENTRY_POINT")
        val apkAbis = nativeLibraryAbis(apk)
        val hostAbis = android.os.Build.SUPPORTED_ABIS.toList()
        val abi = hostAbis.firstOrNull { it in apkAbis } ?: apkAbis.firstOrNull()
        return AndroidParsedPackage(pkg,label, if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong(), info.versionName ?: "", appInfo.minSdkVersion, appInfo.targetSdkVersion, components, permissions, abi, apkAbis.isNotEmpty(), components.firstOrNull{it.type==ComponentType.ACTIVITY}?.name, entry)
    }

    private fun nativeLibraryAbis(apk: File): List<String> {
        val abis = linkedSetOf<String>()
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val parts = entry.name.split('/')
                if (parts.size >= 3 && parts[0] == "lib" && parts.last().endsWith(".so")) {
                    abis += parts[1]
                }
            }
        }
        return abis.toList()
    }
}
