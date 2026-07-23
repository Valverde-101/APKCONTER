package com.valcrono.virtualspace

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "virtual_packages", primaryKeys = ["packageName", "virtualUserId"], indices = [Index(value = ["packageName"]), Index(value = ["sha256"]), Index(value = ["compatibilityLevel"]), Index(value = ["importState"])])
data class VirtualPackageEntity(val packageName:String,val label:String,val versionCode:Long,val versionName:String,val minSdk:Int,val targetSdk:Int,val sha256:String,val apkInternalPath:String,val installTime:Long,val updateTime:Long,val primaryAbi:String?,val hasNativeLibraries:Boolean,val mainActivity:String?,val entryPointClass:String?,val compatibilityLevel:String,val enabled:Boolean,val damaged:Boolean,val virtualUserId:Int,val virtualUid:Int,val runtimeMode:String = "COOPERATIVE", val displayName:String = label, val importedApkPath:String = apkInternalPath, val originalFileName:String = "", val apkSizeBytes:Long = 0, val certificateSha256:String? = null, val compileSdk:Int? = null, val supportedAbisJson:String = "[]", val dexCount:Int = 0, val nativeLibraryCount:Int = 0, val hasNativeCode:Boolean = hasNativeLibraries, val isSplitApk:Boolean = false, val splitNamesJson:String = "[]", val hasCustomApplication:Boolean = false, val applicationClassName:String? = null, val launcherActivityName:String? = mainActivity, val declaredActivitiesJson:String = "[]", val declaredServicesJson:String = "[]", val declaredReceiversJson:String = "[]", val declaredProvidersJson:String = "[]", val declaredPermissionsJson:String = "[]", val requestedPermissionsJson:String = "[]", val compatibilityReasonsJson:String = "[]", val cooperativeEntryPointClass:String? = entryPointClass, val genericRuntimeCapability:String = "NONE", val blockingReasonsJson:String = "[]", val importantIntentFiltersJson:String = "[]", val importState:String = "READY", val importErrorCode:String? = null, val importErrorMessage:String? = null, val importedAt:Long = installTime, val updatedAt:Long = updateTime, val lastVerifiedAt:Long? = null)
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
    val hasReachedActiveAck:Boolean = false,
    val firstActiveAtElapsed:Long? = null,
    val lastActiveAtElapsed:Long? = null,
    val lastRuntimeExitReason:String? = null,
    val processExitDetectedBy:String? = null,
    val lastHeartbeatSequence:Long = 0,
    val processStartElapsedRealtime:Long? = null,
)
@Entity(tableName = "virtual_shared_permissions", primaryKeys = ["packageName", "virtualUserId", "permission"])
data class VirtualSharedPermissionEntity(val packageName:String,val virtualUserId:Int,val permission:String,val grantedAt:Long,val grantedBy:String)
@Entity(tableName = "virtual_fs_access_log", primaryKeys = ["id"])
data class VirtualFsAccessLogEntity(val id:String,val virtualUserId:Int,val packageName:String?,val role:String,val virtualPath:String,val operation:String,val allowed:Boolean,val at:Long)
@Entity(tableName = "virtual_launch_tokens", primaryKeys = ["token"])
data class VirtualLaunchTokenEntity(val token:String,val sessionId:String,val launchAttemptId:String,val virtualUserId:Int,val packageName:String,val activityName:String,val createdAt:Long,val expiresAt:Long,val consumedAt:Long?)

@Entity(
    tableName = "runtime_slots",
    indices = [Index(value = ["sessionId"], unique = true), Index(value = ["packageName", "virtualUserId"])]
)
data class RuntimeSlotEntity(
    @PrimaryKey val slotId:String,
    val processName:String,
    val state:String,
    val packageName:String?,
    val virtualUserId:Int?,
    val sessionId:String?,
    val launchAttemptId:String?,
    val hostPid:Int?,
    val assignedAt:Long?,
    val startedAt:Long?,
    val lastHeartbeatAt:Long?,
    val lastHeartbeatElapsedRealtime:Long? = null,
    val lastHeartbeatWallClock:Long? = null,
    val stoppedAt:Long?,
    val pssBytes:Long?,
    val errorCode:String?,
    val errorMessage:String?,
    val taskId:Int? = null,
    val activityInstanceId:String? = null,
    val activityLastAttachedAt:Long? = null,
    val activityAttached:Boolean = false,
    val binderGeneration:Long = 0,
    val binderAlive:Boolean = false,
    val reclaimInProgress:Boolean = false,
    val lastReclaimReason:String? = null,
    val lastReclaimAt:Long? = null,
    val reservationToken:String? = null,
    val runtimeGeneration:Long? = null,
    val reservedAtElapsed:Long? = null,
    val startupDeadlineElapsed:Long? = null,
    val slotEpoch:Long = 0,
    val lastMutationReason:String? = null,
    val lastMutationCaller:String? = null,
    val lastMutationElapsed:Long? = null
)

@Entity(tableName = "virtual_messages", indices = [Index("receiverPackage"), Index("senderPackage"), Index(value = ["receiverPackage", "virtualUserId", "status", "createdAt"]), Index(value = ["senderPackage", "virtualUserId", "createdAt"])])
data class VirtualMessageEntity(@PrimaryKey val messageId:String,val virtualUserId:Int,val senderPackage:String,val receiverPackage:String,val type:String,val payload:String,val createdAt:Long,val deliveredAt:Long?,val consumedAt:Long?,val status:String,val attemptCount:Int)


