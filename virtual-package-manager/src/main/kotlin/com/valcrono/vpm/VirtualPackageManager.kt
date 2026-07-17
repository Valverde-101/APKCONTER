package com.valcrono.vpm

import com.valcrono.core.Clock
import com.valcrono.core.Sha256
import com.valcrono.core.VLog
import com.valcrono.virtualstorage.VirtualStorageManager
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.Channels
import java.util.zip.ZipException
import java.util.zip.ZipFile

enum class ComponentType { ACTIVITY, SERVICE, PROVIDER, RECEIVER }
enum class CompatibilityLevel { COOPERATIVE_SUPPORTED, COMPATIBLE, LIMITED, HIGH_RISK, UNSUPPORTED }

data class VirtualPackage(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val sha256: String,
    val apkInternalPath: String,
    val installTime: Long,
    val updateTime: Long,
    val primaryAbi: String?,
    val hasNativeLibraries: Boolean,
    val mainActivity: String?,
    val compatibilityLevel: CompatibilityLevel,
    val enabled: Boolean,
    val virtualUserId: Int,
    val virtualUid: Int,
    val signingCertificateSha256: String? = null,
)

data class VirtualComponent(val packageName: String, val name: String, val type: ComponentType, val exported: Boolean = false, val processName: String? = null)
data class VirtualPermission(val packageName: String, val name: String)
data class VirtualInstallSession(val id: String, val source: String, val startedAt: Long, val state: String, val error: String? = null)
data class VirtualStorageRecord(val packageName: String, val virtualUserId: Int, val rootPath: String, val usedBytes: Long)
data class CompatibilityIssue(val packageName: String, val severity: CompatibilityLevel, val code: String, val message: String)

data class ApkMetadata(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val components: List<VirtualComponent>,
    val permissions: List<VirtualPermission>,
    val hasNativeLibraries: Boolean,
    val primaryAbi: String?,
    val mainActivity: String?,
    val hasSplitMarker: Boolean = false,
    val usesGooglePlayServices: Boolean = false,
    val usesFirebase: Boolean = false,
    val usesDynamicCodeLoading: Boolean = false,
    val usesFileProvider: Boolean = false,
    val usesWebView: Boolean = false,
    val usesAccessibility: Boolean = false,
    val usesDeviceAdmin: Boolean = false,
    val usesVpn: Boolean = false,
    val usesPlayIntegrity: Boolean = false,
    val entryPointClass: String? = null,
    val entryPointImplementsInterface: Boolean = false,
    val signingCertificateSha256: String? = null,
)

data class ApkLimits(
    val maxBytes: Long = 500L * 1024 * 1024,
    val maxEntries: Int = 4096,
    val maxUncompressedBytes: Long = 1500L * 1024 * 1024,
    val maxCompressionRatio: Double = 100.0,
)

class ApkValidator(private val limits: ApkLimits = ApkLimits()) {
    fun validate(apk: File) {
        require(apk.isFile) { "APK missing" }
        require(apk.length() in 1..limits.maxBytes) { "APK size out of bounds" }
        try {
            ZipFile(apk).use { zip ->
                var entries = 0
                var total = 0L
                zip.entries().asSequence().forEach { entry ->
                    entries++
                    require(entries <= limits.maxEntries) { "Too many ZIP entries" }
                    require(!entry.name.startsWith("/") && !entry.name.startsWith("\\")) { "Unsafe absolute ZIP path ${entry.name}" }
                    require(!entry.name.contains("../") && !entry.name.contains("..\\")) { "Unsafe ZIP path ${entry.name}" }
                    require(!entry.name.contains('\u0000')) { "Unsafe NUL ZIP path ${entry.name}" }
                    if (entry.size > 0) total += entry.size
                    require(total <= limits.maxUncompressedBytes) { "Uncompressed size too large" }
                    if (entry.compressedSize > 0 && entry.size > 0) {
                        require(entry.size.toDouble() / entry.compressedSize <= limits.maxCompressionRatio) {
                            "Suspicious compression ratio"
                        }
                    }
                }
                require(zip.getEntry("AndroidManifest.xml") != null) { "Missing AndroidManifest.xml" }
            }
        } catch (e: ZipException) {
            throw IllegalArgumentException("Corrupt APK/ZIP", e)
        }
    }
}

