package com.valcrono.virtualstorage
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
class AndroidPathAliasTest { @Test fun sdcardAliasIsNormalizedSafely(){ assertEquals("/storage/emulated/0/Shared", VirtualFsResolver(VirtualFsNamespace(Files.createTempDirectory("vfs-test").toFile())).normalize("/sdcard/Shared")) }; @Test fun traversalRejected(){ assertThrows(IllegalArgumentException::class.java) { VirtualFsResolver(VirtualFsNamespace(Files.createTempDirectory("vfs-test").toFile())).normalize("/sdcard/../data") } } }
