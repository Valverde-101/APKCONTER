package com.valcrono.virtualspace

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.valcrono.compat.Android15Diagnostics
import com.valcrono.core.VLog
import com.valcrono.vfm.VirtualFileManager
import com.valcrono.vproc.InMemoryLaunchTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var repository: VirtualRepository
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            importStatus = "Selección cancelada"
        } else {
            importUri(uri)
        }
    }
    private var pendingLaunchPackage by mutableStateOf<VirtualPackageEntity?>(null)
    private var importStatus by mutableStateOf("Listo para importar APKs")
    private var isImporting by mutableStateOf(false)
    private val permissionRequester = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val denied = grants.filterValues { granted -> !granted }.keys
        if (denied.isEmpty()) {
            pendingLaunchPackage?.let { launchVirtual(it) } ?: run {
                importStatus = "Permisos concedidos"
                VLog.i("UI", importStatus)
            }
        } else {
            val message = "Permisos denegados: ${denied.joinToString()}"
            importStatus = message
            VLog.e("UI", message)
        }
        pendingLaunchPackage = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = VirtualRepository(applicationContext)
        setContent { AppUi() }
    }

    @Composable private fun AppUi() {
        val scope = rememberCoroutineScope()
        var packages by remember { mutableStateOf<List<VirtualPackageEntity>>(emptyList()) }
        LaunchedEffect(Unit) { repository.packages().collectLatest { packages = it } }
        MaterialTheme {
            Column(Modifier.padding(16.dp)) {
                Text("Valcrono VirtualSpace", style = MaterialTheme.typography.headlineSmall)
                Row {
                    Button({
                        importStatus = "Selecciona un archivo .apk"
                        picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip", "*/*"))
                    }) { Text("Importar APK") }
                    Spacer(Modifier.width(8.dp))
                    Button({ requestHostPermissions() }) { Text("Pedir permisos") }
                    Spacer(Modifier.width(8.dp))
                    Button({ runDiagnostics() }) { Text("Diagnóstico") }
                }
                Text(importStatus)
                if (isImporting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                Text(Android15Diagnostics.summary())
                LazyColumn {
                    items(packages) { p ->
                        Card(Modifier.fillMaxWidth().padding(4.dp)) {
                            Column(Modifier.padding(8.dp)) {
                                Text("${p.label} (${p.packageName})")
                                Text("v${p.versionName} ${p.compatibilityLevel} damaged=${p.damaged}")
                                Row {
                                    Button({ openVirtual(p) }, enabled = p.enabled && !p.damaged && p.compatibilityLevel != "UNSUPPORTED") { Text("Abrir") }
                                    Button({ requestPermissionsForPackage(p) }, enabled = p.enabled && !p.damaged) { Text("Permisos") }
                                    Button({ installInAndroid(p) }, enabled = !p.damaged) { Text("Instalar en Android") }
                                    Button({ scope.launch { repository.storage().clearCache(p.virtualUserId, p.packageName) } }) { Text("Limpiar cache") }
                                    Button({ exportSimple(p) }) { Text("Exportar") }
                                    Button({ scope.launch { repository.db.packages().deletePackage(p.packageName, p.virtualUserId); repository.storage().resolver().packageRoot(p.virtualUserId, p.packageName).deleteRecursively() } }) { Text("Desinstalar") }
                                }
                                val entries = runCatching { VirtualFileManager(repository.storage().resolver()).list(p.virtualUserId, p.packageName).joinToString { it.name } }.getOrDefault("")
                                Text("Archivos: $entries")
                            }
                        }
                    }
                }
                Text("Logs")
                VLog.recent().takeLast(8).forEach { Text(it) }
            }
        }
    }

    private fun importUri(uri: Uri) {
        importStatus = "Copiando APK seleccionado..."
        isImporting = true
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)
        scope.launch {
            try {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val firstCopy = withContext(Dispatchers.IO) {
                    val out = File(cacheDir, "incoming-${System.currentTimeMillis()}.apk")
                    val input = contentResolver.openInputStream(uri) ?: error("No se pudo abrir el APK seleccionado")
                    input.use { source -> out.outputStream().use { target -> source.copyTo(target) } }
                    if (out.length() == 0L) error("El APK seleccionado está vacío")
                    out
                }
                importStatus = "Analizando APK..."
                val parsed = AndroidArchivePackageParser(this@MainActivity).parse(firstCopy)
                val namedCopy = File(cacheDir, "${parsed.packageName}-${System.currentTimeMillis()}.apk")
                firstCopy.copyTo(namedCopy, overwrite = true)
                firstCopy.delete()
                val imported = withContext(Dispatchers.IO) { repository.importCopiedApk(namedCopy, 0, parsed) }
                namedCopy.delete()
                val warning = if (imported.compatibilityLevel == "UNSUPPORTED") " con advertencias: no se puede abrir virtualmente en este dispositivo; usa Instalar en Android" else ""
                val message = "Importado ${parsed.label} (${parsed.packageName})$warning"
                importStatus = message
                VLog.i("UI", message)
            } catch (t: Throwable) {
                val message = "No se pudo importar el APK: ${t.message ?: t::class.java.simpleName}"
                importStatus = message
                VLog.e("UI", message, t)
            } finally {
                isImporting = false
            }
        }
    }

    private fun requestHostPermissions() {
        val missing = allRuntimePermissions().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            importStatus = "Todos los permisos compatibles ya están concedidos"
            VLog.i("UI", importStatus)
        } else {
            importStatus = "Solicitando ${missing.size} permisos..."
            permissionRequester.launch(missing.toTypedArray())
        }
    }

    private fun requestPermissionsForPackage(p: VirtualPackageEntity) {
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)
        scope.launch {
            val missing = withContext(Dispatchers.IO) { missingRuntimePermissions(p) }
            if (missing.isEmpty()) {
                importStatus = "${p.label} no tiene permisos pendientes"
                VLog.i("UI", importStatus)
            } else {
                pendingLaunchPackage = null
                importStatus = "Solicitando permisos para ${p.label}"
                permissionRequester.launch(missing.toTypedArray())
            }
        }
    }

    private fun runDiagnostics() {
        val missing = allRuntimePermissions().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        val summary = Android15Diagnostics.summary()
        importStatus = if (missing.isEmpty()) {
            "$summary | permisos OK"
        } else {
            "$summary | faltan ${missing.size} permisos; pulsa Pedir permisos"
        }
        VLog.i("UI", importStatus)
    }

    private fun openVirtual(p: VirtualPackageEntity) {
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)
        scope.launch {
            val missing = withContext(Dispatchers.IO) { missingRuntimePermissions(p) }
            if (missing.isNotEmpty()) {
                pendingLaunchPackage = p
                permissionRequester.launch(missing.toTypedArray())
            } else {
                launchVirtual(p)
            }
        }
    }

    private fun launchVirtual(p: VirtualPackageEntity) {
        val activity = p.mainActivity ?: p.entryPointClass ?: return
        val token = InMemoryLaunchTokens.create(p.virtualUserId, p.packageName, activity)
        startActivity(Intent(this, ProxyActivity::class.java).putExtra("virtualUserId", p.virtualUserId).putExtra("virtualPackageName", p.packageName).putExtra("virtualActivityName", activity).putExtra("launchToken", token))
    }

    private suspend fun missingRuntimePermissions(p: VirtualPackageEntity): List<String> {
        val requested = repository.db.permissions().permissionNames(p.packageName, p.virtualUserId)
        return requested
            .mapNotNull { normalizeRuntimePermission(it) }
            .distinct()
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun normalizeRuntimePermission(permission: String): String? = when {
        permission == android.Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33 -> null
        permission == android.Manifest.permission.READ_EXTERNAL_STORAGE && Build.VERSION.SDK_INT >= 33 -> null
        permission == android.Manifest.permission.WRITE_EXTERNAL_STORAGE && Build.VERSION.SDK_INT > 28 -> null
        permission in supportedRuntimePermissions -> permission
        else -> null
    }

    private fun allRuntimePermissions(): List<String> = supportedRuntimePermissions.mapNotNull { normalizeRuntimePermission(it) }.distinct()

    private val supportedRuntimePermissions = setOf(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.BODY_SENSORS,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.WRITE_CONTACTS,
        android.Manifest.permission.GET_ACCOUNTS,
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.WRITE_CALENDAR,
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.READ_CALL_LOG,
        android.Manifest.permission.WRITE_CALL_LOG,
        android.Manifest.permission.READ_SMS,
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.RECEIVE_SMS,
        android.Manifest.permission.READ_EXTERNAL_STORAGE,
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        android.Manifest.permission.READ_MEDIA_IMAGES,
        android.Manifest.permission.READ_MEDIA_VIDEO,
        android.Manifest.permission.READ_MEDIA_AUDIO,
    )

    private fun installInAndroid(p: VirtualPackageEntity) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            importStatus = "Activa permiso para instalar APKs y vuelve a tocar Instalar en Android"
            VLog.i("UI", importStatus)
            return
        }
        val apk = File(p.apkInternalPath)
        if (!apk.isFile) {
            importStatus = "No se encontró el APK importado para instalar en Android"
            VLog.e("UI", importStatus)
            return
        }
        importStatus = "Abriendo instalador de Android para ${p.label}..."
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun exportSimple(p: VirtualPackageEntity) {
        val root = repository.storage().resolver().packageRoot(p.virtualUserId, p.packageName)
        val out = File(filesDir, "exports/${p.packageName}-${System.currentTimeMillis()}").apply { mkdirs() }
        File(out, "metadata.json").writeText(
            """{"format":"ValcronoVirtualExport-1","package":${jsonString(p.packageName)}}""",
        )
        File(out, "package-info.json").writeText(p.toString())
        File(out, "checksums.json").writeText(
            VirtualFileManager(repository.storage().resolver()).checksums(root).entries.joinToString(prefix = "{", postfix = "}") {
                "${jsonString(it.key)}:${jsonString(it.value)}"
            },
        )
        VLog.i("Export", "Exported to ${out.absolutePath}")
    }

    private fun jsonString(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .let { "\"$it\"" }
}
