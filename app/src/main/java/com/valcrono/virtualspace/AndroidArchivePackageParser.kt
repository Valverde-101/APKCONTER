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
import net.dongliu.apk.parser.ApkFile

class AndroidArchivePackageParser(private val context: Context) {
    data class AndroidIntentFilter(val componentName:String,val actions:List<String>,val categories:List<String>)
    data class AndroidParsedPackage(val packageName:String,val label:String,val versionCode:Long,val versionName:String,val minSdk:Int,val targetSdk:Int,val components:List<VirtualComponent>,val permissions:List<VirtualPermission>,val primaryAbi:String?,val hasNativeLibraries:Boolean,val mainActivity:String?,val entryPointClass:String?, val certificateSha256:String? = null, val compileSdk:Int? = null, val applicationClassName:String? = null, val intentFilters:List<AndroidIntentFilter> = emptyList())
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
        val manifest = readManifest(apk, pkg)
        val entry = appInfo.metaData?.getString("com.valcrono.virtualspace.ENTRY_POINT")
        val apkAbis = nativeLibraryAbis(apk)
        val hostAbis = android.os.Build.SUPPORTED_ABIS.toList()
        val abi = hostAbis.firstOrNull { it in apkAbis } ?: apkAbis.firstOrNull()
        val certSha = signingCertificateSha256(info)
        return AndroidParsedPackage(pkg,label, if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong(), info.versionName ?: "", appInfo.minSdkVersion, appInfo.targetSdkVersion, components, permissions, abi, apkAbis.isNotEmpty(), manifest.launcherActivityName, entry, certSha, if (Build.VERSION.SDK_INT >= 31) appInfo.compileSdkVersion else null, appInfo.className, manifest.intentFilters)
    }

    private data class ManifestFacts(val launcherActivityName:String?, val intentFilters:List<AndroidIntentFilter>)
    private fun readManifest(apk: File, packageName: String): ManifestFacts = runCatching {
        ApkFile(apk).use { apkFile ->
            val xml = apkFile.manifestXml
            val filters = parseIntentFiltersFromManifestXml(xml, packageName)
            val launcher = filters.firstOrNull {
                "android.intent.action.MAIN" in it.actions && "android.intent.category.LAUNCHER" in it.categories
            }?.componentName
            ManifestFacts(launcher, filters)
        }
    }.getOrElse { ManifestFacts(null, emptyList()) }

    private fun parseIntentFiltersFromManifestXml(xml: String, packageName: String): List<AndroidIntentFilter> {
        val out = mutableListOf<AndroidIntentFilter>()
        Regex("<activity[^>]*android:name=\"([^\"]+)\"[\\s\\S]*?</activity>").findAll(xml).forEach { activity ->
            val name = resolveComponentName(packageName, activity.groupValues[1])
            Regex("<intent-filter[\\s\\S]*?</intent-filter>").findAll(activity.value).forEach { filter ->
                val actions = Regex("<action[^>]*android:name=\"([^\"]+)\"").findAll(filter.value).map { it.groupValues[1] }.toList()
                val categories = Regex("<category[^>]*android:name=\"([^\"]+)\"").findAll(filter.value).map { it.groupValues[1] }.toList()
                out += AndroidIntentFilter(name, actions, categories)
            }
        }
        return out
    }

    private fun resolveComponentName(packageName: String, name: String): String = when {
        name.startsWith(".") -> packageName + name
        "." in name -> name
        else -> "$packageName.$name"
    }

    private fun signingCertificateSha256(info: PackageInfo): String? = if (Build.VERSION.SDK_INT >= 28) {
        info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.sha256Hex()
    } else {
        legacySigningCertificateSha256(info)
    }

    @Suppress("DEPRECATION")
    private fun legacySigningCertificateSha256(info: PackageInfo): String? =
        info.signatures?.firstOrNull()?.toByteArray()?.sha256Hex()

    private fun ByteArray.sha256Hex(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { b -> "%02x".format(b) }

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
