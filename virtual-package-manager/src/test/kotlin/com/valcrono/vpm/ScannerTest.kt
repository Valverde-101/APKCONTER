package com.valcrono.vpm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.valcrono.virtualstorage.VirtualStorageManager

class ScannerTest {
    @Test fun compatibleSimple() {
        val metadata = ApkMetadata(
            packageName = "com.example.app",
            label = "app",
            versionCode = 1,
            versionName = "1",
            minSdk = 23,
            targetSdk = 35,
            components = listOf(VirtualComponent("com.example.app", "com.example.app.Main", ComponentType.ACTIVITY)),
            permissions = emptyList(),
            hasNativeLibraries = false,
            primaryAbi = null,
            mainActivity = "com.example.app.Main",
        )
        assertEquals(CompatibilityLevel.COMPATIBLE, CompatibilityScanner().scan(metadata).first)
    }

    @Test fun unsupportedForWrongAbi() {
        val metadata = ApkMetadata(
            packageName = "com.example.app",
            label = "app",
            versionCode = 1,
            versionName = "1",
            minSdk = 23,
            targetSdk = 35,
            components = listOf(VirtualComponent("com.example.app", "com.example.app.Main", ComponentType.ACTIVITY)),
            permissions = emptyList(),
            hasNativeLibraries = true,
            primaryAbi = "armeabi-v7a",
            mainActivity = "com.example.app.Main",
        )
        val result = CompatibilityScanner().scan(metadata)
        assertEquals(CompatibilityLevel.UNSUPPORTED, result.first)
        assertTrue(result.second.any { it.code == "ABI_UNSUPPORTED" })
    }

    @Test fun importerStoresUnsupportedAbiInsteadOfRejecting() {
        val tempDir = createTempDir(prefix = "vpm-test")
        try {
            val apk = File(tempDir, "unsupported.apk")
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write("manifest".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("lib/x86_64/libsample.so"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            val metadata = ApkMetadata(
                packageName = "com.example.unsupported",
                label = "unsupported",
                versionCode = 1,
                versionName = "1",
                minSdk = 23,
                targetSdk = 35,
                components = listOf(VirtualComponent("com.example.unsupported", "com.example.unsupported.Main", ComponentType.ACTIVITY)),
                permissions = emptyList(),
                hasNativeLibraries = true,
                primaryAbi = "x86_64",
                mainActivity = "com.example.unsupported.Main",
            )

            val imported = SecureApkImporter(VirtualStorageManager(tempDir), linkedMapOf()).importApk(apk, metadataOverride = metadata)

            assertEquals(CompatibilityLevel.UNSUPPORTED, imported.compatibilityLevel)
            assertTrue(File(imported.apkInternalPath).isFile)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test fun highRiskForPlayIntegrity() {
        val metadata = ApkMetadata(
            packageName = "com.example.app",
            label = "app",
            versionCode = 1,
            versionName = "1",
            minSdk = 23,
            targetSdk = 35,
            components = listOf(VirtualComponent("com.example.app", "com.example.app.Main", ComponentType.ACTIVITY)),
            permissions = emptyList(),
            hasNativeLibraries = false,
            primaryAbi = null,
            mainActivity = "com.example.app.Main",
            usesPlayIntegrity = true,
        )
        assertEquals(CompatibilityLevel.HIGH_RISK, CompatibilityScanner().scan(metadata).first)
    }
}
