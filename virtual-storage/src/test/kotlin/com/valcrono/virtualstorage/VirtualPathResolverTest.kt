package com.valcrono.virtualstorage

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class VirtualPathResolverTest {
    @Test fun rejectsTraversal() {
        val root = Files.createTempDirectory("vs").toFile()
        val resolver = VirtualPathResolver(root)
        assertThrows(IllegalArgumentException::class.java) { resolver.resolve(0, "com.example.app", "../evil") }
    }

    @Test fun rejectsMaliciousPackageName() {
        val root = Files.createTempDirectory("vs").toFile()
        val resolver = VirtualPathResolver(root)
        assertThrows(IllegalArgumentException::class.java) { resolver.resolve(0, "../evil", "data/files/a") }
        assertThrows(IllegalArgumentException::class.java) { resolver.resolve(0, "com.example/evil", "data/files/a") }
        assertThrows(IllegalArgumentException::class.java) { resolver.resolve(-1, "com.example.app", "data/files/a") }
    }

    @Test fun createsStructure() {
        val root = Files.createTempDirectory("vs").toFile()
        val metadata = VirtualStorageManager(root).createPackageStorage(0, "com.example.app", 100000)
        assertTrue(java.io.File(metadata.root, "data/files").isDirectory)
        assertTrue(java.io.File(metadata.root, "external/cache").isDirectory)
    }
}
