package com.valcrono.virtualstorage
import org.junit.Assert.*
import org.junit.Test
class AndroidPathAliasTest { @Test fun sdcardAliasIsNormalizedSafely(){ assertEquals("/storage/emulated/0/Shared", VirtualFsResolver(VirtualFsNamespace(createTempDir())).normalize("/sdcard/Shared")) }; @Test fun traversalRejected(){ assertThrows(IllegalArgumentException::class.java) { VirtualFsResolver(VirtualFsNamespace(createTempDir())).normalize("/sdcard/../data") } } }
