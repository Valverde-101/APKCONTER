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
        instance ?: Room.databaseBuilder(context.applicationContext, ValcronoDatabase::class.java, "valcrono-virtualspace.db").addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17).enableMultiInstanceInvalidation().build().also { instance = it }
    }
    fun instanceId(context: Context): String = System.identityHashCode(get(context)).toString(16)
}

class VirtualRepository(private val context: Context) {
    val db: ValcronoDatabase = DatabaseProvider.get(context)
    private val storage = VirtualStorageManager(context.filesDir)
    val apkArtifacts = ApkArtifactRepository(context.applicationContext, db)
    fun packages(): Flow<List<VirtualPackageEntity>> = db.packages().observePackages()
    fun sessions(): Flow<List<VirtualRuntimeSessionEntity>> = db.runtime().observeSessions()
    suspend fun getPackage(packageName: String, userId: Int) = db.packages().getPackage(packageName, userId)
    suspend fun validateOnStartup() {
        db.packages().getAll().forEach { pkg -> runCatching { reprocessImportedPackage(pkg) }.onFailure { VLog.e("Repository", "reprocess failed package=${pkg.packageName}: ${it.message}", it) } }
    }
    private suspend fun reprocessImportedPackage(pkg: VirtualPackageEntity) {
        val apk = runCatching { apkArtifacts.resolveVerified(pkg).file }.getOrNull() ?: return
        val parsed = AndroidArchivePackageParser(context).parse(apk)
        val archive = ApkArchiveReaderV1.read(apk)
        val classification = classifyRuntime(parsed, archive, parsed.entryPointClass != null, emptyList())
        db.packages().upsertPackage(pkg.copy(
            label = parsed.label, displayName = parsed.label, versionCode = parsed.versionCode, versionName = parsed.versionName,
            minSdk = parsed.minSdk, targetSdk = parsed.targetSdk, primaryAbi = parsed.primaryAbi, hasNativeLibraries = parsed.hasNativeLibraries,
            mainActivity = parsed.mainActivity, launcherActivityName = parsed.mainActivity, launcherAliasName = parsed.launcherAliasName, launcherTargetActivity = parsed.launcherTargetActivity,
            entryPointClass = parsed.entryPointClass, cooperativeEntryPointClass = parsed.entryPointClass, applicationClassName = parsed.applicationClassName, hasCustomApplication = parsed.applicationClassName != null,
            compileSdk = parsed.compileSdk, dexCount = archive.dexCount, nativeLibraryCount = archive.nativeLibraryCount, hasNativeCode = archive.nativeLibraryCount > 0,
            isSplitApk = archive.isSplitApk, splitNamesJson = archive.splitNames.toJsonArray(), supportedAbisJson = archive.supportedAbis.toJsonArray(), nativeLibrariesJson = parsed.nativeLibraries.toJsonArray(),
            declaredActivitiesJson = parsed.components.filter { it.type == ComponentType.ACTIVITY }.map { it.name }.toJsonArray(), declaredServicesJson = parsed.components.filter { it.type == ComponentType.SERVICE }.map { it.name }.toJsonArray(), declaredReceiversJson = parsed.components.filter { it.type == ComponentType.RECEIVER }.map { it.name }.toJsonArray(), declaredProvidersJson = parsed.components.filter { it.type == ComponentType.PROVIDER }.map { it.name }.toJsonArray(), declaredProcessesJson = parsed.declaredProcesses.toJsonArray(), requestedPermissionsJson = parsed.permissions.map { it.name }.toJsonArray(), importantIntentFiltersJson = parsed.intentFilters.map { "${it.componentName}:${it.actions.joinToString("|")}:${it.categories.joinToString("|")}" }.toJsonArray(),
            detectedFramework = parsed.framework, highRiskApisJson = parsed.highRiskApis.toJsonArray(), compatibilityLevel = classification.compatibilityLevel, runtimeMode = classification.runtimeMode, genericRuntimeCapability = classification.genericRuntimeCapability, compatibilityReasonsJson = classification.reasons.toJsonArray(), blockingReasonsJson = classification.blockingReasons.toJsonArray(), importState = classification.importState, updatedAt = System.currentTimeMillis(), lastVerifiedAt = System.currentTimeMillis()
        ))
    }
    suspend fun importCopiedApk(apk: File, userId: Int, metadata: AndroidArchivePackageParser.AndroidParsedPackage): VirtualPackageEntity {
        val sessionId = UUID.randomUUID().toString(); val now=System.currentTimeMillis()
        db.installSessions().upsert(VirtualInstallSessionEntity(sessionId, metadata.packageName, apk.absolutePath, now, now, "CREATED", null))
        return try {
            db.installSessions().upsert(VirtualInstallSessionEntity(sessionId, metadata.packageName, apk.absolutePath, now, System.currentTimeMillis(), "VALIDATING", null))
            db.installSessions().upsert(VirtualInstallSessionEntity(sessionId, metadata.packageName, apk.absolutePath, now, System.currentTimeMillis(), "COPYING", null))
            val sourceSha = apk.inputStream().use { Sha256.hex(it) }
            val existing = db.packages().getPackage(metadata.packageName, userId)
            val virtualUid = existing?.virtualUid ?: 100000 + db.packages().count()
            val installTime = existing?.installTime ?: now
            val provisional = VirtualPackageEntity(metadata.packageName, metadata.label, metadata.versionCode, metadata.versionName, metadata.minSdk, metadata.targetSdk, sourceSha, apk.absolutePath, installTime, now, metadata.primaryAbi, metadata.hasNativeLibraries, metadata.mainActivity, metadata.entryPointClass, CompatibilityLevel.COMPATIBLE.name, true, false, userId, virtualUid)
            val artifact = apkArtifacts.importCanonical(apk, provisional)
            val imported = VirtualPackage(metadata.packageName, metadata.label, metadata.versionCode, metadata.versionName, metadata.minSdk, metadata.targetSdk, sourceSha, artifact.file.absolutePath, installTime, now, metadata.primaryAbi, metadata.hasNativeLibraries, metadata.mainActivity, CompatibilityLevel.COMPATIBLE, true, userId, virtualUid)
            db.installSessions().upsert(VirtualInstallSessionEntity(sessionId, metadata.packageName, apk.absolutePath, now, System.currentTimeMillis(), "ANALYZING", null))
            val runtimeMode = if (metadata.entryPointClass != null) "COOPERATIVE" else "INSPECTION_ONLY"
            val entity = VirtualPackageEntity(metadata.packageName, metadata.label, metadata.versionCode, metadata.versionName, metadata.minSdk, metadata.targetSdk, imported.sha256, imported.apkInternalPath, imported.installTime, imported.updateTime, metadata.primaryAbi, metadata.hasNativeLibraries, metadata.mainActivity, metadata.entryPointClass, imported.compatibilityLevel.name, true, false, userId, imported.virtualUid, runtimeMode, apkVirtualPath = apkArtifacts.virtualPath(metadata.packageName), apkIntegrityState = APK_INTEGRITY_VERIFIED, apkLastVerifiedAt = System.currentTimeMillis())
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
    fun AndroidArchivePackageParser.AndroidParsedPackage.toImporterMetadata(apk: File): ApkMetadata {
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

    suspend fun verifyPackage(pkg: VirtualPackageEntity): Boolean = runCatching { apkArtifacts.resolveVerified(pkg); true }.getOrDefault(false)
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


val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_virtual_messages_receiverPackage ON virtual_messages(receiverPackage)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_virtual_messages_senderPackage ON virtual_messages(senderPackage)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_virtual_messages_receiverPackage_virtualUserId_status_createdAt ON virtual_messages(receiverPackage, virtualUserId, status, createdAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_virtual_messages_senderPackage_virtualUserId_createdAt ON virtual_messages(senderPackage, virtualUserId, createdAt)")
        db.execSQL("UPDATE virtual_messages SET status='PENDING' WHERE status='DELIVERING'")
    }
}


val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN lastHeartbeatElapsedRealtime INTEGER")
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN lastHeartbeatWallClock INTEGER")
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN binderGeneration INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN binderAlive INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN reclaimInProgress INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN lastReclaimReason TEXT")
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN lastReclaimAt INTEGER")
        db.execSQL("UPDATE runtime_slots SET state='FREE', packageName=NULL, virtualUserId=NULL, sessionId=NULL, launchAttemptId=NULL, hostPid=NULL, assignedAt=NULL, startedAt=NULL, lastHeartbeatAt=NULL, stoppedAt=strftime('%s','now')*1000, pssBytes=NULL, errorCode=NULL, errorMessage=NULL, activityAttached=0, binderAlive=0, lastReclaimReason='MIGRATION_RECONCILE', lastReclaimAt=strftime('%s','now')*1000 WHERE state IN ('RESERVED','STARTING','ACTIVE_FOREGROUND','ACTIVE_BACKGROUND','PAUSED_BY_USER','CRASHED','ERROR')")
        db.execSQL("UPDATE virtual_messages SET status='PENDING' WHERE status='DELIVERING'")
        db.execSQL("UPDATE virtual_runtime_sessions SET state='STOPPED', launchPhase='MIGRATION_RECONCILE', stoppedAt=strftime('%s','now')*1000, errorCode='STOPPED_PROCESS_LOST', sanitizedError='El proceso anterior ya no existe.' WHERE state IN ('ACTIVE','PAUSED','STARTING')")
    }
}


val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN reservationToken TEXT")
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN runtimeGeneration INTEGER")
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN reservedAtElapsed INTEGER")
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN startupDeadlineElapsed INTEGER")
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN slotEpoch INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN lastMutationReason TEXT")
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN lastMutationCaller TEXT")
        db.execSQL("ALTER TABLE runtime_slots ADD COLUMN lastMutationElapsed INTEGER")
        db.execSQL("UPDATE runtime_slots SET state='FREE', packageName=NULL, virtualUserId=NULL, sessionId=NULL, launchAttemptId=NULL, reservationToken=NULL, runtimeGeneration=NULL, reservedAtElapsed=NULL, startupDeadlineElapsed=NULL, hostPid=NULL, assignedAt=NULL, startedAt=NULL, lastHeartbeatAt=NULL, lastHeartbeatElapsedRealtime=NULL, lastHeartbeatWallClock=NULL, stoppedAt=strftime('%s','now')*1000, pssBytes=NULL, errorCode=NULL, errorMessage=NULL, activityAttached=0, binderAlive=0, reclaimInProgress=0, lastReclaimReason='MIGRATION_11_12', lastReclaimAt=strftime('%s','now')*1000, lastMutationReason='SLOT_NORMALIZED_FREE', lastMutationCaller='MIGRATION_11_12', lastMutationElapsed=0 WHERE state='FREE' OR sessionId IS NULL OR packageName IS NULL")
    }
}


