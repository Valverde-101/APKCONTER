package com.valcrono.vpm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
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
            primaryAbi = "mips",
            mainActivity = "com.example.app.Main",
        )
        val result = CompatibilityScanner().scan(metadata)
        assertEquals(CompatibilityLevel.UNSUPPORTED, result.first)
        assertTrue(result.second.any { it.code == "ABI_UNSUPPORTED" })
    }

    @Test fun importerStoresUnsupportedAbiInsteadOfRejecting() {
        val tempDir = Files.createTempDirectory("vpm-test").toFile()
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
                primaryAbi = "mips",
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


class CooperativeApkClassificationTest {
    private fun cooperative(pkg: String) = ApkMetadata(
        packageName = pkg,
        label = pkg.substringAfterLast('.'),
        versionCode = 1,
        versionName = "1.0",
        minSdk = 26,
        targetSdk = 35,
        components = listOf(VirtualComponent(pkg, "$pkg.VirtualEntryPoint", ComponentType.ACTIVITY)),
        permissions = emptyList(),
        hasNativeLibraries = false,
        primaryAbi = null,
        mainActivity = "$pkg.MainActivity",
        entryPointClass = "$pkg.VirtualEntryPoint",
        entryPointImplementsInterface = true,
        signingCertificateSha256 = "valid",
    )

    @Test fun testAppAIsCooperativeSupported() {
        val result = CompatibilityScanner().scan(cooperative("com.valcrono.testapp.a"))
        assertEquals(CompatibilityLevel.COOPERATIVE_SUPPORTED, result.first)
        assertTrue(result.second.any { it.message == "Runtime cooperativo compatible" })
        assertTrue(result.second.any { it.message == "Entry point encontrado" })
    }

    @Test fun testAppBIsCooperativeSupported() {
        val result = CompatibilityScanner().scan(cooperative("com.valcrono.testapp.b"))
        assertEquals(CompatibilityLevel.COOPERATIVE_SUPPORTED, result.first)
        assertTrue(result.second.any { it.message == "Sin librerías nativas" })
        assertTrue(result.second.any { it.message == "Sin Google Play Services" })
        assertTrue(result.second.any { it.message == "Sin multiproceso" })
    }
}
