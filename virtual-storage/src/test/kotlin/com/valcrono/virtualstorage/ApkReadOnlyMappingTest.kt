package com.valcrono.virtualstorage
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
class ApkReadOnlyMappingTest { @Test fun baseApkIsReadOnly(){ val r=Files.createTempDirectory("vfs-test").toFile(); File(r,"virtual/users/0/a.b.c/apk").mkdirs(); File(r,"virtual/users/0/a.b.c/apk/package.apk").writeText("apk"); val n=VirtualFileSystem(VirtualFsNamespace(r)).resolve("/data/app/a.b.c/base.apk"); assertTrue(n.readOnly); assertEquals("r--", n.permissions) } }