val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE virtual_runtime_sessions ADD COLUMN hasReachedActiveAck INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE virtual_runtime_sessions ADD COLUMN firstActiveAtElapsed INTEGER")
        db.execSQL("ALTER TABLE virtual_runtime_sessions ADD COLUMN lastActiveAtElapsed INTEGER")
        db.execSQL("ALTER TABLE virtual_runtime_sessions ADD COLUMN lastRuntimeExitReason TEXT")
        db.execSQL("ALTER TABLE virtual_runtime_sessions ADD COLUMN processExitDetectedBy TEXT")
        db.execSQL("ALTER TABLE virtual_runtime_sessions ADD COLUMN lastHeartbeatSequence INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE virtual_runtime_sessions ADD COLUMN processStartElapsedRealtime INTEGER")
        db.execSQL("UPDATE virtual_runtime_sessions SET hasReachedActiveAck=1, classLoaderState='LOADED' WHERE state IN ('ACTIVE','PAUSED') OR classLoaderState='LOADED'")
        db.execSQL("UPDATE virtual_runtime_sessions SET state='STOPPED', errorCode=NULL, sanitizedError=NULL, lastRuntimeExitReason=COALESCE(errorCode, launchPhase), processExitDetectedBy='HOST_RECOVERY_FAILED' WHERE hasReachedActiveAck=1 AND state='ERROR'")
    }
}
