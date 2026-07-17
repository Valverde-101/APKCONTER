package com.valcrono.virtualstorage

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class GlobalVirtualFsTest {
    private fun fs(): Pair<File, VirtualFileSystem> {
        val root = Files.createTempDirectory("vfs-test").toFile()
        File(root, "virtual/users/0/com.valcrono.testapp.a/data/files").mkdirs()
        File(root, "virtual/users/0/com.valcrono.testapp.a/data/files/a-file.txt").writeText("a")
        File(root, "virtual/users/0/com.valcrono.testapp.b/data/databases").mkdirs()
        File(root, "virtual/users/0/com.valcrono.testapp.b/data/databases/b.db").writeText("b")
        File(root, "virtual/users/0/com.valcrono.testapp.a/apk").mkdirs()
        File(root, "virtual/users/0/com.valcrono.testapp.a/apk/package-x.apk").writeText("apk")
        return root to VirtualFileSystem(VirtualFsNamespace(root))
    }
    @Test fun rootShowsGlobalAndroidTree() { val (_, fs)=fs(); assertEquals(listOf("data","storage","sdcard","proc"), fs.list("/").map{it.name}) }
    @Test fun androidDataMapsToPackageData() { val (_, fs)=fs(); assertEquals("a-file.txt", fs.resolve("/data/data/com.valcrono.testapp.a/files/a-file.txt").name) }
    @Test fun dataUserAliasMapsToSameData() { val (_, fs)=fs(); assertEquals("b.db", fs.resolve("/data/user/0/com.valcrono.testapp.b/databases/b.db").name) }
}