@Dao interface RuntimeSlotDao {
    @Query("SELECT * FROM runtime_slots ORDER BY slotId") suspend fun getAll(): List<RuntimeSlotEntity>
    @Query("SELECT * FROM runtime_slots ORDER BY slotId") fun observeAll(): Flow<List<RuntimeSlotEntity>>
    @Query("SELECT * FROM runtime_slots WHERE slotId=:slotId LIMIT 1") suspend fun get(slotId:String): RuntimeSlotEntity?
    @Query("SELECT * FROM runtime_slots WHERE sessionId=:sessionId LIMIT 1") suspend fun findBySession(sessionId:String): RuntimeSlotEntity?
    @Query("SELECT * FROM runtime_slots WHERE packageName=:packageName AND virtualUserId=:virtualUserId LIMIT 1") suspend fun findByPackage(packageName:String, virtualUserId:Int): RuntimeSlotEntity?
    @Query("SELECT * FROM runtime_slots WHERE packageName=:packageName AND virtualUserId=:virtualUserId LIMIT 1") fun observeByPackage(packageName:String, virtualUserId:Int): Flow<RuntimeSlotEntity?>
    @Query("SELECT * FROM runtime_slots WHERE state='FREE' AND sessionId IS NULL AND packageName IS NULL AND launchAttemptId IS NULL AND reservationToken IS NULL AND hostPid IS NULL AND reclaimInProgress=0 AND slotId IN (:enabledSlotIds) ORDER BY slotId LIMIT 1") suspend fun firstFree(enabledSlotIds: List<String>): RuntimeSlotEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDefaults(slots: List<RuntimeSlotEntity>)
    @Query("UPDATE runtime_slots SET state='PROCESS_STARTING', packageName=:packageName, virtualUserId=:virtualUserId, sessionId=:sessionId, launchAttemptId=:attemptId, reservationToken=:reservationToken, runtimeGeneration=:runtimeGeneration, reservedAtElapsed=:elapsed, startupDeadlineElapsed=:startupDeadlineElapsed, slotEpoch=slotEpoch+1, hostPid=NULL, assignedAt=:now, startedAt=NULL, lastHeartbeatAt=NULL, lastHeartbeatElapsedRealtime=NULL, lastHeartbeatWallClock=NULL, stoppedAt=NULL, pssBytes=NULL, errorCode=NULL, errorMessage=NULL, lastMutationReason='SLOT_RESERVED', lastMutationCaller=:caller, lastMutationElapsed=:elapsed WHERE slotId=:slotId AND state='FREE' AND sessionId IS NULL AND packageName IS NULL AND launchAttemptId IS NULL AND reservationToken IS NULL") suspend fun reserveSlot(slotId:String, packageName:String, virtualUserId:Int, sessionId:String, attemptId:String, reservationToken:String, runtimeGeneration:Long, now:Long, elapsed:Long, startupDeadlineElapsed:Long, caller:String): Int

