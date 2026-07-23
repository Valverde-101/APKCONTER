package com.valcrono.vpm

import kotlin.test.Test
import kotlin.test.assertFailsWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkValidatorV1Test {
    @Test fun rejectsApkWithoutManifest() {
        val apk = tempApk(mapOf("classes.dex" to byteArrayOf(0)))
        assertFailsWith<IllegalArgumentException> { ApkValidator().validate(apk) }
    }

    @Test fun rejectsApkWithoutClassesDex() {
        val apk = tempApk(mapOf("AndroidManifest.xml" to byteArrayOf(0)))
        assertFailsWith<IllegalArgumentException> { ApkValidator().validate(apk) }
    }

    @Test fun rejectsPathTraversalEntry() {
        val apk = tempApk(mapOf("AndroidManifest.xml" to byteArrayOf(0), "classes.dex" to byteArrayOf(0), "../evil" to byteArrayOf(1)))
        assertFailsWith<IllegalArgumentException> { ApkValidator().validate(apk) }
    }

    private fun tempApk(entries: Map<String, ByteArray>): File {
        val file = kotlin.io.path.createTempFile(prefix = "apk-validator", suffix = ".apk").toFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        file.deleteOnExit()
        return file
    }
}