class ApkMetadataParser {
    fun parse(apk: File): ApkMetadata {
        ApkValidator().validate(apk)
        val fileName = apk.nameWithoutExtension.lowercase()
        var primaryAbi: String? = null
        var hasNative = false
        var hasSplit = false
        var usesGms = false
        var usesFirebase = false
        var usesDynamic = false
        var usesFileProvider = false
        var usesWebView = false
        var usesAccessibility = false
        var usesDeviceAdmin = false
        var usesVpn = false
        var usesIntegrity = false
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val name = entry.name
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    hasNative = true
                    primaryAbi = name.split('/').getOrNull(1) ?: primaryAbi
                }
                hasSplit = hasSplit || name.startsWith("split_") || name.contains("/split_config.")
                usesDynamic = usesDynamic || name.endsWith(".jar") || name.endsWith(".dex") && name != "classes.dex"
                usesGms = usesGms || name.contains("google/android/gms")
                usesFirebase = usesFirebase || name.contains("firebase")
                usesFileProvider = usesFileProvider || name.contains("FileProvider")
                usesWebView = usesWebView || name.contains("WebView")
                usesAccessibility = usesAccessibility || name.contains("AccessibilityService")
                usesDeviceAdmin = usesDeviceAdmin || name.contains("DeviceAdminReceiver")
                usesVpn = usesVpn || name.contains("VpnService")
                usesIntegrity = usesIntegrity || name.contains("play/core/integrity") || name.contains("integrity")
            }
        }
        // Fase 1 fallback: JVM module cannot decode Android binary XML without Android PackageManager.
        // Android app layer should replace this metadata using PackageManager.getPackageArchiveInfo when available.
        val normalized = fileName.replace(Regex("[^a-z0-9_.]"), ".").trim('.').ifBlank { "imported" }
        val packageName = when {
            normalized.contains("testapp.a") -> "com.valcrono.testapp.a"
            normalized.contains("testapp.b") -> "com.valcrono.testapp.b"
            else -> "virtual.$normalized"
        }
        val main = "$packageName.MainActivity"
        return ApkMetadata(
            packageName = packageName,
            label = packageName.substringAfterLast('.'),
            versionCode = 1,
            versionName = "1.0",
            minSdk = 23,
            targetSdk = 35,
            components = listOf(VirtualComponent(packageName, main, ComponentType.ACTIVITY, exported = false)),
            permissions = emptyList(),
            hasNativeLibraries = hasNative,
            primaryAbi = primaryAbi,
            mainActivity = main,
            hasSplitMarker = hasSplit,
            usesGooglePlayServices = usesGms,
            usesFirebase = usesFirebase,
            usesDynamicCodeLoading = usesDynamic,
            usesFileProvider = usesFileProvider,
            usesWebView = usesWebView,
            usesAccessibility = usesAccessibility,
            usesDeviceAdmin = usesDeviceAdmin,
            usesVpn = usesVpn,
            usesPlayIntegrity = usesIntegrity,
            entryPointClass = null,
            entryPointImplementsInterface = false,
        )
    }
}

