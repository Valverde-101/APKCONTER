package com.valcrono.virtualspace

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "virtual_packages", primaryKeys = ["packageName", "virtualUserId"])
data class VirtualPackageEntity(val packageName:String,val label:String,val versionCode:Long,val versionName:String,val minSdk:Int,val targetSdk:Int,val sha256:String,val apkInternalPath:String,val installTime:Long,val updateTime:Long,val primaryAbi:String?,val hasNativeLibraries:Boolean,val mainActivity:String?,val entryPointClass:String?,val compatibilityLevel:String,val enabled:Boolean,val damaged:Boolean,val virtualUserId:Int,val virtualUid:Int,val runtimeMode:String = "COOPERATIVE")
@Entity(tableName = "virtual_components", primaryKeys = ["packageName", "virtualUserId", "name", "type"])
data class VirtualComponentEntity(val packageName:String,val virtualUserId:Int,val name:String,val type:String,val exported:Boolean,val processName:String?)
@Entity(tableName = "virtual_permissions", primaryKeys = ["packageName", "virtualUserId", "name"])
data class VirtualPermissionEntity(val packageName:String,val virtualUserId:Int,val name:String)
@Entity(tableName = "virtual_install_sessions", primaryKeys = ["id"])
data class VirtualInstallSessionEntity(val id:String,val packageName:String?,val source:String,val startedAt:Long,val updatedAt:Long,val state:String,val error:String?)
@Entity(tableName = "virtual_storage_records", primaryKeys = ["packageName", "virtualUserId"])
data class VirtualStorageRecordEntity(val packageName:String,val virtualUserId:Int,val rootPath:String,val usedBytes:Long)
@Entity(tableName = "compatibility_issues", primaryKeys = ["packageName", "virtualUserId", "code"])
data class CompatibilityIssueEntity(val packageName:String,val virtualUserId:Int,val severity:String,val code:String,val message:String)
@Entity(tableName = "virtual_runtime_sessions", primaryKeys = ["sessionId"], indices = [Index(value = ["packageName", "virtualUserId"], unique = true)])
data class VirtualRuntimeSessionEntity(
    val sessionId:String,
    val packageName:String,
    val virtualUserId:Int,
    val state:String,
    val currentLaunchAttemptId:String?,
    val createdAt:Long,
    val startedAt:Long?,
    val lastActivityAt:Long,
    val lastHeartbeatAt:Long,
    val stoppedAt:Long?,
    val hostPid:Int?,
    val entryPoint:String?,
    val classLoaderState:String,
    val launchPhase:String,
    val errorCode:String?,
    val sanitizedError:String?,
)
@Entity(tableName = "virtual_shared_permissions", primaryKeys = ["packageName", "virtualUserId", "permission"])
data class VirtualSharedPermissionEntity(val packageName:String,val virtualUserId:Int,val permission:String,val grantedAt:Long,val grantedBy:String)
@Entity(tableName = "virtual_fs_access_log", primaryKeys = ["id"])
data class VirtualFsAccessLogEntity(val id:String,val virtualUserId:Int,val packageName:String?,val role:String,val virtualPath:String,val operation:String,val allowed:Boolean,val at:Long)
@Entity(tableName = "virtual_launch_tokens", primaryKeys = ["token"])
data class VirtualLaunchTokenEntity(val token:String,val sessionId:String,val launchAttemptId:String,val virtualUserId:Int,val packageName:String,val activityName:String,val createdAt:Long,val expiresAt:Long,val consumedAt:Long?)
@Entity(tableName = "virtual_messages", indices = [Index("receiverPackage"), Index("senderPackage")])
data class VirtualMessageEntity(@PrimaryKey val messageId:String,val virtualUserId:Int,val senderPackage:String,val receiverPackage:String,val type:String,val payload:String,val createdAt:Long,val deliveredAt:Long?,val consumedAt:Long?,val status:String,val attemptCount:Int)

