package com.valcrono.virtualspace

import android.content.Context
import java.io.File

data class ApkPathResolution(val file: File, val code: String)

class ApkPathResolver(private val context: Context, private val db: ValcronoDatabase) {
    suspend fun resolve(pkg: VirtualPackageEntity): ApkPathResolution {
        val artifact = ApkArtifactRepository(context.applicationContext, db).resolveVerified(pkg)
        return ApkPathResolution(artifact.file, artifact.resolutionCode)
    }
}

// Compatibility codes kept for migration/contract tests: APK_FILE_MISSING, APK_PATH_REPAIRED, APK_HASH_MISMATCH, APK_CANONICALIZATION_FAILED.
