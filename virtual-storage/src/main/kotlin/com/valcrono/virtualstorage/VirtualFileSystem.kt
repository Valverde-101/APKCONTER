package com.valcrono.virtualstorage

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.nio.charset.Charset
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class VirtualFsRole { ADMIN, APP, VIRTUAL_SU }
enum class VirtualFsPermission { READ_SHARED_STORAGE, WRITE_SHARED_STORAGE }
enum class VirtualFsNodeType { DIRECTORY, FILE, GENERATED, ALIAS, MISSING }

open class VirtualFsException(message: String) : IllegalStateException(message)
class VirtualPathNotFoundException(path: String) : VirtualFsException("PATH_NOT_FOUND: $path")
class VirtualPathNotFileException(path: String) : VirtualFsException("PATH_NOT_FILE: $path")
class VirtualAccessDeniedException(path: String) : VirtualFsException("ACCESS_DENIED: $path")
open class VirtualAliasException(message: String) : VirtualFsException(message)
class VirtualAliasTargetNotFoundException(path: String) : VirtualAliasException("ALIAS_TARGET_NOT_FOUND: $path")
class VirtualAliasLoopDetectedException(path: String) : VirtualAliasException("ALIAS_LOOP_DETECTED: $path")
class VirtualAliasDepthExceededException(path: String) : VirtualAliasException("ALIAS_DEPTH_EXCEEDED: $path")

data class VirtualFsAccessContext(
    val role: VirtualFsRole,
    val packageName: String? = null,
    val virtualUserId: Int = 0,
    val permissions: Set<VirtualFsPermission> = emptySet(),
) {
    companion object {
        fun admin(userId: Int = 0) = VirtualFsAccessContext(VirtualFsRole.ADMIN, virtualUserId = userId)
        fun app(packageName: String, userId: Int = 0, permissions: Set<VirtualFsPermission> = emptySet()) = VirtualFsAccessContext(VirtualFsRole.APP, packageName, userId, permissions)
        fun virtualSu(packageName: String, userId: Int = 0, permissions: Set<VirtualFsPermission> = emptySet()) = VirtualFsAccessContext(VirtualFsRole.VIRTUAL_SU, packageName, userId, permissions)
    }
}

data class VirtualFsNode(
    val name: String,
    val virtualPath: String,
    val type: VirtualFsNodeType,
    val size: Long = 0,
    val modifiedAt: Long = 0,
    val permissions: String = "r--",
    val owner: String = "system",
    val packageName: String? = null,
    val physicalFile: File? = null,
    val readOnly: Boolean = false,
    val generatedContent: String? = null,
)

data class VirtualFsResolution(val node: VirtualFsNode, val relativePhysicalPath: String? = null)

class VirtualFsNamespace(private val filesDir: File, private val packages: () -> List<String> = { emptyList() }) {
    val virtualRoot: File get() = File(filesDir, "virtual")
    fun userRoot(userId: Int) = File(virtualRoot, "users/$userId")
    fun packageRoot(userId: Int, packageName: String) = File(userRoot(userId), packageName)
    fun sharedRoot(userId: Int): File = File(userRoot(userId), "shared").apply { mkdirs() }
    fun installedPackages(userId: Int): List<String> {
        val fromDisk = userRoot(userId).listFiles().orEmpty().filter { it.isDirectory && it.name != "shared" }.map { it.name }
        return (fromDisk + packages()).distinct().sorted()
    }
}

class VirtualFsPermissionPolicy {
    fun canRead(ctx: VirtualFsAccessContext, node: VirtualFsNode): Boolean = canAccess(ctx, node, write = false)
    fun canWrite(ctx: VirtualFsAccessContext, node: VirtualFsNode): Boolean = !node.readOnly && canAccess(ctx, node, write = true)
    private fun canAccess(ctx: VirtualFsAccessContext, node: VirtualFsNode, write: Boolean): Boolean {
        if (ctx.role == VirtualFsRole.ADMIN) return true
        val pkg = ctx.packageName ?: return false
        if (node.virtualPath.startsWith("/proc")) return !write
        if (node.virtualPath.contains("/Shared")) return if (write) VirtualFsPermission.WRITE_SHARED_STORAGE in ctx.permissions else VirtualFsPermission.READ_SHARED_STORAGE in ctx.permissions
        if (node.packageName != null && node.packageName != pkg) return false
        if (node.virtualPath.startsWith("/data/app/")) return !write && node.packageName == pkg
        return node.packageName == pkg
    }
}

class VirtualFsResolver(private val namespace: VirtualFsNamespace) {
    fun normalize(input: String): String {
        require(!input.contains('\u0000')) { "NUL path rejected" }
        var path = if (input.isBlank()) "/" else input.replace('\\', '/')
        if (!path.startsWith('/')) path = "/$path"
        val parts = mutableListOf<String>()
        path.split('/').filter { it.isNotEmpty() }.forEach {
            when (it) { "." -> Unit; ".." -> throw IllegalArgumentException("Path traversal rejected: $input"); else -> parts += it }
        }
        path = "/" + parts.joinToString("/")
        if (path == "/sdcard" || path.startsWith("/sdcard/")) path = "/storage/emulated/0" + path.removePrefix("/sdcard")
        return path.removeSuffix("/").ifBlank { "/" }
    }

