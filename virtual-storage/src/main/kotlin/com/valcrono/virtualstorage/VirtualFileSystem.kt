package com.valcrono.virtualstorage

import java.io.File
import java.time.Instant

enum class VirtualFsRole { ADMIN, APP, VIRTUAL_SU }
enum class VirtualFsPermission { READ_SHARED_STORAGE, WRITE_SHARED_STORAGE }
enum class VirtualFsNodeType { DIRECTORY, FILE, GENERATED, ALIAS, MISSING }

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
        if (p == "/data" || p == "/storage" || p == "/storage/emulated" || p.matches(Regex("/storage/emulated/\\d+")) || p == "/proc" || p == "/proc/virtualspace" || p == "/proc/apps") return synthetic(p.substringAfterLast('/'))
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

class VirtualFileSystem(private val namespace: VirtualFsNamespace, private val resolver: VirtualFsResolver = VirtualFsResolver(namespace), private val policy: VirtualFsPermissionPolicy = VirtualFsPermissionPolicy()) {
    fun resolve(path: String, context: VirtualFsAccessContext = VirtualFsAccessContext.admin()): VirtualFsNode { val r=resolver.resolve(path, context.virtualUserId).node; require(policy.canRead(context,r)) { "DENIED ${context.role} ${r.virtualPath}" }; return r }
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
