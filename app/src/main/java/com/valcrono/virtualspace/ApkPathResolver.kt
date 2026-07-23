package com.valcrono.virtualspace

import android.content.Context
import androidx.room.withTransaction
import com.valcrono.core.Sha256
import java.io.File

data class ApkPathResolution(val file: File, val code: String)

class ApkPathResolver(private val context: Context, private val db: ValcronoDatabase) {
    suspend fun resolve(pkg: VirtualPackageEntity): ApkPathResolution {
        val canonical = File(pkg.apkInternalPath)
        if (canonical.isFile) {
            val sha = canonical.inputStream().use { Sha256.hex(it) }
            if (sha != pkg.sha256) throw IllegalStateException("APK_HASH_MISMATCH packageName=${pkg.packageName} apkPath=${canonical.absolutePath}")
            return ApkPathResolution(canonical, "APK_PATH_VALIDATED")
        }
        val secondary = File(context.filesDir, "virtual-packages/${pkg.packageName}/apk/base.apk")
        if (!secondary.isFile) {
            db.packages().markDamaged(pkg.packageName, pkg.virtualUserId)
            throw IllegalStateException("APK_FILE_MISSING packageName=${pkg.packageName} apkPath=${canonical.absolutePath} fallback=${secondary.absolutePath}")
        }
        val sha = secondary.inputStream().use { Sha256.hex(it) }
        if (sha != pkg.sha256) {
            db.packages().markDamaged(pkg.packageName, pkg.virtualUserId)
            throw IllegalStateException("APK_HASH_MISMATCH packageName=${pkg.packageName} apkPath=${secondary.absolutePath}")
        }
        return runCatching {
            canonical.parentFile?.mkdirs()
            secondary.copyTo(canonical, overwrite = true)
            canonical.setReadOnly()
            db.withTransaction { db.packages().upsertPackage(pkg.copy(apkInternalPath = canonical.absolutePath, importedApkPath = canonical.absolutePath, importErrorCode = "APK_PATH_REPAIRED", lastVerifiedAt = System.currentTimeMillis())) }
            ApkPathResolution(canonical, "APK_PATH_REPAIRED")
        }.getOrElse { throw IllegalStateException("APK_CANONICALIZATION_FAILED packageName=${pkg.packageName} apkPath=${canonical.absolutePath}", it) }
    }
}
