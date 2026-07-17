package com.valcrono.virtualstorage
import kotlin.test.*
class AndroidPathAliasTest { @Test fun sdcardAliasIsNormalizedSafely(){ assertEquals("/storage/emulated/0/Shared", VirtualFsResolver(VirtualFsNamespace(createTempDir())).normalize("/sdcard/Shared")) }; @Test fun traversalRejected(){ assertFailsWith<IllegalArgumentException>{ VirtualFsResolver(VirtualFsNamespace(createTempDir())).normalize("/sdcard/../data") } } }
