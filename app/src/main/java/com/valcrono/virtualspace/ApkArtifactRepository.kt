package com.valcrono.virtualspace

import android.content.Context
import androidx.room.withTransaction
import com.valcrono.core.Sha256
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.Channels

const val APK_ARTIFACT_VERSION_CURRENT = 1
const val APK_INTEGRITY_VERIFIED = "VERIFIED"
const val APK_INTEGRITY_DAMAGED = "DAMAGED"
const val APK_VIRTUAL_BASE_PATH_PREFIX = "/data/app"

data class ApkArtifact(val file: File, val virtualPath: String, val sha256: String, val integrityState: String, val resolutionCode: String)

class ApkArtifactIntegrityVerifier {
    fun verify(file: File, expectedSha256: String): String {
        if (!file.isFile) return "APK_ARTIFACT_MISSING"
        val actual = file.inputStream().use { Sha256.hex(it) }
        return if (actual == expectedSha256) APK_INTEGRITY_VERIFIED else "APK_ARTIFACT_HASH_MISMATCH"
    }
}

class ApkArtifactResolver(private val context: Context) {
    private val packageNameRegex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    fun canonicalFile(packageName: String, virtualUserId: Int): File {
        require(packageNameRegex.matches(packageName)) { "INVALID_PACKAGE_NAME" }
        val base = File(context.filesDir, "virtual-packages/users/$virtualUserId").canonicalFile
        val file = File(base, "$packageName/apk/base.apk").canonicalFile
        require(file.path.startsWith(base.path + File.separator)) { "PACKAGE_PATH_TRAVERSAL" }
        return file
    }
    fun virtualPath(packageName: String): String = "$APK_VIRTUAL_BASE_PATH_PREFIX/$packageName/base.apk"
    fun legacyCandidates(pkg: VirtualPackageEntity): List<File> = listOf(
        File(pkg.apkInternalPath),
        File(pkg.importedApkPath),
        File(context.filesDir, "virtual-packages/${pkg.packageName}/apk/base.apk"),
    ).distinctBy { it.absolutePath }
}

class ApkArtifactMigration(private val resolver: ApkArtifactResolver, private val verifier: ApkArtifactIntegrityVerifier = ApkArtifactIntegrityVerifier()) {
    fun migrateFromLegacy(pkg: VirtualPackageEntity): ApkArtifact? {
        val canonical = resolver.canonicalFile(pkg.packageName, pkg.virtualUserId)
        resolver.legacyCandidates(pkg).filter { it.absolutePath != canonical.absolutePath }.forEach { candidate ->
            if (verifier.verify(candidate, pkg.sha256) == APK_INTEGRITY_VERIFIED) {
                installCanonical(candidate, canonical)
                if (verifier.verify(canonical, pkg.sha256) == APK_INTEGRITY_VERIFIED) {
                    return ApkArtifact(canonical, resolver.virtualPath(pkg.packageName), pkg.sha256, APK_INTEGRITY_VERIFIED, "APK_PATH_REPAIRED")
                }
            }
        }
        return null
    }
    fun installCanonical(source: File, canonical: File) {
        canonical.parentFile?.mkdirs()
        val tmp = File(canonical.parentFile, "base.apk.tmp")
        FileOutputStream(tmp).channel.use { channel ->
            FileInputStream(source).use { input -> input.copyTo(Channels.newOutputStream(channel)) }
            channel.force(true)
        }
        fsyncDir(canonical.parentFile)
        if (canonical.exists()) canonical.delete()
        require(tmp.renameTo(canonical)) { "APK_CANONICAL_RENAME_FAILED" }
        canonical.setReadOnly()
        fsyncDir(canonical.parentFile)
    }
    private fun fsyncDir(dir: File?) { if (dir != null) runCatching { FileInputStream(dir).channel.use { it.force(true) } } }
}

class ApkArtifactRepository(private val context: Context, private val db: ValcronoDatabase) {
    private val resolver = ApkArtifactResolver(context)
    private val verifier = ApkArtifactIntegrityVerifier()
    private val migration = ApkArtifactMigration(resolver, verifier)

    fun canonicalFile(packageName: String, virtualUserId: Int): File = resolver.canonicalFile(packageName, virtualUserId)
    fun virtualPath(packageName: String): String = resolver.virtualPath(packageName)

    suspend fun importCanonical(stagingApk: File, pkg: VirtualPackageEntity): ApkArtifact {
        val canonical = resolver.canonicalFile(pkg.packageName, pkg.virtualUserId)
        migration.installCanonical(stagingApk, canonical)
        val state = verifier.verify(canonical, pkg.sha256)
        require(state == APK_INTEGRITY_VERIFIED) { state }
        return ApkArtifact(canonical, resolver.virtualPath(pkg.packageName), pkg.sha256, state, "APK_PATH_VALIDATED")
    }

    suspend fun resolveVerified(pkg: VirtualPackageEntity): ApkArtifact {
        val canonical = resolver.canonicalFile(pkg.packageName, pkg.virtualUserId)
        val direct = verifier.verify(canonical, pkg.sha256)
        if (direct == APK_INTEGRITY_VERIFIED) {
            repairEntityIfNeeded(pkg, canonical, APK_INTEGRITY_VERIFIED, null, 0)
            return ApkArtifact(canonical, resolver.virtualPath(pkg.packageName), pkg.sha256, direct, "APK_PATH_VALIDATED")
        }
        val migrated = migration.migrateFromLegacy(pkg)
        if (migrated != null) {
            repairEntityIfNeeded(pkg, migrated.file, APK_INTEGRITY_VERIFIED, "APK_PATH_REPAIRED", 1)
            return migrated
        }
        val code = if (canonical.isFile) "APK_ARTIFACT_HASH_MISMATCH" else "APK_ARTIFACT_MISSING"
        db.packages().upsertPackage(pkg.copy(damaged = true, importState = "DAMAGED", importErrorCode = code, importErrorMessage = code, apkIntegrityState = APK_INTEGRITY_DAMAGED, apkLastVerifiedAt = System.currentTimeMillis()))
        throw IllegalStateException("$code packageName=${pkg.packageName} apkVirtualPath=${resolver.virtualPath(pkg.packageName)}")
    }

    private suspend fun repairEntityIfNeeded(pkg: VirtualPackageEntity, canonical: File, integrity: String, code: String?, repairDelta: Int) {
        val needs = pkg.apkInternalPath != canonical.absolutePath || pkg.importedApkPath != canonical.absolutePath || pkg.apkVirtualPath != resolver.virtualPath(pkg.packageName) || pkg.apkIntegrityState != integrity || code != null
        if (needs) db.withTransaction { db.packages().upsertPackage(pkg.copy(apkInternalPath = canonical.absolutePath, importedApkPath = canonical.absolutePath, apkVirtualPath = resolver.virtualPath(pkg.packageName), apkArtifactVersion = APK_ARTIFACT_VERSION_CURRENT, apkIntegrityState = integrity, apkLastVerifiedAt = System.currentTimeMillis(), apkRepairCount = pkg.apkRepairCount + repairDelta, importErrorCode = code ?: pkg.importErrorCode, damaged = false)) }
    }
}