@Dao interface VirtualPackageDao {
    @Upsert suspend fun upsertPackage(pkg: VirtualPackageEntity)
    @Query("SELECT * FROM virtual_packages ORDER BY label") fun observePackages(): Flow<List<VirtualPackageEntity>>
    @Query("SELECT * FROM virtual_packages WHERE packageName=:packageName AND virtualUserId=:userId") suspend fun getPackage(packageName:String,userId:Int): VirtualPackageEntity?
    @Query("SELECT COUNT(*) FROM virtual_packages") suspend fun count(): Int
    @Query("UPDATE virtual_packages SET damaged=1 WHERE packageName=:packageName AND virtualUserId=:userId") suspend fun markDamaged(packageName:String,userId:Int)
    @Query("DELETE FROM virtual_packages WHERE packageName=:packageName AND virtualUserId=:userId") suspend fun deletePackage(packageName:String,userId:Int)
}
@Dao interface VirtualMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(message: VirtualMessageEntity): Long
    @Query("SELECT * FROM virtual_messages WHERE receiverPackage=:toPkg AND virtualUserId=:userId AND status IN ('PENDING','DELIVERING','DELIVERED') ORDER BY createdAt") suspend fun pendingFor(toPkg:String,userId:Int): List<VirtualMessageEntity>
    @Query("SELECT * FROM virtual_messages WHERE receiverPackage=:toPkg AND virtualUserId=:userId ORDER BY createdAt DESC LIMIT 100") suspend fun historyFor(toPkg:String,userId:Int): List<VirtualMessageEntity>
    @Query("UPDATE virtual_messages SET status='DELIVERED', deliveredAt=:time, attemptCount=attemptCount+1 WHERE messageId=:messageId AND status IN ('PENDING','DELIVERING')") suspend fun markDelivered(messageId:String,time:Long)
    @Query("UPDATE virtual_messages SET status='CONSUMED', consumedAt=:time WHERE messageId=:messageId AND status!='CONSUMED'") suspend fun markConsumed(messageId:String,time:Long)
    @Query("DELETE FROM virtual_messages WHERE receiverPackage=:toPkg AND virtualUserId=:userId") suspend fun clearHistory(toPkg:String,userId:Int)
    @Query("SELECT COUNT(*) FROM virtual_messages WHERE senderPackage=:pkg AND virtualUserId=:userId") suspend fun sentCount(pkg:String,userId:Int): Int
    @Query("SELECT COUNT(*) FROM virtual_messages WHERE receiverPackage=:pkg AND virtualUserId=:userId") suspend fun receivedCount(pkg:String,userId:Int): Int
}
@Dao interface VirtualRuntimeDao {
    @Upsert suspend fun upsert(session: VirtualRuntimeSessionEntity)
    @Query("SELECT * FROM virtual_runtime_sessions ORDER BY lastActivityAt DESC") fun observeSessions(): Flow<List<VirtualRuntimeSessionEntity>>
    @Query("SELECT * FROM virtual_runtime_sessions WHERE packageName=:packageName AND virtualUserId=:userId LIMIT 1") suspend fun forPackage(packageName:String,userId:Int): VirtualRuntimeSessionEntity?
    @Query("SELECT * FROM virtual_runtime_sessions WHERE sessionId=:sessionId LIMIT 1") suspend fun get(sessionId:String): VirtualRuntimeSessionEntity?
    @Query("SELECT * FROM virtual_runtime_sessions WHERE state='STARTING' AND lastHeartbeatAt < :deadline") suspend fun staleStarting(deadline:Long): List<VirtualRuntimeSessionEntity>
    @Query("SELECT * FROM virtual_runtime_sessions WHERE state IN ('ACTIVE','PAUSED')") suspend fun resumableSessions(): List<VirtualRuntimeSessionEntity>
    @Query("UPDATE virtual_runtime_sessions SET launchPhase=:phase,lastHeartbeatAt=:now,lastActivityAt=:now WHERE sessionId=:sessionId AND currentLaunchAttemptId=:attemptId AND state='STARTING'") suspend fun heartbeat(sessionId:String,attemptId:String,phase:String,now:Long): Int
    @Query("UPDATE virtual_runtime_sessions SET state='ACTIVE',launchPhase='RESUMED',startedAt=COALESCE(startedAt,:now),lastActivityAt=:now,lastHeartbeatAt=:now,hostPid=:pid,classLoaderState='LOADED',errorCode=NULL,sanitizedError=NULL WHERE sessionId=:sessionId AND currentLaunchAttemptId=:attemptId AND state='STARTING'") suspend fun acknowledgeActive(sessionId:String,attemptId:String,now:Long,pid:Int): Int
    @Query("UPDATE virtual_runtime_sessions SET state=:state,launchPhase=:phase,lastActivityAt=:now,lastHeartbeatAt=:now,stoppedAt=CASE WHEN :state IN ('STOPPED','ERROR') THEN :now ELSE stoppedAt END,errorCode=:errorCode,sanitizedError=:error WHERE sessionId=:sessionId AND currentLaunchAttemptId=:attemptId") suspend fun compareAndSetState(sessionId:String,attemptId:String,state:String,phase:String,now:Long,errorCode:String?,error:String?): Int
    @Query("UPDATE virtual_runtime_sessions SET state='ERROR',launchPhase='TIMEOUT',stoppedAt=:now,lastActivityAt=:now,errorCode='ERROR_START_TIMEOUT',sanitizedError='La aplicación tardó más de 30 segundos en iniciar. Puedes reintentar.',classLoaderState='FAILED' WHERE sessionId=:sessionId AND currentLaunchAttemptId=:attemptId AND state='STARTING' AND lastHeartbeatAt < :deadline") suspend fun timeoutLaunch(sessionId:String,attemptId:String,deadline:Long,now:Long): Int
    @Query("UPDATE virtual_runtime_sessions SET state='ERROR',launchPhase='STALE',stoppedAt=:now,lastActivityAt=:now,lastHeartbeatAt=:now,errorCode='ERROR_STALE_STARTING',sanitizedError='Inicio anterior limpiado al abrir VirtualSpace. Puedes reintentar.',classLoaderState='FAILED' WHERE state='STARTING'") suspend fun markStaleStarting(now:Long)
    @Query("UPDATE virtual_runtime_sessions SET state='STOPPED',launchPhase='PROCESS_LOST',stoppedAt=:now,lastActivityAt=:now,lastHeartbeatAt=:now,errorCode='STOPPED_PROCESS_LOST',sanitizedError='El proceso anterior ya no existe.' WHERE state IN ('ACTIVE','PAUSED')") suspend fun markProcessLost(now:Long)
    @Query("DELETE FROM virtual_runtime_sessions WHERE packageName=:packageName AND virtualUserId=:userId AND sessionId != :keepSessionId") suspend fun deleteDuplicatesFor(packageName:String,userId:Int,keepSessionId:String)
    @Query("SELECT COUNT(*) FROM virtual_runtime_sessions WHERE state='ACTIVE'") suspend fun activeCount(): Int
    @Query("UPDATE virtual_runtime_sessions SET state='STOPPED',launchPhase='STOPPED',stoppedAt=:now,lastActivityAt=:now,lastHeartbeatAt=:now WHERE state != 'STOPPED'") suspend fun stopAll(now:Long)
}
@Dao interface VirtualSharedPermissionDao { @Upsert suspend fun upsert(permission: VirtualSharedPermissionEntity); @Query("SELECT permission FROM virtual_shared_permissions WHERE packageName=:packageName AND virtualUserId=:userId") suspend fun permissions(packageName:String,userId:Int): List<String> }
@Dao interface VirtualFsAccessLogDao { @Insert suspend fun insert(row: VirtualFsAccessLogEntity) }
@Dao interface VirtualLaunchTokenDao { @Upsert suspend fun upsert(token: VirtualLaunchTokenEntity); @Query("SELECT * FROM virtual_launch_tokens WHERE token=:token AND consumedAt IS NULL AND expiresAt > :now") suspend fun getFresh(token:String,now:Long): VirtualLaunchTokenEntity?; @Query("UPDATE virtual_launch_tokens SET consumedAt=:time WHERE token=:token AND consumedAt IS NULL") suspend fun consume(token:String,time:Long): Int; @Query("UPDATE virtual_launch_tokens SET consumedAt=:time WHERE token=:token AND consumedAt IS NULL") suspend fun revoke(token:String,time:Long): Int; @Query("UPDATE virtual_launch_tokens SET consumedAt=:time WHERE sessionId=:sessionId AND launchAttemptId=:attemptId AND consumedAt IS NULL") suspend fun revokeAttempt(sessionId:String,attemptId:String,time:Long): Int; @Query("DELETE FROM virtual_launch_tokens WHERE expiresAt < :now OR (consumedAt IS NOT NULL AND consumedAt < :consumedBefore)") suspend fun cleanup(now:Long,consumedBefore:Long): Int }
@Dao interface VirtualInstallSessionDao { @Upsert suspend fun upsert(session: VirtualInstallSessionEntity) }
@Dao interface VirtualComponentDao { @Upsert suspend fun upsertAll(components: List<VirtualComponentEntity>); @Query("SELECT * FROM virtual_components WHERE packageName=:packageName AND virtualUserId=:userId") suspend fun forPackage(packageName:String,userId:Int): List<VirtualComponentEntity> }
@Dao interface VirtualPermissionDao {
    @Upsert suspend fun upsertAll(permissions: List<VirtualPermissionEntity>)
    @Query("SELECT name FROM virtual_permissions WHERE packageName=:packageName AND virtualUserId=:userId ORDER BY name") suspend fun permissionNames(packageName:String,userId:Int): List<String>
}
@Dao interface CompatibilityIssueDao { @Upsert suspend fun upsertAll(issues: List<CompatibilityIssueEntity>); @Query("SELECT * FROM compatibility_issues WHERE packageName=:packageName AND virtualUserId=:userId ORDER BY severity, code") suspend fun forPackage(packageName:String,userId:Int): List<CompatibilityIssueEntity> }
@Dao interface VirtualStorageRecordDao { @Upsert suspend fun upsert(record: VirtualStorageRecordEntity) }

@Database(entities=[VirtualPackageEntity::class,VirtualComponentEntity::class,VirtualPermissionEntity::class,VirtualInstallSessionEntity::class,VirtualStorageRecordEntity::class,CompatibilityIssueEntity::class,VirtualRuntimeSessionEntity::class,VirtualMessageEntity::class,VirtualSharedPermissionEntity::class,VirtualFsAccessLogEntity::class,VirtualLaunchTokenEntity::class], version=6, exportSchema=false)
abstract class ValcronoDatabase: RoomDatabase() {
    abstract fun packages(): VirtualPackageDao
    abstract fun messages(): VirtualMessageDao
    abstract fun runtime(): VirtualRuntimeDao
    abstract fun installSessions(): VirtualInstallSessionDao
    abstract fun components(): VirtualComponentDao
    abstract fun permissions(): VirtualPermissionDao
    abstract fun issues(): CompatibilityIssueDao
    abstract fun storageRecords(): VirtualStorageRecordDao
    abstract fun sharedPermissions(): VirtualSharedPermissionDao
    abstract fun fsAccessLog(): VirtualFsAccessLogDao
    abstract fun launchTokens(): VirtualLaunchTokenDao
}
