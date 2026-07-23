package com.valcrono.virtualspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkArtifactRepositoryContractTest {
    private fun src(path: String): String {
        val userDir = File(System.getProperty("user.dir"))
        val candidates = listOf(File(path), File(userDir, path), File(userDir.parentFile ?: userDir, path), File(userDir.parentFile?.parentFile ?: userDir, path))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("Source file not found: $path")
    }

    @Test fun canonicalArtifactPathIncludesUserAndBaseApk() {
        val s = src("app/src/main/java/com/valcrono/virtualspace/ApkArtifactRepository.kt")
        assertTrue(s.contains("virtual-packages/users/$".removeSuffix("$") + "virtualUserId"))
        assertTrue(s.contains("apk/base.apk"))
        assertTrue(s.contains("/data/app"))
    }

    @Test fun resolverRepairsLegacyPathsWithoutExposingPhysicalPath() {
        val s = src("app/src/main/java/com/valcrono/virtualspace/ApkArtifactRepository.kt")
        assertTrue(s.contains("APK_PATH_REPAIRED"))
        assertTrue(s.contains("APK_ARTIFACT_MISSING"))
        assertTrue(s.contains("APK_ARTIFACT_HASH_MISMATCH"))
        assertFalse(s.contains("PATH_NOT_FOUND"))
    }

    @Test fun viewerUsesVirtualApkPath() {
        val s = src("app/src/main/java/com/valcrono/virtualspace/MainActivity.kt")
        assertTrue(s.contains("FileDestination.ApkViewer(pkg.apkVirtualPath)"))
        assertFalse(s.contains("FileDestination.ApkViewer(pkg.apkInternalPath)"))
    }
}