    @Query("UPDATE runtime_slots SET state='STOPPING', lastMutationReason=:reason, lastMutationCaller=:caller, lastMutationElapsed=:elapsed WHERE slotId=:slotId AND state != 'FREE'") suspend fun markStopping(slotId:String, reason:String, caller:String, elapsed:Long): Int
    @Query("UPDATE runtime_slots SET state='FREE', packageName=NULL, virtualUserId=NULL, sessionId=NULL, launchAttemptId=NULL, reservationToken=NULL, runtimeGeneration=NULL, reservedAtElapsed=NULL, startupDeadlineElapsed=NULL, hostPid=NULL, assignedAt=NULL, startedAt=NULL, lastHeartbeatAt=NULL, lastHeartbeatElapsedRealtime=NULL, lastHeartbeatWallClock=NULL, stoppedAt=:now, pssBytes=NULL, errorCode=NULL, errorMessage=NULL, taskId=NULL, activityInstanceId=NULL, activityLastAttachedAt=NULL, activityAttached=0, binderAlive=0, reclaimInProgress=0, lastReclaimReason=:reason, lastReclaimAt=:now, lastMutationReason='SLOT_RECLAIMED', lastMutationCaller=:caller, lastMutationElapsed=:elapsed WHERE slotId=:slotId") suspend fun forceFreeSlot(slotId:String, reason:String, caller:String, now:Long, elapsed:Long): Int
    @Query("UPDATE runtime_slots SET state=:toState, lastMutationReason=:reason, lastMutationCaller=:caller, lastMutationElapsed=:elapsed WHERE slotId=:slotId AND state=:fromState AND sessionId=:sessionId AND launchAttemptId=:attemptId AND reservationToken=:reservationToken") suspend fun compareAndSetOwnedState(slotId:String, fromState:String, toState:String, sessionId:String, attemptId:String, reservationToken:String, reason:String, caller:String, elapsed:Long): Int
    @Query("UPDATE runtime_slots SET state=:state, hostPid=:pid, startedAt=COALESCE(startedAt,:now), lastHeartbeatAt=:now, lastHeartbeatElapsedRealtime=:elapsed, lastHeartbeatWallClock=:now, pssBytes=:pssBytes, errorCode=NULL, errorMessage=NULL, lastMutationReason='ACTIVE_ACK_ACCEPTED', lastMutationCaller=:caller, lastMutationElapsed=:elapsed WHERE slotId=:slotId AND sessionId=:sessionId AND launchAttemptId=:attemptId AND reservationToken=:reservationToken AND runtimeGeneration=:runtimeGeneration AND slotEpoch=:slotEpoch AND state IN ('SERVICE_CONNECTED','LOAD_REQUEST_SENT','CLASSLOADER_READY','ACTIVITY_STARTING')") suspend fun acknowledgeStarted(slotId:String, sessionId:String, attemptId:String, reservationToken:String, runtimeGeneration:Long, slotEpoch:Long, pid:Int, state:String, pssBytes:Long?, now:Long, elapsed:Long, caller:String): Int
    @Query("UPDATE runtime_slots SET lastHeartbeatAt=:now, lastHeartbeatElapsedRealtime=:elapsed, lastHeartbeatWallClock=:now, hostPid=:pid, state=:state, pssBytes=:pssBytes, lastMutationReason='HEARTBEAT_RECEIVED', lastMutationCaller=:caller, lastMutationElapsed=:elapsed WHERE slotId=:slotId AND sessionId=:sessionId AND launchAttemptId=:attemptId AND reservationToken=:reservationToken AND runtimeGeneration=:runtimeGeneration AND slotEpoch=:slotEpoch AND state IN ('ACTIVE_FOREGROUND','ACTIVE_BACKGROUND','PAUSED_BY_USER')") suspend fun heartbeat(slotId:String, sessionId:String, attemptId:String, reservationToken:String, runtimeGeneration:Long, slotEpoch:Long, pid:Int, state:String, pssBytes:Long?, now:Long, elapsed:Long, caller:String): Int
    @Query("UPDATE runtime_slots SET pssBytes=:pssBytes, lastHeartbeatAt=:now, lastHeartbeatElapsedRealtime=:elapsed, lastHeartbeatWallClock=:now, hostPid=:pid WHERE slotId=:slotId AND sessionId=:sessionId") suspend fun updatePss(slotId:String, sessionId:String, pid:Int, pssBytes:Long, now:Long, elapsed:Long): Int
    @Query("UPDATE runtime_slots SET taskId=:taskId, activityInstanceId=:activityInstanceId, activityLastAttachedAt=:now, activityAttached=1, hostPid=:pid, binderAlive=1, binderGeneration=binderGeneration+1 WHERE slotId=:slotId AND sessionId=:sessionId") suspend fun markActivityAttached(slotId:String, sessionId:String, taskId:Int, activityInstanceId:String, now:Long, pid:Int): Int
    @Query("UPDATE runtime_slots SET activityAttached=0 WHERE slotId=:slotId AND sessionId=:sessionId AND activityInstanceId=:activityInstanceId") suspend fun markActivityDetached(slotId:String, sessionId:String, activityInstanceId:String): Int
    @Query("UPDATE runtime_slots SET taskId=NULL, activityInstanceId=NULL, activityLastAttachedAt=NULL, activityAttached=0 WHERE slotId=:slotId AND sessionId=:sessionId") suspend fun clearActivityTask(slotId:String, sessionId:String): Int
    @Query("UPDATE runtime_slots SET state='CRASHED', errorCode=:code, errorMessage=:message, stoppedAt=:now WHERE slotId=:slotId") suspend fun markCrashed(slotId:String, code:String, message:String?, now:Long): Int
    @Query("UPDATE runtime_slots SET state='FREE', packageName=NULL, virtualUserId=NULL, sessionId=NULL, launchAttemptId=NULL, reservationToken=NULL, runtimeGeneration=NULL, reservedAtElapsed=NULL, startupDeadlineElapsed=NULL, hostPid=NULL, assignedAt=NULL, startedAt=NULL, lastHeartbeatAt=NULL, lastHeartbeatElapsedRealtime=NULL, lastHeartbeatWallClock=NULL, stoppedAt=:now, pssBytes=NULL, errorCode=NULL, errorMessage=NULL, taskId=NULL, activityInstanceId=NULL, activityLastAttachedAt=NULL, activityAttached=0, binderAlive=0, reclaimInProgress=0, lastReclaimReason='RELEASE', lastReclaimAt=:now, lastMutationReason='SLOT_RELEASED', lastMutationCaller='release', lastMutationElapsed=:elapsed WHERE slotId=:slotId") suspend fun release(slotId:String, now:Long, elapsed:Long): Int
    @Query("UPDATE runtime_slots SET state='FREE', packageName=NULL, virtualUserId=NULL, sessionId=NULL, launchAttemptId=NULL, reservationToken=NULL, runtimeGeneration=NULL, reservedAtElapsed=NULL, startupDeadlineElapsed=NULL, hostPid=NULL, assignedAt=NULL, startedAt=NULL, lastHeartbeatAt=NULL, lastHeartbeatElapsedRealtime=NULL, lastHeartbeatWallClock=NULL, stoppedAt=:now, pssBytes=NULL, errorCode=NULL, errorMessage=NULL, taskId=NULL, activityInstanceId=NULL, activityLastAttachedAt=NULL, activityAttached=0, binderAlive=0, reclaimInProgress=0, lastReclaimReason=:reason, lastReclaimAt=:now, lastMutationReason='SLOT_NORMALIZED_FREE', lastMutationCaller=:reason, lastMutationElapsed=:elapsed WHERE (sessionId IS NULL AND packageName IS NULL AND launchAttemptId IS NULL AND state != 'FREE') OR (state='FREE' AND errorCode IS NOT NULL)") suspend fun normalizeEmptySlots(reason:String, now:Long, elapsed:Long): Int
    @Query("UPDATE runtime_slots SET state='FREE', packageName=NULL, virtualUserId=NULL, sessionId=NULL, launchAttemptId=NULL, reservationToken=NULL, runtimeGeneration=NULL, reservedAtElapsed=NULL, startupDeadlineElapsed=NULL, hostPid=NULL, assignedAt=NULL, startedAt=NULL, lastHeartbeatAt=NULL, lastHeartbeatElapsedRealtime=NULL, lastHeartbeatWallClock=NULL, stoppedAt=:now, pssBytes=NULL, errorCode=NULL, errorMessage=NULL, taskId=NULL, activityInstanceId=NULL, activityLastAttachedAt=NULL, activityAttached=0, binderAlive=0, reclaimInProgress=0, lastReclaimReason='STALE_HEARTBEAT', lastReclaimAt=:now, lastMutationReason='SLOT_RECLAIMED', lastMutationCaller='recoverStaleSlots', lastMutationElapsed=:now WHERE state IN ('ACTIVE_FOREGROUND','ACTIVE_BACKGROUND','PAUSED_BY_USER','CRASHED','ERROR') AND COALESCE(lastHeartbeatElapsedRealtime,lastHeartbeatAt,0) < :deadline") suspend fun recoverStaleSlots(deadline:Long, now:Long): Int
    @Query("UPDATE runtime_slots SET state='FREE', packageName=NULL, virtualUserId=NULL, sessionId=NULL, launchAttemptId=NULL, reservationToken=NULL, runtimeGeneration=NULL, reservedAtElapsed=NULL, startupDeadlineElapsed=NULL, hostPid=NULL, assignedAt=NULL, startedAt=NULL, lastHeartbeatAt=NULL, lastHeartbeatElapsedRealtime=NULL, lastHeartbeatWallClock=NULL, stoppedAt=:now, pssBytes=NULL, errorCode=NULL, errorMessage=NULL, taskId=NULL, activityInstanceId=NULL, activityLastAttachedAt=NULL, activityAttached=0, binderAlive=0, reclaimInProgress=0, lastReclaimReason=:reason, lastReclaimAt=:now, lastMutationReason='RECLAIM_COMPLETED', lastMutationCaller=:caller, lastMutationElapsed=:elapsed WHERE slotId=:slotId AND sessionId=:expectedSessionId AND launchAttemptId=:expectedLaunchAttemptId AND reservationToken=:expectedReservationToken") suspend fun reclaimSlot(slotId:String, expectedSessionId:String, expectedLaunchAttemptId:String, expectedReservationToken:String, reason:String, now:Long, elapsed:Long, caller:String): Int
}
@Dao interface VirtualPackageDao {
    @Upsert suspend fun upsertPackage(pkg: VirtualPackageEntity)
    @Query("SELECT * FROM virtual_packages ORDER BY label") fun observePackages(): Flow<List<VirtualPackageEntity>>
    @Query("SELECT * FROM virtual_packages ORDER BY label") suspend fun getAll(): List<VirtualPackageEntity>
    @Query("SELECT * FROM virtual_packages WHERE packageName=:packageName AND virtualUserId=:userId") suspend fun getPackage(packageName:String,userId:Int): VirtualPackageEntity?
    @Query("SELECT * FROM virtual_packages WHERE packageName=:packageName AND virtualUserId=:userId LIMIT 1") fun observePackage(packageName:String,userId:Int): Flow<VirtualPackageEntity?>
    @Query("SELECT COUNT(*) FROM virtual_packages") suspend fun count(): Int
    @Query("UPDATE virtual_packages SET damaged=1 WHERE packageName=:packageName AND virtualUserId=:userId") suspend fun markDamaged(packageName:String,userId:Int)
    @Query("DELETE FROM virtual_packages WHERE packageName=:packageName AND virtualUserId=:userId") suspend fun deletePackage(packageName:String,userId:Int)
}
@Dao interface VirtualMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(message: VirtualMessageEntity): Long
    @Query("SELECT * FROM virtual_messages WHERE receiverPackage=:toPackage AND virtualUserId=:virtualUserId AND status = 'PENDING' ORDER BY createdAt ASC, messageId ASC") fun observePendingFor(toPackage:String, virtualUserId:Int): Flow<List<VirtualMessageEntity>>
    @Query("SELECT * FROM virtual_messages WHERE receiverPackage=:toPkg AND virtualUserId=:userId AND status = 'PENDING' ORDER BY createdAt ASC, messageId ASC") suspend fun pendingFor(toPkg:String,userId:Int): List<VirtualMessageEntity>
    @Query("SELECT * FROM virtual_messages WHERE receiverPackage=:toPkg AND virtualUserId=:userId ORDER BY createdAt DESC LIMIT 100") suspend fun historyFor(toPkg:String,userId:Int): List<VirtualMessageEntity>
    @Query("UPDATE virtual_messages SET status='DELIVERING', attemptCount=attemptCount+1 WHERE messageId=:messageId AND status='PENDING'") suspend fun claimPending(messageId:String): Int
    @Query("UPDATE virtual_messages SET status='CONSUMED', deliveredAt=:time, consumedAt=:time WHERE messageId=:messageId AND status='DELIVERING'") suspend fun markConsumedAfterCallback(messageId:String,time:Long): Int
    @Query("UPDATE virtual_messages SET status='PENDING' WHERE messageId=:messageId AND status='DELIVERING'") suspend fun requeueDelivering(messageId:String): Int
    @Query("UPDATE virtual_messages SET status='PENDING' WHERE receiverPackage=:toPkg AND virtualUserId=:userId AND status='DELIVERING'") suspend fun requeueDeliveringFor(toPkg:String,userId:Int): Int
    @Query("UPDATE virtual_messages SET status='PENDING' WHERE receiverPackage=:toPkg AND virtualUserId=:userId AND status='DELIVERING' AND deliveredAt IS NULL AND createdAt < :deadline") suspend fun requeueStaleDelivering(toPkg:String,userId:Int,deadline:Long): Int
    @Query("UPDATE virtual_messages SET status='FAILED', consumedAt=:time WHERE messageId=:messageId AND status='DELIVERING'") suspend fun markFailed(messageId:String,time:Long): Int
    @Query("DELETE FROM virtual_messages WHERE receiverPackage=:toPkg AND virtualUserId=:userId") suspend fun clearHistory(toPkg:String,userId:Int)
    @Query("SELECT COUNT(*) FROM virtual_messages WHERE senderPackage=:pkg AND virtualUserId=:userId") suspend fun sentCount(pkg:String,userId:Int): Int
    @Query("SELECT COUNT(*) FROM virtual_messages WHERE receiverPackage=:pkg AND virtualUserId=:userId") suspend fun receivedCount(pkg:String,userId:Int): Int
    @Query("SELECT COUNT(*) FROM virtual_messages WHERE receiverPackage=:pkg AND virtualUserId=:userId AND status=:status") suspend fun countByStatus(pkg:String,userId:Int,status:String): Int
    @Query("SELECT * FROM virtual_messages WHERE (receiverPackage=:pkg OR senderPackage=:pkg) AND virtualUserId=:userId ORDER BY createdAt DESC LIMIT 1") suspend fun lastForPackage(pkg:String,userId:Int): VirtualMessageEntity?
}
@Dao interface VirtualRuntimeDao {
    @Upsert suspend fun upsert(session: VirtualRuntimeSessionEntity)
    @Query("SELECT * FROM virtual_runtime_sessions ORDER BY lastActivityAt DESC") fun observeSessions(): Flow<List<VirtualRuntimeSessionEntity>>
    @Query("SELECT * FROM virtual_runtime_sessions WHERE packageName=:packageName AND virtualUserId=:userId LIMIT 1") suspend fun forPackage(packageName:String,userId:Int): VirtualRuntimeSessionEntity?
    @Query("SELECT * FROM virtual_runtime_sessions WHERE packageName=:packageName AND virtualUserId=:userId LIMIT 1") fun observeByPackage(packageName:String,userId:Int): Flow<VirtualRuntimeSessionEntity?>
    @Query("SELECT * FROM virtual_runtime_sessions WHERE sessionId=:sessionId LIMIT 1") suspend fun get(sessionId:String): VirtualRuntimeSessionEntity?
    @Query("SELECT * FROM virtual_runtime_sessions WHERE state='STARTING' AND lastHeartbeatAt < :deadline") suspend fun staleStarting(deadline:Long): List<VirtualRuntimeSessionEntity>
    @Query("SELECT * FROM virtual_runtime_sessions WHERE state IN ('ACTIVE','PAUSED')") suspend fun resumableSessions(): List<VirtualRuntimeSessionEntity>
    @Query("UPDATE virtual_runtime_sessions SET launchPhase=:phase,lastHeartbeatAt=:now,lastActivityAt=:now WHERE sessionId=:sessionId AND currentLaunchAttemptId=:attemptId AND state='STARTING'") suspend fun heartbeat(sessionId:String,attemptId:String,phase:String,now:Long): Int
    @Query("UPDATE virtual_runtime_sessions SET state='ACTIVE',launchPhase='STATE_RECONCILED',lastActivityAt=:now,lastHeartbeatAt=:now,hostPid=:pid,classLoaderState='LOADED',hasReachedActiveAck=1,lastActiveAtElapsed=:elapsed,errorCode=NULL,sanitizedError=NULL WHERE sessionId=:sessionId") suspend fun repairActive(sessionId:String,now:Long,elapsed:Long,pid:Int): Int
    @Query("UPDATE virtual_runtime_sessions SET state=CASE WHEN :slotState IN ('ACTIVE_FOREGROUND','ACTIVE_BACKGROUND') THEN 'ACTIVE' ELSE state END, launchPhase=:phase, lastHeartbeatAt=:now, lastActivityAt=CASE WHEN :slotState IN ('ACTIVE_FOREGROUND','ACTIVE_BACKGROUND') THEN :now ELSE lastActivityAt END, hostPid=:pid, classLoaderState='LOADED', hasReachedActiveAck=1, lastActiveAtElapsed=:elapsed, lastHeartbeatSequence=lastHeartbeatSequence+1, errorCode=NULL, sanitizedError=NULL WHERE sessionId=:sessionId AND currentLaunchAttemptId=:attemptId AND hasReachedActiveAck=1") suspend fun heartbeatRuntimeSession(sessionId:String, attemptId:String, slotState:String, phase:String, now:Long, elapsed:Long, pid:Int): Int
    @Query("UPDATE virtual_runtime_sessions SET state='ACTIVE',launchPhase='RESUMED',startedAt=COALESCE(startedAt,:now),lastActivityAt=:now,lastHeartbeatAt=:now,hostPid=:pid,classLoaderState='LOADED',hasReachedActiveAck=1,firstActiveAtElapsed=COALESCE(firstActiveAtElapsed,:elapsed),lastActiveAtElapsed=:elapsed,errorCode=NULL,sanitizedError=NULL WHERE sessionId=:sessionId AND currentLaunchAttemptId=:attemptId AND state='STARTING'") suspend fun acknowledgeActive(sessionId:String,attemptId:String,now:Long,elapsed:Long,pid:Int): Int
    @Query("UPDATE virtual_runtime_sessions SET state=CASE WHEN hasReachedActiveAck=1 AND :state IN ('DEAD','ERROR','CRASHED') THEN 'STOPPED' ELSE :state END,launchPhase=:phase,lastActivityAt=:now,lastHeartbeatAt=:now,stoppedAt=CASE WHEN :state IN ('STOPPED','ERROR','DEAD','CRASHED') THEN :now ELSE stoppedAt END,errorCode=CASE WHEN hasReachedActiveAck=1 AND :state IN ('DEAD','ERROR','CRASHED') THEN NULL ELSE :errorCode END,sanitizedError=CASE WHEN hasReachedActiveAck=1 AND :state IN ('DEAD','ERROR','CRASHED') THEN NULL ELSE :error END,lastRuntimeExitReason=CASE WHEN hasReachedActiveAck=1 AND :state IN ('DEAD','ERROR','CRASHED','STOPPED') THEN :phase ELSE lastRuntimeExitReason END,processExitDetectedBy=CASE WHEN hasReachedActiveAck=1 AND :state IN ('DEAD','ERROR','CRASHED') THEN :phase ELSE processExitDetectedBy END WHERE sessionId=:sessionId AND currentLaunchAttemptId=:attemptId") suspend fun compareAndSetState(sessionId:String,attemptId:String,state:String,phase:String,now:Long,errorCode:String?,error:String?): Int
    @Query("UPDATE virtual_runtime_sessions SET state='ERROR',launchPhase='TIMEOUT',stoppedAt=:now,lastActivityAt=:now,errorCode='ERROR_START_TIMEOUT',sanitizedError='La aplicación tardó más de 30 segundos en iniciar. Puedes reintentar.',classLoaderState='FAILED' WHERE sessionId=:sessionId AND currentLaunchAttemptId=:attemptId AND state='STARTING' AND lastHeartbeatAt < :deadline") suspend fun timeoutLaunch(sessionId:String,attemptId:String,deadline:Long,now:Long): Int
    @Query("UPDATE virtual_runtime_sessions SET state='ERROR',launchPhase='STALE',stoppedAt=:now,lastActivityAt=:now,lastHeartbeatAt=:now,errorCode='ERROR_STALE_STARTING',sanitizedError='Inicio anterior limpiado al abrir VirtualSpace. Puedes reintentar.',classLoaderState='FAILED' WHERE state='STARTING'") suspend fun markStaleStarting(now:Long)
    @Query("UPDATE virtual_runtime_sessions SET state='DEAD',launchPhase='PROCESS_LOST',stoppedAt=:now,lastActivityAt=:now,lastHeartbeatAt=:now,errorCode='STOPPED_PROCESS_LOST',sanitizedError='El proceso anterior ya no existe.' WHERE state IN ('ACTIVE','PAUSED')") suspend fun markProcessLost(now:Long)
    @Query("DELETE FROM virtual_runtime_sessions WHERE packageName=:packageName AND virtualUserId=:userId AND sessionId != :keepSessionId") suspend fun deleteDuplicatesFor(packageName:String,userId:Int,keepSessionId:String)
    @Query("DELETE FROM virtual_runtime_sessions WHERE sessionId=:sessionId") suspend fun deleteSession(sessionId:String)
    @Query("SELECT COUNT(*) FROM virtual_runtime_sessions WHERE state='ACTIVE'") suspend fun activeCount(): Int
    @Query("UPDATE virtual_runtime_sessions SET state='STOPPED',launchPhase=:phase,stoppedAt=:now,lastActivityAt=:now,lastHeartbeatAt=:now,hostPid=NULL,classLoaderState='UNKNOWN',errorCode=NULL,sanitizedError=NULL WHERE packageName=:packageName AND state != 'STOPPED'") suspend fun markPackageStopped(packageName:String, now:Long, phase:String): Int
    @Query("UPDATE virtual_runtime_sessions SET state='STOPPED',launchPhase='STOPPED',stoppedAt=:now,lastActivityAt=:now,lastHeartbeatAt=:now WHERE state != 'STOPPED'") suspend fun stopAll(now:Long)
    @Query("UPDATE virtual_runtime_sessions SET state='STOPPED',launchPhase='STOPPED_BY_HOST',stoppedAt=:now,lastActivityAt=:now,lastHeartbeatAt=:now,errorCode=NULL,sanitizedError=NULL,lastRuntimeExitReason='STOPPED_BY_HOST',processExitDetectedBy='HOST_REMOVED_FROM_RECENTS' WHERE state != 'STOPPED'") suspend fun stopAllByHost(now:Long): Int
}
@Dao interface VirtualSharedPermissionDao { @Upsert suspend fun upsert(permission: VirtualSharedPermissionEntity); @Query("SELECT permission FROM virtual_shared_permissions WHERE packageName=:packageName AND virtualUserId=:userId") suspend fun permissions(packageName:String,userId:Int): List<String> }
@Dao interface VirtualFsAccessLogDao { @Insert suspend fun insert(row: VirtualFsAccessLogEntity) }
@Dao interface VirtualLaunchTokenDao { @Upsert suspend fun upsert(token: VirtualLaunchTokenEntity); @Query("SELECT * FROM virtual_launch_tokens WHERE token=:token AND consumedAt IS NULL AND expiresAt > :now") suspend fun getFresh(token:String,now:Long): VirtualLaunchTokenEntity?; @Query("UPDATE virtual_launch_tokens SET consumedAt=:time WHERE token=:token AND consumedAt IS NULL") suspend fun consume(token:String,time:Long): Int; @Query("UPDATE virtual_launch_tokens SET consumedAt=:time WHERE token=:token AND consumedAt IS NULL") suspend fun revoke(token:String,time:Long): Int; @Query("UPDATE virtual_launch_tokens SET consumedAt=:time WHERE sessionId=:sessionId AND launchAttemptId=:attemptId AND consumedAt IS NULL") suspend fun revokeAttempt(sessionId:String,attemptId:String,time:Long): Int; @Query("UPDATE virtual_launch_tokens SET consumedAt=:time WHERE consumedAt IS NULL") suspend fun revokeAll(time:Long): Int; @Query("DELETE FROM virtual_launch_tokens WHERE expiresAt < :now OR (consumedAt IS NOT NULL AND consumedAt < :consumedBefore)") suspend fun cleanup(now:Long,consumedBefore:Long): Int }
@Dao interface VirtualInstallSessionDao { @Upsert suspend fun upsert(session: VirtualInstallSessionEntity) }
@Dao interface VirtualComponentDao { @Upsert suspend fun upsertAll(components: List<VirtualComponentEntity>); @Query("SELECT * FROM virtual_components WHERE packageName=:packageName AND virtualUserId=:userId") suspend fun forPackage(packageName:String,userId:Int): List<VirtualComponentEntity> }
@Dao interface VirtualPermissionDao {
    @Upsert suspend fun upsertAll(permissions: List<VirtualPermissionEntity>)
    @Query("SELECT name FROM virtual_permissions WHERE packageName=:packageName AND virtualUserId=:userId ORDER BY name") suspend fun permissionNames(packageName:String,userId:Int): List<String>
}
@Dao interface CompatibilityIssueDao { @Upsert suspend fun upsertAll(issues: List<CompatibilityIssueEntity>); @Query("SELECT * FROM compatibility_issues WHERE packageName=:packageName AND virtualUserId=:userId ORDER BY severity, code") suspend fun forPackage(packageName:String,userId:Int): List<CompatibilityIssueEntity> }
@Dao interface VirtualStorageRecordDao { @Upsert suspend fun upsert(record: VirtualStorageRecordEntity) }

