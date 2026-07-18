package com.valcrono.virtualspace

import com.valcrono.virtualstorage.VirtualAliasDepthExceededException
import com.valcrono.virtualstorage.VirtualAliasLoopDetectedException
import com.valcrono.virtualstorage.VirtualAliasTargetNotFoundException
import com.valcrono.virtualstorage.VirtualAccessDeniedException
import com.valcrono.virtualstorage.VirtualFileSystem
import com.valcrono.virtualstorage.VirtualFsAccessContext
import com.valcrono.virtualstorage.VirtualFsNodeType
import com.valcrono.virtualstorage.VirtualPathNotFileException
import com.valcrono.virtualstorage.VirtualPathNotFoundException
import java.util.Locale

sealed interface FileRouteResult {
    data class Navigate(val destination: FileDestination) : FileRouteResult
    data class Error(val code: ViewerErrorCode, val message: String) : FileRouteResult
}

class FileDestinationRouter(private val vfs: VirtualFileSystem) {
    suspend fun route(virtualPath: String, context: VirtualFsAccessContext): FileRouteResult {
        return runCatching { routeInternal(normalize(virtualPath), context, emptySet(), 0) }
            .getOrElse { t -> FileRouteResult.Error(t.toViewerCode(), t.message ?: "No se pudo resolver la ruta") }
    }

    private fun routeInternal(path: String, context: VirtualFsAccessContext, seen: Set<String>, depth: Int): FileRouteResult {
        if (depth > 8) throw VirtualAliasDepthExceededException(path)
        val stat = vfs.stat(path, context)
        val resolvedPath = aliasTarget(path)
        if (resolvedPath != null) {
            if (path in seen) throw VirtualAliasLoopDetectedException(path)
            val targetStat = vfs.stat(resolvedPath, context)
            if (targetStat.type == VirtualFsNodeType.MISSING) throw VirtualAliasTargetNotFoundException(resolvedPath)
            return when (targetStat.type) {
                VirtualFsNodeType.DIRECTORY -> FileRouteResult.Navigate(FileDestination.Browser(resolvedPath))
                VirtualFsNodeType.FILE, VirtualFsNodeType.GENERATED -> FileRouteResult.Navigate(routeFileByFormat(resolvedPath))
                VirtualFsNodeType.ALIAS -> routeInternal(resolvedPath, context, seen + path, depth + 1)
                VirtualFsNodeType.MISSING -> throw VirtualAliasTargetNotFoundException(resolvedPath)
            }
        }
        return when (stat.type) {
            VirtualFsNodeType.DIRECTORY -> FileRouteResult.Navigate(FileDestination.Browser(stat.virtualPath))
            VirtualFsNodeType.FILE, VirtualFsNodeType.GENERATED -> FileRouteResult.Navigate(routeFileByFormat(stat.virtualPath))
            VirtualFsNodeType.ALIAS -> throw VirtualAliasTargetNotFoundException(stat.virtualPath)
            VirtualFsNodeType.MISSING -> throw VirtualPathNotFoundException(stat.virtualPath)
        }
    }

    private fun aliasTarget(path: String): String? = when {
        path == "/sdcard" -> "/storage/emulated/0"
        path.startsWith("/sdcard/") -> "/storage/emulated/0" + path.removePrefix("/sdcard")
        else -> null
    }

    private fun routeFileByFormat(path: String): FileDestination {
        val name = path.lowercase(Locale.US)
        return when {
            name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".properties") || name.endsWith(".ini") || name.endsWith(".conf") || name.endsWith(".csv") || name.endsWith(".md") -> FileDestination.TextViewer(path)
            name.endsWith(".json") -> FileDestination.JsonViewer(path)
            name.endsWith(".xml") -> FileDestination.XmlViewer(path)
            name.endsWith(".db-journal") || name.endsWith(".db-wal") || name.endsWith(".db-shm") -> FileDestination.HexViewer(path)
            name.endsWith(".db") || name.endsWith(".sqlite") || name.endsWith(".sqlite3") -> FileDestination.SQLiteViewer(path)
            name.endsWith(".apk") -> FileDestination.ApkViewer(path)
            name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp") -> FileDestination.ImageViewer(path)
            else -> FileDestination.HexViewer(path)
        }
    }

    private fun normalize(input: String): String = if (input.isBlank()) "/" else (if (input.startsWith('/')) input else "/$input").replace(Regex("/{2,}"), "/").removeSuffix("/").ifBlank { "/" }
}

fun Throwable.toViewerCode(): ViewerErrorCode = when (this) {
    is VirtualPathNotFoundException -> ViewerErrorCode.PATH_NOT_FOUND
    is VirtualPathNotFileException -> ViewerErrorCode.PATH_NOT_FILE
    is VirtualAccessDeniedException, is SecurityException -> ViewerErrorCode.ACCESS_DENIED
    is VirtualAliasTargetNotFoundException -> ViewerErrorCode.ALIAS_TARGET_NOT_FOUND
    is VirtualAliasLoopDetectedException -> ViewerErrorCode.ALIAS_LOOP_DETECTED
    is VirtualAliasDepthExceededException -> ViewerErrorCode.ALIAS_DEPTH_EXCEEDED
    else -> ViewerErrorCode.UNKNOWN_VIEWER_ERROR
}
