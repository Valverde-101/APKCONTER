package com.valcrono.virtualspace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let { importUri(it) } }

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
                    Button({ picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }) { Text("Importar APK") }
                    Spacer(Modifier.width(8.dp))
                    Button({ scope.launch { VLog.i("UI", Android15Diagnostics.summary()) } }) { Text("Diagnóstico") }
                }
                Text(Android15Diagnostics.summary())
                LazyColumn {
                    items(packages) { p ->
                        Card(Modifier.fillMaxWidth().padding(4.dp)) {
                            Column(Modifier.padding(8.dp)) {
                                Text("${p.label} (${p.packageName})")
                                Text("v${p.versionName} ${p.compatibilityLevel} damaged=${p.damaged}")
                                Row {
                                    Button({ openVirtual(p) }, enabled = p.enabled && !p.damaged) { Text("Abrir") }
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
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)
        scope.launch {
            try {
                val firstCopy = withContext(Dispatchers.IO) {
                    File(cacheDir, "incoming-${System.currentTimeMillis()}.apk").also { out -> contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } } }
                }
                val parsed = AndroidArchivePackageParser(this@MainActivity).parse(firstCopy)
                val namedCopy = File(cacheDir, "${parsed.packageName}.apk")
                firstCopy.copyTo(namedCopy, overwrite = true); firstCopy.delete()
                withContext(Dispatchers.IO) { repository.importCopiedApk(namedCopy, 0, parsed); namedCopy.delete() }
                VLog.i("UI", "Imported ${parsed.packageName} via AndroidArchivePackageParser")
            } catch (t: Throwable) { VLog.e("UI", "Import failed: ${t.message}", t) }
        }
    }

    private fun openVirtual(p: VirtualPackageEntity) {
        val activity = p.mainActivity ?: p.entryPointClass ?: return
        val token = InMemoryLaunchTokens.create(p.virtualUserId, p.packageName, activity)
        startActivity(Intent(this, ProxyActivity::class.java).putExtra("virtualUserId", p.virtualUserId).putExtra("virtualPackageName", p.packageName).putExtra("virtualActivityName", activity).putExtra("launchToken", token))
    }

    private fun exportSimple(p: VirtualPackageEntity) {
        val root = repository.storage().resolver().packageRoot(p.virtualUserId, p.packageName)
        val out = File(filesDir, "exports/${p.packageName}-${System.currentTimeMillis()}").apply { mkdirs() }
        File(out, "metadata.json").writeText("{\"format\":\"ValcronoVirtualExport-1\",\"package\":\"${p.packageName}\"}")
        File(out, "package-info.json").writeText(p.toString())
        File(out, "checksums.json").writeText(VirtualFileManager(repository.storage().resolver()).checksums(root).entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":\"${it.value}\"" })
        VLog.i("Export", "Exported to ${out.absolutePath}")
    }
}
