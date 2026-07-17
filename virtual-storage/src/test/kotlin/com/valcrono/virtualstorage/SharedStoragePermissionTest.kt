package com.valcrono.virtualstorage
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
class SharedStoragePermissionTest { @Test fun sharedRequiresGrantForApps(){ val fs=VirtualFileSystem(VirtualFsNamespace(Files.createTempDirectory("vfs-test").toFile())); assertThrows(IllegalArgumentException::class.java) { fs.resolve("/storage/emulated/0/Shared", VirtualFsAccessContext.app("a.b.c")) }; assertEquals("shared", fs.resolve("/storage/emulated/0/Shared", VirtualFsAccessContext.app("a.b.c", permissions=setOf(VirtualFsPermission.READ_SHARED_STORAGE))).owner) } }
