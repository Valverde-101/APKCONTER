package com.valcrono.virtualspace

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.graphics.BitmapFactory
import com.valcrono.core.VLog
import com.valcrono.virtualstorage.VirtualFileSystem
import com.valcrono.virtualstorage.VirtualFsAccessContext
import com.valcrono.virtualstorage.VirtualFsNamespace
import com.valcrono.virtualstorage.VirtualFsNodeType
import com.valcrono.virtualstorage.VirtualPathNotFileException
import com.valcrono.virtualstorage.VirtualPathNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

object VirtualFileSystemProvider {
    @Volatile private var instance: VirtualFileSystem? = null
    fun get(context: Context, packages: () -> List<String>): VirtualFileSystem = instance ?: synchronized(this) {
        instance ?: VirtualFileSystem(VirtualFsNamespace(context.applicationContext.filesDir, packages)).also { instance = it }
    }
}

data class CrashReportEntity(
    val crashId: String = UUID.randomUUID().toString(), val timestamp: Long = System.currentTimeMillis(), val appVersion: String,
    val buildCommit: String, val threadName: String, val exceptionClass: String, val sanitizedMessage: String?, val sanitizedStackTrace: String,
    val activeDestination: String?, val viewerType: String?, val virtualPath: String?, val fileSize: Long?, val availableMemory: Long,
    val javaHeapUsed: Long, val nativeHeapApprox: Long, val lastVfsOperation: String?
)

class CrashReportStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("viewer_crash_reports", Context.MODE_PRIVATE)
    fun save(report: CrashReportEntity) { prefs.edit().putString("last", listOf(report.crashId, report.timestamp, report.exceptionClass, report.sanitizedMessage, report.viewerType, report.virtualPath).joinToString("\n")).apply() }
    fun latest(): String? = prefs.getString("last", null)
    fun clear() { prefs.edit().clear().apply() }
}
class CrashReportRepository(private val store: CrashReportStore) { fun save(report: CrashReportEntity)=store.save(report); fun latest()=store.latest(); fun clear()=store.clear() }

enum class ViewerType { TEXT, JSON, XML, SQLITE, APK, IMAGE, HEX }
enum class ViewerErrorCode { PATH_NOT_FOUND, PATH_NOT_FILE, ACCESS_DENIED, ALIAS_TARGET_NOT_FOUND, ALIAS_LOOP_DETECTED, ALIAS_DEPTH_EXCEEDED, FILE_CHANGED_DURING_READ, FILE_TOO_LARGE, UNSUPPORTED_ENCODING, MALFORMED_JSON, MALFORMED_XML, SQLITE_SNAPSHOT_FAILED, SQLITE_OPEN_FAILED, SQLITE_LOCKED, SQLITE_CORRUPT, SQLITE_TABLE_NOT_FOUND, SQLITE_SCHEMA_READ_FAILED, SQLITE_QUERY_FAILED, SQLITE_COUNT_TIMEOUT, SQLITE_PAGE_OUT_OF_RANGE, SQLITE_BLOB_TOO_LARGE, SQLITE_SNAPSHOT_EXPIRED, APK_PARSE_FAILED, IMAGE_DECODE_FAILED, VFS_RESOLUTION_FAILED, OUT_OF_MEMORY_PREVENTED, READ_CANCELLED, UNKNOWN_VIEWER_ERROR }
sealed interface ViewerUiState { data object Loading: ViewerUiState; data class Ready<T>(val data:T): ViewerUiState; data class Error(val code: ViewerErrorCode, val userMessage:String, val technicalMessage:String?, val retryable:Boolean): ViewerUiState }
data class ViewerRequest(val viewerId:String, val virtualPath:String, val parentPath:String, val viewerType:ViewerType, val fileModifiedAt:Long=0L, val offset:Long=0L)
data class HexViewerData(val title:String, val virtualPath:String, val fileSize:Long, val offset:Long, val bytes:ByteArray, val rows:List<String>, val summary:String)
data class TextViewerData(val kind:String, val virtualPath:String, val fileSize:Long, val encoding:String, val lineCount:Int, val text:String, val warning:String?=null)
data class SQLiteDatabaseData(val virtualPath:String, val fileSize:Long, val userVersion:Int, val pageSize:Int, val journalMode:String?, val tables:List<SQLiteTableSummary>, val views:List<SQLiteViewSummary>, val indexes:List<SQLiteIndexSummary>, val triggers:List<String> = emptyList(), val snapshotId:String = "")
data class SQLiteViewSummary(val name:String, val createSql:String?)
data class SQLiteIndexSummary(val name:String, val tableName:String?, val unique:Boolean, val createSql:String?)
data class SQLiteTableSummary(val name:String, val createSql:String?, val estimatedRowCount:Long?, val columns:List<SQLiteColumnInfo>, val indexes:List<SQLiteIndexInfo>)
data class SQLiteColumnInfo(val cid:Int, val name:String, val declaredType:String, val notNull:Boolean, val defaultValue:String?, val primaryKeyPosition:Int, val hidden:Int = 0)
data class SQLiteIndexInfo(val name:String, val unique:Boolean, val origin:String?, val partial:Boolean, val columns:List<String>)
data class SQLitePageData(val tableName:String, val columns:List<String>, val rows:List<SQLiteRowData>, val offset:Int, val limit:Int, val totalRows:Long, val hasPrevious:Boolean, val hasNext:Boolean)
data class SQLiteRowData(val rowNumber:Long, val cells:List<SQLiteCellData>)
sealed interface SQLiteCellData { data object NullValue:SQLiteCellData; data class IntegerValue(val value:Long):SQLiteCellData; data class RealValue(val value:Double):SQLiteCellData; data class TextValue(val value:String, val truncated:Boolean):SQLiteCellData; data class BlobValue(val size:Int, val previewHex:String, val truncated:Boolean):SQLiteCellData }
sealed interface SQLiteDestination { data class DatabaseOverview(val virtualPath:String):SQLiteDestination; data class TableSchema(val virtualPath:String,val tableName:String):SQLiteDestination; data class TableRows(val virtualPath:String,val tableName:String,val offset:Int=0,val limit:Int=100):SQLiteDestination; data class RowDetail(val virtualPath:String,val tableName:String,val rowIndex:Long,val cells:List<SQLiteCellData>):SQLiteDestination }
sealed interface SQLiteViewerUiState { data object LoadingDatabase:SQLiteViewerUiState; data class DatabaseReady(val database:SQLiteDatabaseData):SQLiteViewerUiState; data class LoadingTable(val tableName:String):SQLiteViewerUiState; data class TableReady(val summary:SQLiteTableSummary,val page:SQLitePageData):SQLiteViewerUiState; data class Error(val code:ViewerErrorCode,val message:String,val retryable:Boolean):SQLiteViewerUiState }
typealias SQLiteViewerData = SQLiteDatabaseData
data class ApkViewerData(val virtualPath:String, val fileSize:Long, val sha256:String, val warning:String?=null)
data class ImageViewerData(val virtualPath:String, val fileSize:Long, val width:Int, val height:Int, val sampleSize:Int)

