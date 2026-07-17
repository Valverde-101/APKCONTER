package com.valcrono.virtualstorage
import kotlin.test.*
class SharedStoragePermissionTest { @Test fun sharedRequiresGrantForApps(){ val fs=VirtualFileSystem(VirtualFsNamespace(createTempDir())); assertFails{ fs.resolve("/storage/emulated/0/Shared", VirtualFsAccessContext.app("a.b.c")) }; assertEquals("shared", fs.resolve("/storage/emulated/0/Shared", VirtualFsAccessContext.app("a.b.c", permissions=setOf(VirtualFsPermission.READ_SHARED_STORAGE))).owner) } }
