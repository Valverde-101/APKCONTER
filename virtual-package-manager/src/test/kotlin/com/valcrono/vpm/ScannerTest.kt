package com.valcrono.vpm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
