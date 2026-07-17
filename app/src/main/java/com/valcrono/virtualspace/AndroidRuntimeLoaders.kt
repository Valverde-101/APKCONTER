package com.valcrono.virtualspace

import android.content.Context
import com.valcrono.core.Sha256
import com.valcrono.core.VLog
import dalvik.system.DexClassLoader
import java.io.File

class AndroidVirtualApkClassLoaderFactory(private val context: Context) {
    fun create(apkPath: String, expectedSha256: String, packageName: String): ClassLoader {
        val apk = File(apkPath)
        require(apk.isFile && !apk.canWrite()) { "APK must exist and be read-only" }
        val actualSha = apk.inputStream().use { Sha256.hex(it) }
        require(actualSha == expectedSha256) { "APK SHA-256 mismatch" }
        val optimizedDir = File(context.codeCacheDir, "virtual-dex/$packageName").apply { mkdirs() }
        VLog.i("DexLoader", "loading package=$packageName apk=$apkPath oat=$optimizedDir")
        return DexClassLoader(apk.absolutePath, optimizedDir.absolutePath, null, context.classLoader)
    }
}
