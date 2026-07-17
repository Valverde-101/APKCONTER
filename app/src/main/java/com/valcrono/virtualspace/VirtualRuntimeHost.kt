package com.valcrono.virtualspace

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.valcrono.core.VLog
import com.valcrono.runtime.*
import com.valcrono.virtualstorage.VirtualPathResolver
import kotlinx.coroutines.runBlocking
import java.io.File

class VirtualRuntimeHost(private val context: Context, private val repository: VirtualRepository) {
    fun start(pkg: VirtualPackageEntity): RunningVirtualApp {
        val loader = AndroidVirtualApkClassLoaderFactory(context).create(pkg.apkInternalPath, pkg.sha256, pkg.packageName)
        val entryName = pkg.entryPointClass ?: error("ENTRY_POINT_NOT_DECLARED")
        val clazz = runCatching { loader.loadClass(entryName) }.getOrElse { throw IllegalStateException("ENTRY_POINT_CLASS_NOT_FOUND", it) }
        val instance = clazz.getDeclaredConstructor().newInstance()
        require(instance is VirtualAppEntryPoint) { "ENTRY_POINT_INTERFACE_MISMATCH" }
        val env = AndroidVirtualAppEnvironment(context, repository, pkg)
        runCatching { instance.onCreate(env); instance.onStart(); instance.onResume() }.getOrElse { throw IllegalStateException("RUNTIME_INITIALIZATION_FAILED", it) }
        return RunningVirtualApp(pkg, instance, env)
    }
}

data class RunningVirtualApp(val pkg: VirtualPackageEntity, val entry: VirtualAppEntryPoint, val environment: AndroidVirtualAppEnvironment)

class AndroidVirtualAppEnvironment(private val context: Context, private val repository: VirtualRepository, private val pkg: VirtualPackageEntity) : VirtualAppEnvironment {
    private val resolver = VirtualPathResolver(context.filesDir)
    override val metadata = VirtualAppMetadata(pkg.virtualUserId, pkg.packageName, pkg.label, pkg.versionName)
    override val logger = object : VirtualLogger { override fun info(message:String)= VLog.i(pkg.packageName,message); override fun error(message:String, throwable:Throwable?)=VLog.e(pkg.packageName,message,throwable) }
    override val files = object : VirtualFileApi {
        override fun writeText(relativePath: String, text: String) { val f=resolver.resolve(pkg.virtualUserId,pkg.packageName,"data/$relativePath"); f.parentFile?.mkdirs(); f.writeText(text) }
        override fun readText(relativePath: String): String? { val f=resolver.resolve(pkg.virtualUserId,pkg.packageName,"data/$relativePath"); return f.takeIf{it.isFile}?.readText() }
        override fun list(relativeDir: String): List<String> { val d=resolver.resolve(pkg.virtualUserId,pkg.packageName,"data/$relativeDir"); return d.listFiles()?.map{it.name}.orEmpty() }
    }
    override val preferences = object : VirtualPreferencesApi {
        private fun file(name:String)=resolver.resolve(pkg.virtualUserId,pkg.packageName,"data/shared_prefs/$name.properties")
        private fun load(name:String)=java.util.Properties().apply { file(name).takeIf{it.isFile}?.inputStream()?.use(::load) }
        private fun save(name:String,p:java.util.Properties){ val f=file(name); f.parentFile?.mkdirs(); f.outputStream().use{p.store(it,null)} }
        override fun putString(name:String,key:String,value:String){ val p=load(name); p.setProperty(key,value); save(name,p) }
        override fun getString(name:String,key:String,defaultValue:String?)=load(name).getProperty(key,defaultValue)
        override fun putInt(name:String,key:String,value:Int)=putString(name,key,value.toString())
        override fun getInt(name:String,key:String,defaultValue:Int)=getString(name,key,null)?.toIntOrNull() ?: defaultValue
    }
    override val database = object : VirtualDatabaseApi {
        private fun db(name:String): SQLiteDatabase { val f=resolver.resolve(pkg.virtualUserId,pkg.packageName,"data/databases/$name"); f.parentFile?.mkdirs(); return SQLiteDatabase.openOrCreateDatabase(f,null) }
        override fun execute(database:String, sql:String, args:List<String>){ db(database).use{ it.execSQL(sql,args.toTypedArray()) } }
        override fun query(database:String, sql:String, args:List<String>, limit:Int): List<Map<String,String?>> { db(database).use{ d-> d.rawQuery(sql,args.toTypedArray()).use{ c-> val out= mutableListOf<Map<String,String?>>(); while(c.moveToNext() && out.size<limit){ out += c.columnNames.associateWith{ c.getString(c.getColumnIndexOrThrow(it)) } }; return out } } }
        override fun listDatabases(): List<String> = resolver.resolve(pkg.virtualUserId,pkg.packageName,"data/databases").listFiles()?.map{it.name}.orEmpty()
    }
    override val messages = object : VirtualMessageApi {
        override fun send(toPackage:String,type:String,payload:String): Long { require(payload.length<=4096); return runBlocking { repository.db.messages().insert(VirtualMessageEntity(virtualUserId=pkg.virtualUserId, fromPackage=pkg.packageName, toPackage=toPackage, type=type, payload=payload, state="PENDING", createdAt=System.currentTimeMillis(), deliveredAt=null)) } }
        override fun pending(): List<VirtualMessage> = runBlocking { repository.db.messages().pendingFor(pkg.packageName,pkg.virtualUserId).map{ VirtualMessage(it.id,it.fromPackage,it.toPackage,it.type,it.payload,it.state) } }
        override fun markDelivered(messageId: Long) { runBlocking { repository.db.messages().markDelivered(messageId,System.currentTimeMillis()) } }
    }
}
