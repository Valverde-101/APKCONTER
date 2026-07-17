package com.valcrono.virtualstorage
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
class VirtualFsAppIsolationTest { @Test fun appsCannotReadEachOther(){ val r=Files.createTempDirectory("vfs-test").toFile(); File(r,"virtual/users/0/a.b.c/data").mkdirs(); File(r,"virtual/users/0/d.e.f/data").mkdirs(); val fs=VirtualFileSystem(VirtualFsNamespace(r)); assertThrows(IllegalArgumentException::class.java) { fs.resolve("/data/data/d.e.f", VirtualFsAccessContext.app("a.b.c")) }; assertThrows(IllegalArgumentException::class.java) { fs.resolve("/data/data/a.b.c", VirtualFsAccessContext.app("d.e.f")) }; assertEquals("a.b.c", fs.resolve("/data/data/a.b.c", VirtualFsAccessContext.admin()).packageName) } }
