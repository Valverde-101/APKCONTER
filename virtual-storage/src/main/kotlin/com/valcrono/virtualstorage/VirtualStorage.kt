package com.valcrono.virtualstorage

import com.valcrono.core.VLog
import java.io.File

private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

data class VirtualStorageMetadata(val packageName: String, val virtualUserId: Int, val virtualUid: Int, val root: File)
data class VirtualStorageQuota(val maxBytes: Long? = 512L * 1024 * 1024)
data class VirtualStoragePolicy(val quota: VirtualStorageQuota = VirtualStorageQuota())

class VirtualPathResolver(private val base: File) {
    fun validateIdentity(userId: Int, packageName: String) {
        require(userId >= 0) { "virtualUserId must be non-negative" }
        require(PACKAGE_NAME_REGEX.matches(packageName)) { "Invalid package name: $packageName" }
        require(!packageName.contains("..") && !packageName.contains('/') && !packageName.contains('\\')) {
            "Unsafe package name: $packageName"
        }
    }

    fun packageRoot(userId: Int, packageName: String): File {
        validateIdentity(userId, packageName)
        return File(base, "virtual/users/$userId/$packageName").canonicalFile
    }

    fun resolve(userId: Int, packageName: String, relative: String): File {
        require(!relative.startsWith("/") && !relative.startsWith("\\")) { "Absolute path rejected" }
        require(!relative.contains('\u0000')) { "NUL path rejected" }
        val root = packageRoot(userId, packageName)
        val resolved = File(root, relative).canonicalFile
        require(resolved.path == root.path || resolved.path.startsWith(root.path + File.separator)) {
            "Path traversal rejected: $relative"
        }
        return resolved
    }
}

class VirtualStorageManager(
    private val filesDir: File,
    private val policy: VirtualStoragePolicy = VirtualStoragePolicy(),
) {
    private val resolver = VirtualPathResolver(filesDir)

    fun createPackageStorage(userId: Int, packageName: String, virtualUid: Int): VirtualStorageMetadata {
        val dirs = listOf(
            "data/files",
            "data/databases",
            "data/shared_prefs",
            "data/cache",
            "data/code_cache",
            "data/no_backup",
            "external/files",
            "external/cache",
            "lib",
            "apk",
            "metadata",
        )
        dirs.forEach { resolver.resolve(userId, packageName, it).mkdirs() }
        File(filesDir, "virtual/users/$userId/shared").mkdirs()
        val root = resolver.packageRoot(userId, packageName)
        val metadata = VirtualStorageMetadata(packageName, userId, virtualUid, root)
        VLog.i("VirtualStorage", "created package=$packageName user=$userId root=$root")
        return metadata
    }

    fun usedBytes(userId: Int, packageName: String): Long =
        resolver.packageRoot(userId, packageName).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun ensureQuota(userId: Int, packageName: String, incomingBytes: Long = 0) {
        val current = usedBytes(userId, packageName)
        val projected = current + incomingBytes
        val deviceFree = filesDir.usableSpace
        val safetyMargin = 64L * 1024 * 1024
        policy.quota.maxBytes?.let { maxBytes ->
            require(projected <= maxBytes) { "Cuota de almacenamiento excedida: $projected > $maxBytes" }
        }
        require(deviceFree - incomingBytes >= safetyMargin) { "Espacio insuficiente en el dispositivo para completar la operación" }
    }

    fun clearCache(userId: Int, packageName: String) {
        listOf("data/cache", "external/cache").forEach { relative ->
            resolver.resolve(userId, packageName, relative).deleteRecursively()
            resolver.resolve(userId, packageName, relative).mkdirs()
        }
        VLog.i("VirtualStorage", "cleared cache package=$packageName user=$userId")
    }

    fun resolver(): VirtualPathResolver = resolver
}