@Database(entities=[VirtualPackageEntity::class,VirtualComponentEntity::class,VirtualPermissionEntity::class,VirtualInstallSessionEntity::class,VirtualStorageRecordEntity::class,CompatibilityIssueEntity::class,VirtualRuntimeSessionEntity::class,VirtualMessageEntity::class,VirtualSharedPermissionEntity::class,VirtualFsAccessLogEntity::class,VirtualLaunchTokenEntity::class,RuntimeSlotEntity::class], version=15, exportSchema=false)
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
    abstract fun runtimeSlots(): RuntimeSlotDao
}


val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        fun add(sql: String) = runCatching { db.execSQL(sql) }
        add("ALTER TABLE virtual_packages ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
        add("ALTER TABLE virtual_packages ADD COLUMN importedApkPath TEXT NOT NULL DEFAULT ''")
        add("ALTER TABLE virtual_packages ADD COLUMN originalFileName TEXT NOT NULL DEFAULT ''")
        add("ALTER TABLE virtual_packages ADD COLUMN apkSizeBytes INTEGER NOT NULL DEFAULT 0")
        add("ALTER TABLE virtual_packages ADD COLUMN certificateSha256 TEXT")
        add("ALTER TABLE virtual_packages ADD COLUMN compileSdk INTEGER")
        add("ALTER TABLE virtual_packages ADD COLUMN supportedAbisJson TEXT NOT NULL DEFAULT '[]'")
        add("ALTER TABLE virtual_packages ADD COLUMN dexCount INTEGER NOT NULL DEFAULT 0")
        add("ALTER TABLE virtual_packages ADD COLUMN nativeLibraryCount INTEGER NOT NULL DEFAULT 0")
        add("ALTER TABLE virtual_packages ADD COLUMN hasNativeCode INTEGER NOT NULL DEFAULT 0")
        add("ALTER TABLE virtual_packages ADD COLUMN isSplitApk INTEGER NOT NULL DEFAULT 0")
        add("ALTER TABLE virtual_packages ADD COLUMN splitNamesJson TEXT NOT NULL DEFAULT '[]'")
        add("ALTER TABLE virtual_packages ADD COLUMN hasCustomApplication INTEGER NOT NULL DEFAULT 0")
        add("ALTER TABLE virtual_packages ADD COLUMN applicationClassName TEXT")
        add("ALTER TABLE virtual_packages ADD COLUMN launcherActivityName TEXT")
        add("ALTER TABLE virtual_packages ADD COLUMN declaredActivitiesJson TEXT NOT NULL DEFAULT '[]'")
        add("ALTER TABLE virtual_packages ADD COLUMN declaredServicesJson TEXT NOT NULL DEFAULT '[]'")
        add("ALTER TABLE virtual_packages ADD COLUMN declaredReceiversJson TEXT NOT NULL DEFAULT '[]'")
        add("ALTER TABLE virtual_packages ADD COLUMN declaredProvidersJson TEXT NOT NULL DEFAULT '[]'")
        add("ALTER TABLE virtual_packages ADD COLUMN declaredPermissionsJson TEXT NOT NULL DEFAULT '[]'")
        add("ALTER TABLE virtual_packages ADD COLUMN requestedPermissionsJson TEXT NOT NULL DEFAULT '[]'")
        add("ALTER TABLE virtual_packages ADD COLUMN compatibilityReasonsJson TEXT NOT NULL DEFAULT '[]'")
        add("ALTER TABLE virtual_packages ADD COLUMN importState TEXT NOT NULL DEFAULT 'READY'")
        add("ALTER TABLE virtual_packages ADD COLUMN importErrorCode TEXT")
        add("ALTER TABLE virtual_packages ADD COLUMN importErrorMessage TEXT")
        add("ALTER TABLE virtual_packages ADD COLUMN importedAt INTEGER NOT NULL DEFAULT 0")
        add("ALTER TABLE virtual_packages ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        add("ALTER TABLE virtual_packages ADD COLUMN lastVerifiedAt INTEGER")
        db.execSQL("UPDATE virtual_packages SET displayName=label WHERE displayName=''")
        db.execSQL("UPDATE virtual_packages SET importedApkPath=apkInternalPath WHERE importedApkPath=''")
        db.execSQL("UPDATE virtual_packages SET apkSizeBytes=CASE WHEN apkInternalPath IS NULL OR apkInternalPath='' THEN 0 ELSE apkSizeBytes END")
        db.execSQL("UPDATE virtual_packages SET hasNativeCode=CASE WHEN hasNativeLibraries THEN 1 ELSE 0 END")
        db.execSQL("UPDATE virtual_packages SET launcherActivityName=mainActivity WHERE launcherActivityName IS NULL")
        db.execSQL("UPDATE virtual_packages SET importedAt=installTime WHERE importedAt=0")
        db.execSQL("UPDATE virtual_packages SET updatedAt=updateTime WHERE updatedAt=0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_virtual_packages_packageName ON virtual_packages(packageName)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_virtual_packages_sha256 ON virtual_packages(sha256)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_virtual_packages_compatibilityLevel ON virtual_packages(compatibilityLevel)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_virtual_packages_importState ON virtual_packages(importState)")
    }
}


val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        fun add(sql: String) = runCatching { db.execSQL(sql) }
        add("ALTER TABLE virtual_packages ADD COLUMN cooperativeEntryPointClass TEXT")
        add("ALTER TABLE virtual_packages ADD COLUMN genericRuntimeCapability TEXT NOT NULL DEFAULT 'NONE'")
        add("ALTER TABLE virtual_packages ADD COLUMN blockingReasonsJson TEXT NOT NULL DEFAULT '[]'")
        add("ALTER TABLE virtual_packages ADD COLUMN importantIntentFiltersJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("UPDATE virtual_packages SET cooperativeEntryPointClass=entryPointClass WHERE cooperativeEntryPointClass IS NULL")
        db.execSQL("UPDATE virtual_packages SET runtimeMode=CASE WHEN entryPointClass IS NOT NULL THEN 'COOPERATIVE' ELSE 'INSPECTION_ONLY' END")
        db.execSQL("UPDATE virtual_packages SET genericRuntimeCapability=CASE WHEN entryPointClass IS NULL AND launcherActivityName IS NOT NULL AND isSplitApk=0 THEN 'PARSED_ONLY' ELSE genericRuntimeCapability END")
    }
}
