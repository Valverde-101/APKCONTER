package com.valcrono.vfm
import com.valcrono.virtualstorage.*; import org.junit.Assert.*; import org.junit.Test; import java.nio.file.Files
class FileManagerTest{ @Test fun checksumsExport(){ val root=Files.createTempDirectory("vs").toFile(); val sm=VirtualStorageManager(root); sm.createPackageStorage(0,"p",1); sm.resolver().resolve(0,"p","data/files/a.txt").writeText("abc"); val sums=VirtualFileManager(sm.resolver()).checksums(sm.resolver().packageRoot(0,"p")); assertTrue(sums.keys.any{it.endsWith("a.txt")}) } }
