package com.valcrono.virtualspace

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.valcrono.core.Sha256
import com.valcrono.vpm.ApkMetadata
import com.valcrono.vpm.ApkValidator
import com.valcrono.vpm.ComponentType
import com.valcrono.vpm.CompatibilityScanner
import com.valcrono.vpm.VirtualPermission
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipFile

private val APK_NAME = Regex("(?i).+\\.apk$")
private val BLOCKED_BUNDLES = Regex("(?i).+\\.(apks|xapk|apkm|zip)$")

data class ApkCopyResult(val file: File, val sha256: String, val originalName: String, val sizeBytes: Long)

class ApkImportCoordinatorV1(private val context: Context, private val repository: VirtualRepository) {
    private val contentResolver = context.contentResolver

    suspend fun importFromSaf(uri: Uri, userId: Int = 0): VirtualPackageEntity {
        require(uri.scheme == "content") { "UNSUPPORTED_URI_SCHEME" }
        val original = displayName(uri).ifBlank { "selected.apk" }
        require(!BLOCKED_BUNDLES.matches(original)) { "APK_BUNDLE_UNSUPPORTED" }
        require(APK_NAME.matches(original) || allowedMime(uri)) { "APK_MIME_OR_EXTENSION_UNSUPPORTED" }
        val tempDir = File(context.filesDir, "virtual-imports/tmp/${UUID.randomUUID()}").canonicalFile
        tempDir.mkdirs()
        return try {
            val copied = copyAndHash(uri, original, tempDir)
            ApkValidator().validate(copied.file)
            val parsed = AndroidArchivePackageParser(context).parse(copied.file)
            val archive = ApkArchiveReaderV1.read(copied.file)
            val metadata = ApkMetadata(
                packageName = parsed.packageName,
                label = parsed.label,
                versionCode = parsed.versionCode,
                versionName = parsed.versionName,
                minSdk = parsed.minSdk,
                targetSdk = parsed.targetSdk,
                components = parsed.components,
                permissions = parsed.permissions,
                hasNativeLibraries = archive.nativeLibraryCount > 0,
                primaryAbi = archive.supportedAbis.firstOrNull { it in android.os.Build.SUPPORTED_ABIS.toSet() } ?: archive.supportedAbis.firstOrNull(),
                mainActivity = parsed.mainActivity,
                hasSplitMarker = archive.isSplitApk,
                entryPointClass = parsed.entryPointClass,
                entryPointImplementsInterface = parsed.entryPointClass != null,
                signingCertificateSha256 = parsed.certificateSha256,
            )
            val (compat, issues) = CompatibilityScanner().scan(metadata)
            val classification = classifyRuntime(parsed, archive, metadata.entryPointClass != null, issues.map { it.code })
            val imported = repository.importCopiedApk(copied.file, userId, parsed)
            val finalEntity = imported.copy(
                displayName = parsed.label,
                importedApkPath = imported.apkInternalPath,
                originalFileName = original,
                apkSizeBytes = copied.sizeBytes,
                certificateSha256 = parsed.certificateSha256,
                compileSdk = parsed.compileSdk,
                supportedAbisJson = archive.supportedAbis.toJsonArray(),
                dexCount = archive.dexCount,
                nativeLibraryCount = archive.nativeLibraryCount,
                hasNativeCode = archive.nativeLibraryCount > 0,
                isSplitApk = archive.isSplitApk,
                splitNamesJson = archive.splitNames.toJsonArray(),
                hasCustomApplication = parsed.applicationClassName != null,
                applicationClassName = parsed.applicationClassName,
                launcherActivityName = parsed.mainActivity,
                cooperativeEntryPointClass = parsed.entryPointClass,
                declaredActivitiesJson = parsed.components.filter { it.type == ComponentType.ACTIVITY }.map { it.name }.toJsonArray(),
                declaredServicesJson = parsed.components.filter { it.type == ComponentType.SERVICE }.map { it.name }.toJsonArray(),
                declaredReceiversJson = parsed.components.filter { it.type == ComponentType.RECEIVER }.map { it.name }.toJsonArray(),
                declaredProvidersJson = parsed.components.filter { it.type == ComponentType.PROVIDER }.map { it.name }.toJsonArray(),
                declaredPermissionsJson = emptyList<String>().toJsonArray(),
                requestedPermissionsJson = parsed.permissions.map(VirtualPermission::name).toJsonArray(),
                importantIntentFiltersJson = parsed.intentFilters.map { "${it.componentName}:${it.actions.joinToString("|")}:${it.categories.joinToString("|")}" }.toJsonArray(),
                compatibilityLevel = classification.compatibilityLevel,
                runtimeMode = classification.runtimeMode,
                genericRuntimeCapability = classification.genericRuntimeCapability,
                compatibilityReasonsJson = classification.reasons.toJsonArray(),
                blockingReasonsJson = classification.blockingReasons.toJsonArray(),
                importState = classification.importState,
                importedAt = imported.installTime,
                updatedAt = System.currentTimeMillis(),
                lastVerifiedAt = System.currentTimeMillis(),
            )
            repository.db.packages().upsertPackage(finalEntity)
            VirtualPackageStorageV1(context.filesDir).writeMetadata(finalEntity)
            finalEntity
        } catch (t: Throwable) {
            throw t
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun copyAndHash(uri: Uri, original: String, tempDir: File): ApkCopyResult {
        val out = File(tempDir, "incoming.apk").canonicalFile
        require(out.path.startsWith(tempDir.path)) { "IMPORT_PATH_TRAVERSAL" }
        val beforeSize = querySize(uri)
        FileOutputStream(out).channel.use { channel ->
            contentResolver.openInputStream(uri)?.use { input -> input.copyTo(java.nio.channels.Channels.newOutputStream(channel)) } ?: error("APK_URI_OPEN_FAILED")
            channel.force(true)
        }
        if (beforeSize != null) require(out.length() == beforeSize) { "APK_TRUNCATED" }
        require(hasZipHeader(out)) { "APK_ZIP_HEADER_INVALID" }
        val sha = out.inputStream().use { Sha256.hex(it) }
        return ApkCopyResult(out, sha, original, out.length())
    }

    private fun allowedMime(uri: Uri): Boolean = contentResolver.getType(uri) in setOf("application/vnd.android.package-archive", "application/octet-stream")
    private fun displayName(uri: Uri): String = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" } ?: (uri.lastPathSegment ?: "")
    private fun querySize(uri: Uri): Long? = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    private fun hasZipHeader(file: File): Boolean = file.inputStream().use { input -> input.read() == 0x50 && input.read() == 0x4b }
}

data class ApkArchiveFactsV1(val dexCount: Int, val nativeLibraryCount: Int, val supportedAbis: List<String>, val isSplitApk: Boolean, val splitNames: List<String>)

object ApkArchiveReaderV1 {
    fun read(apk: File): ApkArchiveFactsV1 {
        val names = linkedSetOf<String>()
        val abis = linkedSetOf<String>()
        val splits = linkedSetOf<String>()
        var dex = 0
        var libs = 0
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().forEach { e ->
                require(names.add(e.name)) { "APK_DUPLICATE_ZIP_ENTRY" }
                require(!e.name.startsWith("/") && !e.name.contains("../") && !e.name.contains("..\\")) { "APK_ZIP_PATH_TRAVERSAL" }
                if (Regex("^classes(\\d+)?\\.dex$").matches(e.name)) dex++
                if (e.name.startsWith("lib/") && e.name.endsWith(".so")) { libs++; e.name.split('/').getOrNull(1)?.let(abis::add) }
                if (e.name.startsWith("split_") || e.name.contains("split_config.")) splits += e.name
            }
            require(zip.getEntry("AndroidManifest.xml") != null) { "APK_MANIFEST_MISSING" }
            require(dex > 0) { "APK_CLASSES_DEX_MISSING" }
        }
        return ApkArchiveFactsV1(dex, libs, abis.toList(), splits.isNotEmpty(), splits.toList())
    }
}

data class RuntimeClassification(val runtimeMode:String, val genericRuntimeCapability:String, val compatibilityLevel:String, val importState:String, val reasons:List<String>, val blockingReasons:List<String>)

private fun classifyRuntime(parsed: AndroidArchivePackageParser.AndroidParsedPackage, archive: ApkArchiveFactsV1, cooperative: Boolean, scannerIssueCodes: List<String>): RuntimeClassification {
    val deviceAbis = android.os.Build.SUPPORTED_ABIS.toSet()
    val abiMatch = archive.supportedAbis.isEmpty() || archive.supportedAbis.any { it in deviceAbis }
    val blocking = mutableListOf<String>()
    val reasons = mutableListOf<String>()
    if (cooperative) return RuntimeClassification("COOPERATIVE", "NONE", "COOPERATIVE_SUPPORTED", "READY", listOf("COOPERATIVE_ENTRY_POINT"), emptyList())
    if (archive.isSplitApk) blocking += "SPLIT_APK_UNSUPPORTED"
    if (parsed.mainActivity == null) blocking += "NO_MAIN_LAUNCHER_ACTIVITY"
    if (!abiMatch) blocking += "ABI_UNSUPPORTED"
    if (parsed.permissions.any { it.name.contains("BIND_ACCESSIBILITY_SERVICE") }) blocking += "ACCESSIBILITY_UNSUPPORTED"
    if (parsed.permissions.any { it.name.contains("BIND_VPN_SERVICE") }) blocking += "VPN_UNSUPPORTED"
    if (parsed.components.any { it.processName != null && it.processName != parsed.packageName }) reasons += "MULTIPROCESS_HIGH_RISK"
    if (parsed.permissions.any { it.name.contains("com.google.android.gms") } || scannerIssueCodes.any { it.contains("GMS") }) reasons += "GMS_UNAVAILABLE"
    if (parsed.permissions.any { it.name.contains("PLAY_INTEGRITY") }) reasons += "PLAY_INTEGRITY_LIMITATION"
    if (archive.nativeLibraryCount == 0) reasons += "GENERIC_SIMPLE_NO_NATIVE_CODE" else reasons += if (abiMatch) "GENERIC_NATIVE_COMPATIBLE" else "NATIVE_ABI_MISMATCH"
    val canAttemptGeneric = blocking.isEmpty() && reasons.none { it == "MULTIPROCESS_HIGH_RISK" || it == "GMS_UNAVAILABLE" }
    return if (canAttemptGeneric) RuntimeClassification("INSPECTION_ONLY", if (archive.nativeLibraryCount == 0) "GENERIC_SIMPLE_READY_WHEN_HOST_IMPLEMENTED" else "GENERIC_NATIVE_COMPATIBLE_READY_WHEN_HOST_IMPLEMENTED", "GENERIC_EXPERIMENTAL", "INSPECTION_ONLY", reasons + "GENERIC_ACTIVITY_HOST_NOT_IMPLEMENTED", listOf("GENERIC_ACTIVITY_HOST_NOT_IMPLEMENTED"))
    else RuntimeClassification("INSPECTION_ONLY", "INSPECTION_ONLY", if (blocking.isEmpty()) "HIGH_RISK" else blocking.first(), if (blocking.isEmpty()) "INSPECTION_ONLY" else "BLOCKED", reasons, blocking.ifEmpty { listOf("HIGH_RISK_API_UNSUPPORTED") })
}

class VirtualPackageStorageV1(private val filesDir: File) {
    fun root(packageName: String): File {
        require(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(packageName)) { "INVALID_PACKAGE_NAME" }
        val base = File(filesDir, "virtual-packages").canonicalFile
        val root = File(base, packageName).canonicalFile
        require(root.path == base.path || root.path.startsWith(base.path + File.separator)) { "PACKAGE_PATH_TRAVERSAL" }
        return root
    }
    fun writeMetadata(pkg: VirtualPackageEntity) {
        val root = root(pkg.packageName)
        listOf("apk", "data/files", "data/cache", "data/databases", "data/shared_prefs", "data/code_cache", "data/no_backup", "metadata", "temp", "logs").forEach { File(root, it).mkdirs() }
        val base = File(root, "apk/base.apk")
        File(pkg.apkInternalPath).copyTo(base, overwrite = true)
        base.setReadOnly()
        val certificateJson = pkg.certificateSha256?.let { "\"${it.escapeJson()}\"" } ?: "null"
        File(root, "metadata/package.json").writeText("{\"packageName\":\"${pkg.packageName.escapeJson()}\",\"displayName\":\"${pkg.displayName.escapeJson()}\",\"versionCode\":${pkg.versionCode}}")
        File(root, "metadata/hashes.json").writeText("{\"sha256\":\"${pkg.sha256}\",\"certificateSha256\":$certificateJson}")
        File(root, "metadata/compatibility.json").writeText("{\"level\":\"${pkg.compatibilityLevel}\",\"reasons\":${pkg.compatibilityReasonsJson}}")
    }
}

private fun List<String>.toJsonArray(): String = joinToString(prefix = "[", postfix = "]") { "\"" + it.escapeJson() + "\"" }
private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")
private fun com.valcrono.vpm.CompatibilityLevel.toV1Level(cooperative: Boolean): String = when {
    cooperative && this == com.valcrono.vpm.CompatibilityLevel.COOPERATIVE_SUPPORTED -> "COOPERATIVE_SUPPORTED"
    this == com.valcrono.vpm.CompatibilityLevel.UNSUPPORTED -> "INCOMPATIBLE"
    this == com.valcrono.vpm.CompatibilityLevel.HIGH_RISK -> "GENERIC_EXPERIMENTAL"
    this == com.valcrono.vpm.CompatibilityLevel.LIMITED -> "GENERIC_EXPERIMENTAL"
    else -> "GENERIC_SIMPLE"
}