    fun resolve(path: String, userId: Int = 0): VirtualFsResolution {
        val p = normalize(path)
        fun synthetic(name: String, type: VirtualFsNodeType = VirtualFsNodeType.DIRECTORY, owner: String = "system") = VirtualFsResolution(VirtualFsNode(name, p, type, owner = owner))
        if (p == "/") return synthetic("/")
        if (p == "/data" || p == "/data/data" || p == "/data/user" || p == "/data/user/$userId" || p == "/data/app" || p == "/storage" || p == "/storage/emulated" || p.matches(Regex("/storage/emulated/\\d+")) || p == "/storage/emulated/$userId/Android" || p == "/storage/emulated/$userId/Android/data" || p == "/proc" || p == "/proc/virtualspace" || p == "/proc/apps") return synthetic(p.substringAfterLast('/'))
        Regex("^/data/data/([^/]+)(/.*)?$").matchEntire(p)?.let { m -> return packageData(p, userId, m.groupValues[1], m.groupValues.getOrNull(2).orEmpty()) }
        Regex("^/data/user/(\\d+)/([^/]+)(/.*)?$").matchEntire(p)?.let { m -> return packageData(p, m.groupValues[1].toInt(), m.groupValues[2], m.groupValues.getOrNull(3).orEmpty()) }
        Regex("^/data/app/([^/]+)(/base\\.apk|/lib.*)?$").matchEntire(p)?.let { m ->
            val pkg=m.groupValues[1]; val tail=m.groupValues.getOrNull(2).orEmpty(); val root=namespace.packageRoot(userId,pkg)
            val f = if (tail == "/base.apk") root.resolve("apk").listFiles()?.firstOrNull{it.extension=="apk"} ?: root.resolve("apk/base.apk") else root.resolve("lib${tail.removePrefix("/lib")}")
            return VirtualFsResolution(fileNode(p,f,pkg, readOnly = true))
        }
        Regex("^/storage/emulated/(\\d+)/Android/data/([^/]+)/(files|cache)(/.*)?$").matchEntire(p)?.let { m ->
            val f=namespace.packageRoot(m.groupValues[1].toInt(),m.groupValues[2]).resolve("external/${m.groupValues[3]}${m.groupValues.getOrNull(4).orEmpty()}")
            return VirtualFsResolution(fileNode(p,f,m.groupValues[2]))
        }
        Regex("^/storage/emulated/(\\d+)/Shared(/.*)?$").matchEntire(p)?.let { m -> return VirtualFsResolution(fileNode(p, namespace.sharedRoot(m.groupValues[1].toInt()).resolve(m.groupValues.getOrNull(2).orEmpty().removePrefix("/")), null, owner="shared")) }
        if (p.startsWith("/proc/virtualspace/") || p.startsWith("/proc/apps/")) return VirtualFsResolution(VirtualFsNode(p.substringAfterLast('/'), p, VirtualFsNodeType.GENERATED, modifiedAt=System.currentTimeMillis(), permissions="r--", owner="proc", readOnly=true, generatedContent="generated ${Instant.now()}\n"))
        return synthetic(p.substringAfterLast('/'), VirtualFsNodeType.MISSING)
    }
    private fun packageData(p:String,userId:Int,pkg:String,tail:String): VirtualFsResolution = VirtualFsResolution(fileNode(p, namespace.packageRoot(userId,pkg).resolve("data${tail}"), pkg))
    private fun fileNode(p:String,f:File,pkg:String?,readOnly:Boolean=false,owner:String=pkg ?: "system") = VirtualFsNode(f.name.ifBlank{p.substringAfterLast('/')}, p, when { f.isDirectory -> VirtualFsNodeType.DIRECTORY; f.isFile -> VirtualFsNodeType.FILE; else -> VirtualFsNodeType.MISSING }, f.takeIf{it.isFile}?.length() ?: 0, f.lastModified(), if (readOnly) "r--" else "rw-", owner, pkg, f, readOnly)
}

data class VirtualFsStat(val virtualPath: String, val name: String, val type: VirtualFsNodeType, val size: Long, val modifiedAt: Long, val permissions: String, val owner: String, val packageName: String?, val readOnly: Boolean)