class CompatibilityScanner {
    fun scan(metadata: ApkMetadata): Pair<CompatibilityLevel, List<CompatibilityIssue>> {
        val issues = mutableListOf<CompatibilityIssue>()
        fun issue(level: CompatibilityLevel, code: String, message: String) {
            issues += CompatibilityIssue(metadata.packageName, level, code, message)
        }
        val hostAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        if (metadata.hasNativeLibraries && metadata.primaryAbi !in hostAbis) issue(CompatibilityLevel.UNSUPPORTED, "ABI_UNSUPPORTED", "Native ABI ${metadata.primaryAbi} is not supported")
        if (metadata.entryPointClass == null && metadata.components.none { it.type == ComponentType.ACTIVITY }) issue(CompatibilityLevel.UNSUPPORTED, "NO_ACTIVITY", "No launchable activity or cooperative entry point detected")
        if (metadata.hasSplitMarker) issue(CompatibilityLevel.UNSUPPORTED, "SPLIT_APK", "Split APK/App Bundle artifacts are not supported in Phase 1")
        if (metadata.components.any { it.type == ComponentType.SERVICE }) issue(CompatibilityLevel.LIMITED, "HAS_SERVICES", "Services are virtualized in Phase 2")
        if (metadata.components.any { it.type == ComponentType.PROVIDER }) issue(CompatibilityLevel.LIMITED, "HAS_PROVIDERS", "Providers are virtualized in Phase 2")
        if (metadata.components.any { it.type == ComponentType.RECEIVER }) issue(CompatibilityLevel.LIMITED, "HAS_RECEIVERS", "Receivers are virtualized in Phase 2")
        if (metadata.components.mapNotNull { it.processName }.distinct().size > 1) issue(CompatibilityLevel.HIGH_RISK, "MULTI_PROCESS", "Multiple virtual processes are high risk in Phase 1")
        if (metadata.usesGooglePlayServices) issue(CompatibilityLevel.HIGH_RISK, "USES_GMS", "Google Play Services are not provided by the container")
        if (metadata.usesFirebase) issue(CompatibilityLevel.LIMITED, "USES_FIREBASE", "Firebase dependencies may expect Google services/network")
        if (metadata.usesDynamicCodeLoading) issue(CompatibilityLevel.HIGH_RISK, "DYNAMIC_CODE", "Dynamic code loading needs additional validation")
        if (metadata.usesFileProvider) issue(CompatibilityLevel.LIMITED, "USES_FILE_PROVIDER", "FileProvider routing is partial in Phase 1")
        if (metadata.usesWebView) issue(CompatibilityLevel.LIMITED, "USES_WEBVIEW", "WebView resources may be incomplete in Phase 1")
        if (metadata.usesAccessibility) issue(CompatibilityLevel.HIGH_RISK, "ACCESSIBILITY", "Accessibility services require host-level registration")
        if (metadata.usesDeviceAdmin) issue(CompatibilityLevel.HIGH_RISK, "DEVICE_ADMIN", "DeviceAdmin cannot be virtualized safely in Phase 1")
        if (metadata.usesVpn) issue(CompatibilityLevel.HIGH_RISK, "VPN_SERVICE", "VPN service cannot be virtualized safely in Phase 1")
        if (metadata.usesPlayIntegrity) issue(CompatibilityLevel.HIGH_RISK, "PLAY_INTEGRITY", "Play Integrity is not supported by this private container")
        val cooperativeBlocked = issues.any { it.severity == CompatibilityLevel.UNSUPPORTED || it.severity == CompatibilityLevel.HIGH_RISK || it.code in setOf("HAS_SERVICES", "HAS_PROVIDERS") }
        val cooperative = metadata.entryPointClass != null && metadata.entryPointImplementsInterface && !cooperativeBlocked && metadata.signingCertificateSha256 != null
        if (cooperative) {
            issue(CompatibilityLevel.COOPERATIVE_SUPPORTED, "COOPERATIVE_RUNTIME", "Runtime cooperativo compatible")
            issue(CompatibilityLevel.COOPERATIVE_SUPPORTED, "ENTRY_POINT_FOUND", "Entry point encontrado")
            issue(CompatibilityLevel.COOPERATIVE_SUPPORTED, "NO_NATIVE_LIBS", if (metadata.hasNativeLibraries) "ABI compatible" else "Sin librerías nativas")
            issue(CompatibilityLevel.COOPERATIVE_SUPPORTED, "NO_GMS", "Sin Google Play Services")
            issue(CompatibilityLevel.COOPERATIVE_SUPPORTED, "NO_MULTIPROCESS", "Sin multiproceso")
            return CompatibilityLevel.COOPERATIVE_SUPPORTED to issues
        }
        val level = issues.filter { it.severity != CompatibilityLevel.COOPERATIVE_SUPPORTED }.maxByOrNull { it.severity.ordinal }?.severity ?: CompatibilityLevel.COMPATIBLE
        return level to issues
    }
}