suspend inline fun <T> runViewerOperation(crossinline block: suspend () -> T): Result<T> = try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (t: Throwable) { Result.failure(t) }

class FileViewerRepository(private val context: Context, private val vfs: VirtualFileSystem) {
    private val admin = VirtualFsAccessContext.admin()
    suspend fun load(request: ViewerRequest): ViewerUiState = withContext(Dispatchers.IO) {
        VLog.i("Viewer", "VIEWER_REQUESTED ${request.viewerId} ${request.viewerType} ${request.virtualPath}")
        val result = runViewerOperation { when(request.viewerType) {
            ViewerType.TEXT -> withTimeout(5_000) { loadText(request, "Texto") }
            ViewerType.JSON -> withTimeout(5_000) { loadStructuredText(request, true) }
            ViewerType.XML -> withTimeout(5_000) { loadStructuredText(request, false) }
            ViewerType.HEX -> withTimeout(5_000) { loadHex(request, "Hex") }
            ViewerType.SQLITE -> withTimeout(15_000) { loadSqlite(request) }
            ViewerType.APK -> withTimeout(10_000) { loadApk(request) }
            ViewerType.IMAGE -> withTimeout(10_000) { loadImage(request) }
        } }
        result.fold(onSuccess={ ViewerUiState.Ready(it) }, onFailure={ errorState(it) })
    }
    private fun statFile(path:String)=vfs.stat(path, admin).also { if (it.type == VirtualFsNodeType.MISSING) throw VirtualPathNotFoundException(it.virtualPath); if (it.type != VirtualFsNodeType.FILE && it.type != VirtualFsNodeType.GENERATED) throw VirtualPathNotFileException(it.virtualPath) }
    private suspend fun loadHex(r:ViewerRequest,title:String):HexViewerData { val s=statFile(r.virtualPath); val len=minOf(4096, (s.size-r.offset).coerceAtLeast(0).toInt()); val b= if(len==0) ByteArray(0) else vfs.readRange(r.virtualPath, admin, r.offset, len); val rows=b.toList().chunked(16).mapIndexed{idx,row -> val off=r.offset+idx*16; val hex=row.joinToString(" "){"%02X".format(it.toInt() and 0xFF)}; val ascii=row.map{ val c=it.toInt() and 0xFF; if(c in 32..126) c.toChar() else '.'}.joinToString(""); "%08X  %-47s  |%s|".format(off,hex,ascii)}; return HexViewerData(title,r.virtualPath,s.size,r.offset,b,rows, if(s.size==0L) "Archivo vacío" else if(s.size>4096) "Mostrando los primeros 4 KB de ${formatSizeStatic(s.size)}" else "Mostrando ${b.size} bytes") }
    private suspend fun loadText(r:ViewerRequest,kind:String):TextViewerData { val s=statFile(r.virtualPath); val max= if(s.size<=2L*1024*1024) 2L*1024*1024 else 64L*1024; val b=vfs.readBytes(r.virtualPath, admin, max); val (enc,txt)=decodeText(b); return TextViewerData(kind,r.virtualPath,s.size,enc,txt.lines().size, if(s.size>max) txt+"\n\n[Archivo grande: se muestran los primeros ${formatSizeStatic(max)}]" else txt) }
    private suspend fun loadStructuredText(r:ViewerRequest,json:Boolean):TextViewerData { val d=loadText(r, if(json)"JSON" else "XML"); val warn=runCatching{ if(json){ val t=d.text.trim(); if(t.startsWith("[")) JSONArray(t) else JSONObject(t) } else { DocumentBuilderFactory.newInstance().apply{ setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); setFeature("http://xml.org/sax/features/external-general-entities", false); setFeature("http://xml.org/sax/features/external-parameter-entities", false); isExpandEntityReferences=false }.newDocumentBuilder().parse(d.text.byteInputStream()) } }.exceptionOrNull()?.message; return d.copy(warning=warn?.let{ if(json)"JSON mal formado: $it" else "XML mal formado: $it"}) }
    private suspend fun loadSqlite(r:ViewerRequest):SQLiteViewerData { val s=statFile(r.virtualPath); if(r.virtualPath.endsWith("-journal")||r.virtualPath.endsWith("-wal")||r.virtualPath.endsWith("-shm")) return SQLiteDatabaseData(r.virtualPath,s.size,0,0,null, emptyList(), emptyList(), emptyList(), listOf("Archivo auxiliar SQLite: abrir con Hex")); return SQLiteViewerRepository(context, vfs).openDatabase(r.virtualPath) }
    private suspend fun loadApk(r:ViewerRequest):ApkViewerData { val s=statFile(r.virtualPath); val sha=vfs.calculateSha256(r.virtualPath, admin); return ApkViewerData(r.virtualPath,s.size,sha, context.packageManager.getPackageArchiveInfo(vfs.resolve(r.virtualPath, admin).physicalFile?.path.orEmpty(),0)?.packageName?.let{"Paquete: $it"} ?: "APK_PARSE_FAILED: metadata no disponible") }
    private suspend fun loadImage(r:ViewerRequest):ImageViewerData { val s=statFile(r.virtualPath); val f=vfs.resolve(r.virtualPath, admin).physicalFile ?: error("VFS_RESOLUTION_FAILED"); val o=BitmapFactory.Options().apply{inJustDecodeBounds=true}; BitmapFactory.decodeFile(f.path,o); if(o.outWidth<=0||o.outHeight<=0) error("IMAGE_DECODE_FAILED"); return ImageViewerData(r.virtualPath,s.size,o.outWidth,o.outHeight,1) }
    private fun decodeText(b:ByteArray):Pair<String,String> { val bom = b.take(3).toByteArray(); val clean= if(bom.contentEquals(byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte()))) b.drop(3).toByteArray() else b; return runCatching { "UTF-8" to Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(clean)).toString() }.getOrElse { "ISO-8859-1" to String(clean, Charset.forName("ISO-8859-1")) } }
    private fun errorState(t:Throwable):ViewerUiState.Error { val msg=t.message.orEmpty(); val code= when { t.toViewerCode() != ViewerErrorCode.UNKNOWN_VIEWER_ERROR -> t.toViewerCode(); t is SQLiteException->ViewerErrorCode.SQLITE_OPEN_FAILED; t is OutOfMemoryError->ViewerErrorCode.OUT_OF_MEMORY_PREVENTED; msg.contains("IMAGE_DECODE_FAILED")->ViewerErrorCode.IMAGE_DECODE_FAILED; msg.contains("VFS_RESOLUTION_FAILED")->ViewerErrorCode.VFS_RESOLUTION_FAILED; else->ViewerErrorCode.UNKNOWN_VIEWER_ERROR }; VLog.e("Viewer", "VIEWER_STATE_ERROR code=$code exception=${t::class.java.name} message=${t.message}", t); return ViewerUiState.Error(code,"No se pudo abrir el archivo.",t::class.java.simpleName+": "+t.message,true) }
}

private fun formatSizeStatic(bytes:Long):String = when { bytes >= 1024L*1024L -> "%.1f MB".format(bytes/1024.0/1024.0); bytes >= 1024L -> "%.1f KB".format(bytes/1024.0); else -> "$bytes B" }
