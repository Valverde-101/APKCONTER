package com.valcrono.virtualstorage
import kotlin.test.*
import java.io.File
class VirtualFsAppIsolationTest { @Test fun appsCannotReadEachOther(){ val r=createTempDir(); File(r,"virtual/users/0/a.b.c/data").mkdirs(); File(r,"virtual/users/0/d.e.f/data").mkdirs(); val fs=VirtualFileSystem(VirtualFsNamespace(r)); assertFails{ fs.resolve("/data/data/d.e.f", VirtualFsAccessContext.app("a.b.c")) }; assertFails{ fs.resolve("/data/data/a.b.c", VirtualFsAccessContext.app("d.e.f")) }; assertEquals("a.b.c", fs.resolve("/data/data/a.b.c", VirtualFsAccessContext.admin()).packageName) } }