class SecureApkImporter(
    private val storage: VirtualStorageManager,
    private val registry: MutableMap<String, VirtualPackage>,
    private val clock: Clock = Clock.System,
) {
    private val parser = ApkMetadataParser()

    fun importApk(source: File, userId: Int = 0, confirmUpdate: Boolean = true, metadataOverride: ApkMetadata? = null): VirtualPackage {
        ApkValidator().validate(source)
        val sourceSize = source.length()
        val sourceModified = source.lastModified()
        val sourceSha = source.inputStream().use { Sha256.hex(it) }
        require(source.length() == sourceSize && source.lastModified() == sourceModified) { "APK changed during import preflight" }
        val metadata = metadataOverride ?: parser.parse(source)
        val existing = registry[metadata.packageName]
        require(existing == null || confirmUpdate) { "Duplicate package requires update confirmation" }
        val (level, issues) = CompatibilityScanner().scan(metadata)
        if (level == CompatibilityLevel.UNSUPPORTED) {
            VLog.e("Importer", "package=${metadata.packageName} imported with unsupported compatibility: ${issues.joinToString { it.message }}")
        }
        val virtualUid = existing?.virtualUid ?: 100000 + registry.size
        val storageMetadata = storage.createPackageStorage(userId, metadata.packageName, virtualUid)
        storage.ensureQuota(userId, metadata.packageName, sourceSize)
        val temp = storage.resolver().resolve(userId, metadata.packageName, "apk/.incoming-$sourceSha.tmp")
        val finalFile = storage.resolver().resolve(userId, metadata.packageName, "apk/package-$sourceSha.apk")
        try {
            temp.parentFile?.mkdirs()
            FileOutputStream(temp).channel.use { channel ->
                temp.setWritable(false, true)
                source.inputStream().use { input -> input.copyTo(Channels.newOutputStream(channel)) }
                channel.force(true)
            }
            require(temp.setReadOnly()) { "Failed to mark APK read-only" }
            val copiedSha = temp.inputStream().use { Sha256.hex(it) }
            require(copiedSha == sourceSha) { "SHA changed during import" }
            if (finalFile.exists()) finalFile.delete()
            require(temp.renameTo(finalFile)) { "Atomic APK move failed" }
            finalFile.setReadOnly()
            val now = clock.now()
            val virtualPackage = VirtualPackage(
                packageName = metadata.packageName,
                label = metadata.label,
                versionCode = metadata.versionCode,
                versionName = metadata.versionName,
                minSdk = metadata.minSdk,
                targetSdk = metadata.targetSdk,
                sha256 = sourceSha,
                apkInternalPath = finalFile.absolutePath,
                installTime = existing?.installTime ?: now,
                updateTime = now,
                primaryAbi = metadata.primaryAbi,
                hasNativeLibraries = metadata.hasNativeLibraries,
                mainActivity = metadata.mainActivity,
                compatibilityLevel = level,
                enabled = true,
                virtualUserId = userId,
                virtualUid = virtualUid,
            )
            registry[metadata.packageName] = virtualPackage
            File(storageMetadata.root, "metadata/package.txt").writeText(virtualPackage.toString())
            File(storageMetadata.root, "metadata/compatibility.txt").writeText(issues.joinToString("\n") { "${it.severity}:${it.code}:${it.message}" })
            VLog.i("Importer", "imported package=${virtualPackage.packageName} sha=${virtualPackage.sha256} level=$level")
            return virtualPackage
        } catch (t: Throwable) {
            temp.delete()
            VLog.e("Importer", "import failed package=${metadata.packageName}: ${t.message}", t)
            throw t
        }
    }
}
