package com.valcrono.virtualspace

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "virtual_packages", primaryKeys = ["packageName", "virtualUserId"])
data class VirtualPackageEntity(val packageName:String,val label:String,val versionCode:Long,val versionName:String,val minSdk:Int,val targetSdk:Int,val sha256:String,val apkInternalPath:String,val installTime:Long,val updateTime:Long,val primaryAbi:String?,val hasNativeLibraries:Boolean,val mainActivity:String?,val entryPointClass:String?,val compatibilityLevel:String,val enabled:Boolean,val damaged:Boolean,val virtualUserId:Int,val virtualUid:Int)
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
@Entity(tableName = "virtual_runtime_sessions", primaryKeys = ["id"])
data class VirtualRuntimeSessionEntity(val id:String,val packageName:String,val virtualUserId:Int,val entryPointClass:String,val startedAt:Long,val state:String,val lastError:String?)
@Entity(tableName = "virtual_messages", indices = [Index("toPackage"), Index("fromPackage")])
data class VirtualMessageEntity(@PrimaryKey(autoGenerate = true) val id:Long = 0,val virtualUserId:Int,val fromPackage:String,val toPackage:String,val type:String,val payload:String,val state:String,val createdAt:Long,val deliveredAt:Long?)

@Dao interface VirtualPackageDao {
    @Upsert suspend fun upsertPackage(pkg: VirtualPackageEntity)
    @Query("SELECT * FROM virtual_packages ORDER BY label") fun observePackages(): Flow<List<VirtualPackageEntity>>
    @Query("SELECT * FROM virtual_packages WHERE packageName=:packageName AND virtualUserId=:userId") suspend fun getPackage(packageName:String,userId:Int): VirtualPackageEntity?
    @Query("UPDATE virtual_packages SET damaged=1 WHERE packageName=:packageName AND virtualUserId=:userId") suspend fun markDamaged(packageName:String,userId:Int)
    @Query("DELETE FROM virtual_packages WHERE packageName=:packageName AND virtualUserId=:userId") suspend fun deletePackage(packageName:String,userId:Int)
}
@Dao interface VirtualMessageDao {
    @Insert suspend fun insert(message: VirtualMessageEntity): Long
    @Query("SELECT * FROM virtual_messages WHERE toPackage=:toPkg AND virtualUserId=:userId AND state='PENDING' ORDER BY createdAt") suspend fun pendingFor(toPkg:String,userId:Int): List<VirtualMessageEntity>
    @Query("UPDATE virtual_messages SET state='DELIVERED', deliveredAt=:time WHERE id=:id") suspend fun markDelivered(id:Long,time:Long)
}
@Dao interface VirtualRuntimeDao { @Upsert suspend fun upsert(session: VirtualRuntimeSessionEntity); @Query("UPDATE virtual_runtime_sessions SET state=:state,lastError=:error WHERE id=:id") suspend fun update(id:String,state:String,error:String?) }
@Dao interface VirtualInstallSessionDao { @Upsert suspend fun upsert(session: VirtualInstallSessionEntity) }
@Dao interface VirtualComponentDao { @Upsert suspend fun upsertAll(components: List<VirtualComponentEntity>) }
@Dao interface VirtualPermissionDao {
    @Upsert suspend fun upsertAll(permissions: List<VirtualPermissionEntity>)
    @Query("SELECT name FROM virtual_permissions WHERE packageName=:packageName AND virtualUserId=:userId ORDER BY name") suspend fun permissionNames(packageName:String,userId:Int): List<String>
}
@Dao interface CompatibilityIssueDao { @Upsert suspend fun upsertAll(issues: List<CompatibilityIssueEntity>) }
@Dao interface VirtualStorageRecordDao { @Upsert suspend fun upsert(record: VirtualStorageRecordEntity) }

@Database(entities=[VirtualPackageEntity::class,VirtualComponentEntity::class,VirtualPermissionEntity::class,VirtualInstallSessionEntity::class,VirtualStorageRecordEntity::class,CompatibilityIssueEntity::class,VirtualRuntimeSessionEntity::class,VirtualMessageEntity::class], version=2, exportSchema=false)
abstract class ValcronoDatabase: RoomDatabase() {
    abstract fun packages(): VirtualPackageDao
    abstract fun messages(): VirtualMessageDao
    abstract fun runtime(): VirtualRuntimeDao
    abstract fun installSessions(): VirtualInstallSessionDao
    abstract fun components(): VirtualComponentDao
    abstract fun permissions(): VirtualPermissionDao
    abstract fun issues(): CompatibilityIssueDao
    abstract fun storageRecords(): VirtualStorageRecordDao
}