class VirtualFileSystem(private val namespace: VirtualFsNamespace, private val resolver: VirtualFsResolver = VirtualFsResolver(namespace), private val policy: VirtualFsPermissionPolicy = VirtualFsPermissionPolicy(), private val accessLogger: ((VirtualFsAccessContext, String, String, Boolean) -> Unit)? = null) {
    fun resolve(path: String, context: VirtualFsAccessContext = VirtualFsAccessContext.admin()): VirtualFsNode { val r=resolver.resolve(path, context.virtualUserId).node; if (!policy.canRead(context,r)) throw VirtualAccessDeniedException(r.virtualPath); return r }
    private fun logAccess(context: VirtualFsAccessContext, path: String, operation: String, allowed: Boolean) { accessLogger?.invoke(context, path, operation, allowed) }
    fun stat(path: String, context: VirtualFsAccessContext = VirtualFsAccessContext.admin()): VirtualFsStat {
        val node = resolve(path, context); logAccess(context, node.virtualPath, "READ", true)
        return VirtualFsStat(node.virtualPath, node.name, node.type, node.size, node.modifiedAt, node.permissions, node.owner, node.packageName, node.readOnly)
    }
    suspend fun openInputStream(path: String, context: VirtualFsAccessContext = VirtualFsAccessContext.admin()): InputStream = withContext(Dispatchers.IO) {
        val node = resolve(path, context); if (node.type == VirtualFsNodeType.MISSING) throw VirtualPathNotFoundException(node.virtualPath); if (node.type != VirtualFsNodeType.FILE && node.type != VirtualFsNodeType.GENERATED) throw VirtualPathNotFileException(node.virtualPath); logAccess(context, node.virtualPath, "READ", true)
        node.generatedContent?.byteInputStream() ?: node.physicalFile?.inputStream() ?: ByteArrayInputStream(ByteArray(0))
    }
    suspend fun readBytes(path: String, context: VirtualFsAccessContext = VirtualFsAccessContext.admin(), maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        require(maxBytes in 0..64L * 1024L * 1024L) { "Invalid read limit" }; openInputStream(path, context).use { it.readNBytes(maxBytes.toInt()) }
    }
    suspend fun readText(path: String, context: VirtualFsAccessContext = VirtualFsAccessContext.admin(), charset: Charset = Charsets.UTF_8, maxBytes: Long): String = withContext(Dispatchers.IO) { String(readBytes(path, context, maxBytes), charset) }
    suspend fun readRange(path: String, context: VirtualFsAccessContext = VirtualFsAccessContext.admin(), offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        require(offset >= 0 && length >= 0 && length <= 4 * 1024 * 1024) { "Invalid range" }
        require(Long.MAX_VALUE - offset >= length.toLong()) { "Invalid range overflow" }
        val before = stat(path, context)
        if (before.type == VirtualFsNodeType.MISSING) throw VirtualPathNotFoundException(before.virtualPath); if (before.type != VirtualFsNodeType.FILE && before.type != VirtualFsNodeType.GENERATED) throw VirtualPathNotFileException(before.virtualPath)
        openInputStream(path, context).use { input ->
            var remaining = offset
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) return@use ByteArray(0)
                remaining -= skipped
            }
            val bytes = input.readNBytes(length)
            val after = stat(path, context)
            require(before.modifiedAt == after.modifiedAt && before.size == after.size) { "FILE_CHANGED_DURING_READ" }
            bytes
        }
    }
    suspend fun calculateSha256(path: String, context: VirtualFsAccessContext = VirtualFsAccessContext.admin()): String = withContext(Dispatchers.IO) {
        val node = resolve(path, context); logAccess(context, node.virtualPath, "HASH", true); val md=MessageDigest.getInstance("SHA-256"); openInputStream(path, context).use { input -> val buf=ByteArray(8192); while(true){ val n=input.read(buf); if(n<0) break; md.update(buf,0,n)} }; md.digest().joinToString("") { "%02x".format(it) }
    }

    fun list(path: String, context: VirtualFsAccessContext = VirtualFsAccessContext.admin()): List<VirtualFsNode> {
        val node = resolve(path, context); val p=node.virtualPath
        if (p == "/") return listOf(
            VirtualFsNode("data", "/data", VirtualFsNodeType.DIRECTORY),
            VirtualFsNode("storage", "/storage", VirtualFsNodeType.DIRECTORY),
            VirtualFsNode("sdcard", "/sdcard", VirtualFsNodeType.ALIAS, owner = "alias"),
            VirtualFsNode("proc", "/proc", VirtualFsNodeType.DIRECTORY, readOnly = true),
        ).filter { policy.canRead(context, it) }
        val children = when (p) {
            "/data" -> listOf("/data/data","/data/user","/data/app")
            "/data/data" -> namespace.installedPackages(context.virtualUserId).map { "/data/data/$it" }
            "/data/user" -> listOf("/data/user/${context.virtualUserId}")
            "/data/user/${context.virtualUserId}" -> namespace.installedPackages(context.virtualUserId).map { "/data/user/${context.virtualUserId}/$it" }
            "/storage/emulated/${context.virtualUserId}" -> listOf("/storage/emulated/${context.virtualUserId}/Android", "/storage/emulated/${context.virtualUserId}/Shared")
            "/proc" -> listOf("/proc/virtualspace","/proc/apps")
            "/proc/virtualspace" -> listOf("/proc/virtualspace/status","/proc/virtualspace/meminfo")
            else -> node.physicalFile?.listFiles()?.map { child -> p.trimEnd('/') + "/" + child.name }.orEmpty()
        }
        return children.mapNotNull { runCatching { resolve(it, context) }.getOrNull() }
    }
}
