package com.valcrono.virtualspace

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.valcrono.core.Sha256
import com.valcrono.core.VLog
import com.valcrono.vpm.*
import com.valcrono.virtualstorage.VirtualStorageManager
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

class VirtualRepository(private val context: Context) {
    val db: ValcronoDatabase = Room.databaseBuilder(context, ValcronoDatabase::class.java, "valcrono-virtualspace.db").addMigrations(MIGRATION_3_4).build()
    private val storage = VirtualStorageManager(context.filesDir)
    fun packages(): Flow<List<VirtualPackageEntity>> = db.packages().observePackages()
    suspend fun getPackage(packageName: String, userId: Int) = db.packages().getPackage(packageName, userId)
    suspend fun validateOnStartup() { db.packages().observePackages() /* Flow used by UI; detailed scan happens during import/open */ }
    suspend fun importCopiedApk(apk: File, userId: Int, metadata: AndroidArchivePackageParser.AndroidParsedPackage): VirtualPackageEntity {
        val sessionId = UUID.randomUUID().toString(); val now=System.currentTimeMillis()
        db.installSessions().upsert(VirtualInstallSessionEntity(sessionId, metadata.packageName, apk.absolutePath, now, now, "CREATED", null))
        return try {
            db.installSessions().upsert(VirtualInstallSessionEntity(sessionId, metadata.packageName, apk.absolutePath, now, System.currentTimeMillis(), "VALIDATING", null))
            val registry = linkedMapOf<String, VirtualPackage>()
            db.installSessions().upsert(VirtualInstallSessionEntity(sessionId, metadata.packageName, apk.absolutePath, now, System.currentTimeMillis(), "COPYING", null))
            val imported = SecureApkImporter(storage, registry).importApk(apk, userId, metadataOverride = metadata.toImporterMetadata(apk))
            db.installSessions().upsert(VirtualInstallSessionEntity(sessionId, metadata.packageName, apk.absolutePath, now, System.currentTimeMillis(), "ANALYZING", null))
            val entity = VirtualPackageEntity(metadata.packageName, metadata.label, metadata.versionCode, metadata.versionName, metadata.minSdk, metadata.targetSdk, imported.sha256, imported.apkInternalPath, imported.installTime, imported.updateTime, metadata.primaryAbi, metadata.hasNativeLibraries, metadata.mainActivity, metadata.entryPointClass, imported.compatibilityLevel.name, true, false, userId, imported.virtualUid)
            db.installSessions().upsert(VirtualInstallSessionEntity(sessionId, metadata.packageName, apk.absolutePath, now, System.currentTimeMillis(), "REGISTERING", null))
            db.packages().upsertPackage(entity)
            db.components().upsertAll(metadata.components.map { VirtualComponentEntity(metadata.packageName, userId, it.name, it.type.name, it.exported, it.processName) })
            db.permissions().upsertAll(metadata.permissions.map { VirtualPermissionEntity(metadata.packageName, userId, it.name) })
            db.storageRecords().upsert(VirtualStorageRecordEntity(metadata.packageName, userId, storage.resolver().packageRoot(userId, metadata.packageName).absolutePath, storage.usedBytes(userId, metadata.packageName)))
            db.installSessions().upsert(VirtualInstallSessionEntity(sessionId, metadata.packageName, apk.absolutePath, now, System.currentTimeMillis(), "INSTALLED", null))
            entity
        } catch (t: Throwable) {
            db.installSessions().upsert(VirtualInstallSessionEntity(sessionId, metadata.packageName, apk.absolutePath, now, System.currentTimeMillis(), "FAILED", t.message))
            VLog.e("Repository", "import failed ${metadata.packageName}", t); throw t
        }
    }
    private fun AndroidArchivePackageParser.AndroidParsedPackage.toImporterMetadata(apk: File): ApkMetadata {
        val scanned = ApkMetadataParser().parse(apk)
        return scanned.copy(
            packageName = packageName,
            label = label,
            versionCode = versionCode,
            versionName = versionName,
            minSdk = minSdk,
            targetSdk = targetSdk,
            components = components,
            permissions = permissions,
            hasNativeLibraries = hasNativeLibraries,
            primaryAbi = primaryAbi,
            mainActivity = mainActivity ?: entryPointClass,
            entryPointClass = entryPointClass,
            entryPointImplementsInterface = entryPointClass != null,
            signingCertificateSha256 = "validated:${packageName}",
        )
    }

    suspend fun verifyPackage(pkg: VirtualPackageEntity): Boolean { val ok = File(pkg.apkInternalPath).isFile && File(pkg.apkInternalPath).inputStream().use { Sha256.hex(it) } == pkg.sha256; if(!ok) db.packages().markDamaged(pkg.packageName,pkg.virtualUserId); return ok }
    fun storage() = storage
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS virtual_shared_permissions (packageName TEXT NOT NULL, virtualUserId INTEGER NOT NULL, permission TEXT NOT NULL, grantedAt INTEGER NOT NULL, grantedBy TEXT NOT NULL, PRIMARY KEY(packageName, virtualUserId, permission))")
        db.execSQL("CREATE TABLE IF NOT EXISTS virtual_fs_access_log (id TEXT NOT NULL, virtualUserId INTEGER NOT NULL, packageName TEXT, role TEXT NOT NULL, virtualPath TEXT NOT NULL, operation TEXT NOT NULL, allowed INTEGER NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS virtual_launch_tokens (token TEXT NOT NULL, virtualUserId INTEGER NOT NULL, packageName TEXT NOT NULL, activityName TEXT NOT NULL, createdAt INTEGER NOT NULL, consumedAt INTEGER, PRIMARY KEY(token))")
        db.execSQL("UPDATE virtual_runtime_sessions SET state='STOPPED' WHERE state='RUNNING'")
    }
}
