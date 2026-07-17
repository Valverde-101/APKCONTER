package com.valcrono.vfm

import com.valcrono.virtualstorage.VirtualStorageManager
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FileManagerTest {
    @Test
    fun checksumsExport() {
        val root = Files.createTempDirectory("vs").toFile()
        val packageName = "com.example.p"
        val storageManager = VirtualStorageManager(root)

        storageManager.createPackageStorage(0, packageName, 1)
        storageManager.resolver().resolve(0, packageName, "data/files/a.txt").writeText("abc")

        val sums = VirtualFileManager(storageManager.resolver())
            .checksums(storageManager.resolver().packageRoot(0, packageName))

        assertTrue(sums.keys.any { it.endsWith("a.txt") })
    }
}
