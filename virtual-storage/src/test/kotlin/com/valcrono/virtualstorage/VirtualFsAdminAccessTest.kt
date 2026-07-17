package com.valcrono.virtualstorage
import kotlin.test.*
import java.io.File
class VirtualFsAdminAccessTest { @Test fun adminReadsAllPackages(){ val r=createTempDir(); File(r,"virtual/users/0/a.b.c/data").mkdirs(); File(r,"virtual/users/0/d.e.f/data").mkdirs(); val fs=VirtualFileSystem(VirtualFsNamespace(r)); assertEquals("a.b.c", fs.resolve("/data/data/a.b.c", VirtualFsAccessContext.admin()).packageName); assertEquals("d.e.f", fs.resolve("/data/data/d.e.f", VirtualFsAccessContext.admin()).packageName) } }
