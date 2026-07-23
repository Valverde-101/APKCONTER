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
    data class AndroidParsedPackage(val packageName:String,val label:String,val versionCode:Long,val versionName:String,val minSdk:Int,val targetSdk:Int,val components:List<VirtualComponent>,val permissions:List<VirtualPermission>,val primaryAbi:String?,val hasNativeLibraries:Boolean,val mainActivity:String?,val entryPointClass:String?, val certificateSha256:String? = null, val compileSdk:Int? = null, val applicationClassName:String? = null, val intentFilters:List<AndroidIntentFilter> = emptyList(), val launcherAliasName:String? = null, val launcherTargetActivity:String? = null, val nativeLibraries:List<String> = emptyList(), val framework:String = "ANDROID", val highRiskApis:List<String> = emptyList(), val declaredProcesses:List<String> = emptyList())
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
        val nativeLibs = nativeLibraries(apk)
        val apkAbis = nativeLibs.mapNotNull { it.split('/').getOrNull(1) }.distinct()
        val hostAbis = android.os.Build.SUPPORTED_ABIS.toList()
        val abi = hostAbis.firstOrNull { it in apkAbis } ?: apkAbis.firstOrNull()
        val certSha = signingCertificateSha256(info)
        val framework = detectFramework(apk, components, nativeLibs)
        val processes = components.mapNotNull { it.processName }.filter { it != pkg }.distinct()
        val highRisk = detectHighRiskApis(permissions.map { it.name }, components, processes, apk)
        return AndroidParsedPackage(pkg,label, if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong(), info.versionName ?: "", appInfo.minSdkVersion, appInfo.targetSdkVersion, components, permissions, abi, apkAbis.isNotEmpty(), manifest.launcherActivityName, entry, certSha, if (Build.VERSION.SDK_INT >= 31) appInfo.compileSdkVersion else null, appInfo.className, manifest.intentFilters, manifest.launcherAliasName, manifest.launcherTargetActivity, nativeLibs, framework, highRisk, processes)
    }

    private data class ManifestFacts(val launcherActivityName:String?, val intentFilters:List<AndroidIntentFilter>, val launcherAliasName:String? = null, val launcherTargetActivity:String? = null)
    private fun readManifest(apk: File, packageName: String): ManifestFacts = runCatching {
        ApkFile(apk).use { apkFile ->
            val xml = apkFile.manifestXml
            val parsed = parseIntentFiltersFromManifestXml(xml, packageName)
            ManifestFacts(parsed.launcherActivityName, parsed.intentFilters, parsed.launcherAliasName, parsed.launcherTargetActivity)
        }
    }.getOrElse { ManifestFacts(null, emptyList()) }

    private data class ParsedManifestFilters(val intentFilters:List<AndroidIntentFilter>, val launcherActivityName:String?, val launcherAliasName:String?, val launcherTargetActivity:String?)
    private fun parseIntentFiltersFromManifestXml(xml: String, packageName: String): ParsedManifestFilters {
        val out = mutableListOf<AndroidIntentFilter>()
        var launcherActivity: String? = null
        var launcherAlias: String? = null
        var launcherTarget: String? = null
        fun collect(tag: String, wholeTag: String, body: String) {
            val name = Regex("android:name=\"([^\"]+)\"").find(wholeTag)?.groupValues?.get(1) ?: return
            val component = resolveComponentName(packageName, name)
            val target = Regex("android:targetActivity=\"([^\"]+)\"").find(wholeTag)?.groupValues?.get(1)?.let { resolveComponentName(packageName, it) }
            Regex("""<intent-filter[\s\S]*?</intent-filter>""").findAll(body).forEach { filter ->
                val actions = Regex("<action[^>]*android:name=\"([^\"]+)\"").findAll(filter.value).map { it.groupValues[1] }.toList()
                val categories = Regex("<category[^>]*android:name=\"([^\"]+)\"").findAll(filter.value).map { it.groupValues[1] }.toList()
                out += AndroidIntentFilter(component, actions, categories)
                if (launcherActivity == null && "android.intent.action.MAIN" in actions && "android.intent.category.LAUNCHER" in categories) {
                    if (tag == "activity-alias") { launcherAlias = component; launcherTarget = target; launcherActivity = target ?: component } else { launcherActivity = component; launcherTarget = component }
                }
            }
        }
        Regex("""<activity\b([^>]*)>[\s\S]*?</activity>""").findAll(xml).forEach { collect("activity", it.groupValues[1], it.value) }
        Regex("""<activity-alias\b([^>]*)>[\s\S]*?</activity-alias>""").findAll(xml).forEach { collect("activity-alias", it.groupValues[1], it.value) }
        return ParsedManifestFilters(out, launcherActivity, launcherAlias, launcherTarget)
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

    private fun nativeLibraries(apk: File): List<String> {
        val libs = linkedSetOf<String>()
        ZipFile(apk).use { zip -> zip.entries().asSequence().forEach { entry -> if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) libs += entry.name } }
        return libs.toList()
    }
    private fun detectFramework(apk: File, components: List<VirtualComponent>, nativeLibs: List<String>): String = ZipFile(apk).use { zip ->
        when {
            nativeLibs.any { it.endsWith("/libflutter.so") || it.endsWith("/libapp.so") } || zip.getEntry("assets/flutter_assets/NOTICES.Z") != null || zip.getEntry("assets/flutter_assets/AssetManifest.json") != null || components.any { it.name.contains("FlutterActivity") } -> "FLUTTER"
            nativeLibs.any { it.contains("reactnative", true) || it.contains("hermes", true) } -> "REACT_NATIVE"
            nativeLibs.any { it.contains("unity", true) || it.contains("il2cpp", true) } -> "UNITY"
            else -> "ANDROID"
        }
    }
    private fun detectHighRiskApis(permissions: List<String>, components: List<VirtualComponent>, processes: List<String>, apk: File): List<String> {
        val risks = linkedSetOf<String>()
        if (permissions.any { it.contains("BIND_ACCESSIBILITY_SERVICE") }) risks += "ACCESSIBILITY"
        if (permissions.any { it.contains("BIND_VPN_SERVICE") }) risks += "VPN"
        if (permissions.any { it.contains("BIND_DEVICE_ADMIN") } || components.any { it.name.contains("DeviceAdmin", true) }) risks += "DEVICE_ADMIN"
        if (processes.isNotEmpty()) risks += "MULTIPROCESS"
        if (permissions.any { it.startsWith("android.permission.") && (it.contains("PACKAGE_USAGE_STATS") || it.contains("MANAGE_") || it.contains("QUERY_ALL_PACKAGES") || it.contains("SYSTEM_ALERT_WINDOW")) }) risks += "SYSTEM_PERMISSION"
        if (permissions.any { it.contains("PLAY_INTEGRITY", true) } || ZipFile(apk).use { z -> z.entries().asSequence().any { it.name.contains("integrity", true) } }) risks += "PLAY_INTEGRITY"
        if (components.any { it.name.contains("Shizuku", true) } || permissions.any { it.contains("shizuku", true) }) risks += "SHIZUKU"
        if (components.any { it.name.contains("Service") && (it.name.contains("Root", true) || it.name.contains("Privileged", true)) }) risks += "PRIVILEGED_SERVICE"
        return risks.toList()
    }
}
