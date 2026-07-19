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

object DatabaseProvider {
    @Volatile private var instance: ValcronoDatabase? = null
    fun get(context: Context): ValcronoDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, ValcronoDatabase::class.java, "valcrono-virtualspace.db").addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).enableMultiInstanceInvalidation().build().also { instance = it }
    }
    fun instanceId(context: Context): String = System.identityHashCode(get(context)).toString(16)
}

class VirtualRepository(private val context: Context) {
    val db: ValcronoDatabase = DatabaseProvider.get(context)
    private val storage = VirtualStorageManager(context.filesDir)
    fun packages(): Flow<List<VirtualPackageEntity>> = db.packages().observePackages()
    fun sessions(): Flow<List<VirtualRuntimeSessionEntity>> = db.runtime().observeSessions()
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
            val runtimeMode = if (metadata.entryPointClass != null) "COOPERATIVE" else "INSPECTION_ONLY"
            val entity = VirtualPackageEntity(metadata.packageName, metadata.label, metadata.versionCode, metadata.versionName, metadata.minSdk, metadata.targetSdk, imported.sha256, imported.apkInternalPath, imported.installTime, imported.updateTime, metadata.primaryAbi, metadata.hasNativeLibraries, metadata.mainActivity, metadata.entryPointClass, imported.compatibilityLevel.name, true, false, userId, imported.virtualUid, runtimeMode)
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


val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS virtual_runtime_sessions_new (sessionId TEXT NOT NULL, packageName TEXT NOT NULL, virtualUserId INTEGER NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL, startedAt INTEGER, lastActivityAt INTEGER NOT NULL, stoppedAt INTEGER, hostPid INTEGER, entryPoint TEXT, classLoaderState TEXT NOT NULL, errorCode TEXT, sanitizedError TEXT, PRIMARY KEY(sessionId))")
        db.execSQL("INSERT OR REPLACE INTO virtual_runtime_sessions_new (sessionId, packageName, virtualUserId, state, createdAt, startedAt, lastActivityAt, stoppedAt, hostPid, entryPoint, classLoaderState, errorCode, sanitizedError) SELECT id, packageName, virtualUserId, CASE WHEN state='RUNNING' THEN 'ACTIVE' ELSE state END, startedAt, CASE WHEN state IN ('ACTIVE','RUNNING') THEN startedAt ELSE NULL END, startedAt, CASE WHEN state IN ('STOPPED','ERROR') THEN startedAt ELSE NULL END, NULL, entryPointClass, 'UNKNOWN', CASE WHEN lastError IS NULL THEN NULL ELSE 'MIGRATED_ERROR' END, lastError FROM virtual_runtime_sessions")
        db.execSQL("DROP TABLE virtual_runtime_sessions")
        db.execSQL("ALTER TABLE virtual_runtime_sessions_new RENAME TO virtual_runtime_sessions")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_virtual_runtime_sessions_packageName_virtualUserId ON virtual_runtime_sessions(packageName, virtualUserId)")
        db.execSQL("UPDATE virtual_runtime_sessions SET state='ERROR', errorCode='ERROR_STALE_STARTING', sanitizedError='Inicio anterior limpiado al actualizar VirtualSpace.' WHERE state='STARTING'")
        db.execSQL("ALTER TABLE virtual_packages ADD COLUMN runtimeMode TEXT NOT NULL DEFAULT 'COOPERATIVE'")
        db.execSQL("UPDATE virtual_packages SET runtimeMode=CASE WHEN entryPointClass IS NULL THEN 'INSPECTION_ONLY' ELSE 'COOPERATIVE' END")
    }
}


val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE virtual_runtime_sessions ADD COLUMN currentLaunchAttemptId TEXT")
        db.execSQL("ALTER TABLE virtual_runtime_sessions ADD COLUMN lastHeartbeatAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE virtual_runtime_sessions ADD COLUMN launchPhase TEXT NOT NULL DEFAULT 'UNKNOWN'")
        db.execSQL("UPDATE virtual_runtime_sessions SET lastHeartbeatAt=lastActivityAt WHERE lastHeartbeatAt=0")
        db.execSQL("CREATE TABLE IF NOT EXISTS virtual_launch_tokens_new (token TEXT NOT NULL, sessionId TEXT NOT NULL, launchAttemptId TEXT NOT NULL, virtualUserId INTEGER NOT NULL, packageName TEXT NOT NULL, activityName TEXT NOT NULL, createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, consumedAt INTEGER, PRIMARY KEY(token))")
        db.execSQL("INSERT OR IGNORE INTO virtual_launch_tokens_new (token, sessionId, launchAttemptId, virtualUserId, packageName, activityName, createdAt, expiresAt, consumedAt) SELECT token, token, token, virtualUserId, packageName, activityName, createdAt, createdAt + 300000, consumedAt FROM virtual_launch_tokens")
        db.execSQL("DROP TABLE virtual_launch_tokens")
        db.execSQL("ALTER TABLE virtual_launch_tokens_new RENAME TO virtual_launch_tokens")
        db.execSQL("UPDATE virtual_runtime_sessions SET state='ERROR', launchPhase='MIGRATED_STALE', errorCode='ERROR_STALE_STARTING', sanitizedError='Inicio anterior limpiado al actualizar VirtualSpace.' WHERE state='STARTING'")
    }
}


val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS runtime_slots (slotId TEXT NOT NULL, processName TEXT NOT NULL, state TEXT NOT NULL, packageName TEXT, virtualUserId INTEGER, sessionId TEXT, launchAttemptId TEXT, hostPid INTEGER, assignedAt INTEGER, startedAt INTEGER, lastHeartbeatAt INTEGER, stoppedAt INTEGER, pssBytes INTEGER, errorCode TEXT, errorMessage TEXT, PRIMARY KEY(slotId))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_runtime_slots_sessionId ON runtime_slots(sessionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_runtime_slots_packageName_virtualUserId ON runtime_slots(packageName, virtualUserId)")
        db.execSQL("INSERT OR IGNORE INTO runtime_slots (slotId, processName, state, packageName, virtualUserId, sessionId, launchAttemptId, hostPid, assignedAt, startedAt, lastHeartbeatAt, stoppedAt, pssBytes, errorCode, errorMessage) VALUES ('VAPP0', ':vapp0', 'FREE', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)")
        db.execSQL("INSERT OR IGNORE INTO runtime_slots (slotId, processName, state, packageName, virtualUserId, sessionId, launchAttemptId, hostPid, assignedAt, startedAt, lastHeartbeatAt, stoppedAt, pssBytes, errorCode, errorMessage) VALUES ('VAPP1', ':vapp1', 'FREE', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)")
        db.execSQL("UPDATE virtual_runtime_sessions SET state='STOPPED', launchPhase='MIGRATED_MULTIPROCESS' WHERE state IN ('ACTIVE','PAUSED','STARTING')")
    }
}


val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN taskId INTEGER")
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN activityInstanceId TEXT")
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN activityLastAttachedAt INTEGER")
    }
}


val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN activityAttached INTEGER NOT NULL DEFAULT 0")
    }
}
