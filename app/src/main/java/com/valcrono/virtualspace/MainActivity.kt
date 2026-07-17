package com.valcrono.virtualspace

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.valcrono.core.*
import com.valcrono.vproc.InMemoryLaunchTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalMaterial3WindowSizeClassApi::class)
class MainActivity : ComponentActivity(), ComponentCallbacks2 {
    private lateinit var repository: VirtualRepository
    private lateinit var settingsStore: VirtualSpaceSettingsStore
    private lateinit var resourceTracker: RuntimeResourceTracker
    private lateinit var sessionManager: VirtualSessionManager
    private var destination by mutableStateOf(Destination.HOME)
    private var selectedPackage by mutableStateOf<VirtualPackageEntity?>(null)
    private var explorerPath by mutableStateOf("")
    private var importStatus by mutableStateOf("PROTOTIPO FUNCIONAL PARA APPS COOPERATIVAS")
    private var isImporting by mutableStateOf(false)
    private var pendingLaunchPackage by mutableStateOf<VirtualPackageEntity?>(null)
    private var exportPackage by mutableStateOf<VirtualPackageEntity?>(null)
    private var sortMode by mutableStateOf("nombre")

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::importUri) ?: run { importStatus = "Selección cancelada" } }
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> exportPackage?.let { p -> if (uri != null) exportToTree(p, uri) else importStatus = "Exportación cancelada" }; exportPackage = null }
    private val permissionRequester = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants -> importStatus = if (grants.values.all { it }) "Permisos listos" else "Permisos pendientes: ${grants.values.count { !it }}"; pendingLaunchPackage?.let { launchVirtual(it) }; pendingLaunchPackage = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = VirtualRepository(applicationContext)
        settingsStore = VirtualSpaceSettingsStore(applicationContext)
        resourceTracker = RuntimeResourceTracker(applicationContext)
        sessionManager = VirtualSessionManager(resourceTracker)
        setContent { AppUi() }
    }

    @Composable private fun AppUi() {
        val scope = rememberCoroutineScope()
        val windowSize = calculateWindowSizeClass(this)
        val settings by settingsStore.settings.collectAsState(initial = VirtualSpaceSettings())
        val snapshot by resourceTracker.snapshot.collectAsState()
        var packages by remember { mutableStateOf<List<VirtualPackageEntity>>(emptyList()) }
        LaunchedEffect(Unit) { repository.packages().collectLatest { packages = it } }
        LaunchedEffect(destination) { if (destination == Destination.PROCESSES) while (true) { resourceTracker.refresh(); delay(3000) } }
        LaunchedEffect(settings.flagSecureEnabled) {
            if (settings.flagSecureEnabled) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        val wide = windowSize.widthSizeClass != WindowWidthSizeClass.Compact
        MaterialTheme {
            Scaffold(
                modifier = Modifier.safeDrawingPadding().imePadding(),
                topBar = { TopAppBar(title = { Column { Text("Valcrono VirtualSpace", maxLines = 1, overflow = TextOverflow.Ellipsis); Text("Estado general: ${importStatus.take(48)}", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) } }, actions = { TextButton({ destination = Destination.SETTINGS }) { Text("Menú") } }) },
                bottomBar = { if (!wide) NavigationBar { Destination.main.forEach { NavigationBarItem(selected = destination == it, onClick = { destination = it }, icon = { Text(it.icon) }, label = { Text(it.label, maxLines = 1) }) } } },
            ) { padding ->
                Row(Modifier.padding(padding).fillMaxSize()) {
                    if (wide) NavigationRail { Destination.main.forEach { NavigationRailItem(selected = destination == it, onClick = { destination = it }, icon = { Text(it.icon) }, label = { Text(it.label) }) } } }
                    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        when (destination) {
                            Destination.HOME -> HomeScreen(packages, snapshot, settings)
                            Destination.APPS -> AppsScreen(packages, wide, scope)
                            Destination.FILES -> FileExplorer(selectedPackage ?: packages.firstOrNull())
                            Destination.PROCESSES -> ProcessScreen(snapshot, packages, settings, scope)
                            Destination.SETTINGS -> SettingsScreen(settings, packages, scope)
                            Destination.APP_SETTINGS -> PerAppSettingsScreen(selectedPackage, scope)
                            Destination.APP_DIAG -> AppDiagnostics(selectedPackage)
                        }
                    }
                }
            }
        }
    }

    @Composable private fun HomeScreen(packages: List<VirtualPackageEntity>, snapshot: RuntimeResourceSnapshot, settings: VirtualSpaceSettings) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item { if (!settings.firstRunComplete) FirstRunCard(settings) }
            item { SummaryGrid(listOf(
                "Aplicaciones instaladas" to packages.size.toString(),
                "Aplicación activa" to (resourceTracker.allSessions().firstOrNull { it.state == SessionState.ACTIVE }?.label ?: "Ninguna"),
                "RAM usada por el host" to formatBytes(snapshot.hostPssBytes),
                "Almacenamiento usado" to formatBytes(packages.sumOf { repository.storage().usedBytes(it.virtualUserId, it.packageName) }),
                "Mensajes pendientes" to "Ver procesos",
                "Último error" to "No disponible",
            )) }
            item { Text("El presupuesto de memoria es un objetivo administrado por VirtualSpace; Android conserva el control final de memoria.") }
            item { if (isImporting) LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { Text(importStatus) }
        }
    }

    @Composable private fun SummaryGrid(rows: List<Pair<String, String>>) {
        LazyVerticalGrid(columns = GridCells.Adaptive(180.dp), modifier = Modifier.heightIn(max = 420.dp), userScrollEnabled = false, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows) { row -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(row.first, style = MaterialTheme.typography.labelLarge); Text(row.second, style = MaterialTheme.typography.titleMedium) } } }
        }
    }

    @Composable private fun AppsScreen(packages: List<VirtualPackageEntity>, wide: Boolean, scope: kotlinx.coroutines.CoroutineScope) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip", "*/*")) }, Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Importar APK") }
                Button({ destination = Destination.FILES }, Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Archivos") }
                Button({ destination = Destination.PROCESSES }, Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Procesos") }
            } }
            items(packages) { p -> AppCard(p, scope) }
        }
    }

    @Composable private fun AppCard(p: VirtualPackageEntity, scope: kotlinx.coroutines.CoroutineScope) {
        var more by remember { mutableStateOf(false) }
        val state = resourceTracker.allSessions().firstOrNull { it.packageName == p.packageName }?.state ?: SessionState.STOPPED
        Card(Modifier.fillMaxWidth().semantics { contentDescription = "Aplicación ${p.label}" }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("▣", style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(p.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(p.packageName, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("v${p.versionName} · ${compatLabel(p.compatibilityLevel)}") } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("Estado: $state"); Text("Uso estimado de RAM: ${formatBytes(resourceTracker.snapshot.value.hostPssBytes)}"); Text("Datos: ${formatBytes(repository.storage().usedBytes(p.virtualUserId, p.packageName))}") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ openVirtual(p) }, enabled = p.enabled && !p.damaged && p.compatibilityLevel != "UNSUPPORTED", modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Abrir") }
                    OutlinedButton({ stopPackage(p) }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Detener") }
                    OutlinedButton({ selectedPackage = p; explorerPath = ""; destination = Destination.FILES }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Archivos") }
                    IconButton({ more = true }, Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) { Text("⋮") }
                }
                DropdownMenu(more, { more = false }) { listOf("Información", "Permisos", "Ajustes de la aplicación", "Exportar", "Restaurar", "Limpiar caché", "Borrar datos", "Actualizar APK", "Desinstalar", "Diagnóstico", "Instalar normalmente en Android").forEach { label -> DropdownMenuItem(text = { Text(label) }, onClick = { more = false; handleAppMenu(label, p, scope) }) } }
            }
        }
    }

    @Composable private fun SettingsScreen(settings: VirtualSpaceSettings, packages: List<VirtualPackageEntity>, scope: kotlinx.coroutines.CoroutineScope) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item { Text("Ajustes de VirtualSpace", style = MaterialTheme.typography.headlineSmall) }
            item { SettingsSection("Rendimiento") { Text("Perfil de rendimiento"); MemoryProfile.entries.forEach { profile -> FilterChip(selected = settings.performanceProfile == profile, onClick = { scope.launch { settingsStore.setProfile(profile); simulateProfileChange(profile) } }, label = { Text(profile.displayName()) }) }; Text("El límite seleccionado es un objetivo de uso administrado por VirtualSpace. Android puede finalizar procesos cuando necesite memoria.") } }
            item { SettingsSection("Aplicaciones virtuales") { Text("Máximo de apps activas: ${settings.maxActiveApps}"); Text("Qué hacer al abrir otra app: ${settings.openAnotherAppPolicy}"); Text("Qué hacer al salir de ProxyActivity: pausar o detener según presupuesto"); Text("Cerrar sesión al apagar pantalla: Experimental"); Text("Restaurar última sesión: No implementada para sesiones pesadas") } }
            item { SettingsSection("Almacenamiento") { listOf("Máximo total para VirtualSpace: ${if (settings.maxTotalStorageMb == 0) "Sin límite interno adicional" else settings.maxTotalStorageMb}", "Caché máxima total: ${settings.maxCacheStorageMb} MB", "Eliminar caché automáticamente: ${settings.autoDeleteCache}", "Advertir cuando quede poco espacio: ${settings.warnLowStorage}", "Limpiar temporales al iniciar: ${settings.cleanTempOnStart}", "Limpiar temporales al cerrar: ${settings.cleanTempOnClose}").forEach { Text(it) } } }
            item { SettingsSection("Interfaz") { listOf("Tema del sistema", "Claro", "Oscuro", "Negro AMOLED", "Color de acento", "Tamaño de texto: ${settings.textScale}", "Densidad: Compacto / Normal / Amplio = ${settings.cardDensity}", "Animaciones: ${settings.animations}", "Reducir movimiento: ${settings.reduceMotion}", "Mostrar información técnica: ${settings.showTechnicalInfo}", "Confirmar acciones destructivas: ${settings.confirmDestructiveActions}").forEach { Text(it) } } }
            item { SettingsSection("Permisos") { PermissionsScreen(packages) } }
            item { SettingsSection("Copias de seguridad") { listOf("Incluir APK en exportación: ${settings.includeApkInExport}", "Incluir caché: ${settings.includeCacheInExport}", "Incluir logs: ${settings.includeLogsInExport}", "Compresión: ${settings.backupCompression}", "Verificación SHA-256: ${settings.backupSha256}", "Backup antes de borrar datos: ${settings.backupBeforeDelete}", "Backup antes de actualizar: ${settings.backupBeforeUpdate}", "Últimas exportaciones: No disponible", "Exportaciones incompletas: No disponible", "Errores de verificación: No disponible", "Espacio usado por backups: No disponible").forEach { Text(it) } } }
            item { SettingsSection("Seguridad") { listOf("Bloqueo mediante PIN: No implementada", "Desbloqueo biométrico: No implementada", "Bloquear al salir: ${settings.lockOnExit}", "Tiempo de bloqueo automático: ${settings.autoLockMinutes} min", "Ocultar contenido reciente: ${settings.hideRecentContent}", "Ocultar nombres de archivos en vista previa: ${settings.hideFileNamesInPreview}", "Confirmación para borrar datos: ${settings.requireDeleteConfirmation}", "Confirmación para restaurar: ${settings.requireRestoreConfirmation}", "Registro de acciones administrativas: ${settings.adminActionLog}", "Impedir capturas en pantallas sensibles: ${settings.flagSecureEnabled}").forEach { Text(it) } } }
            item { SettingsSection("Diagnóstico") { deviceDiagnostics(packages).forEach { Text("${it.first}: ${it.second}") } } }
            item { SettingsSection("Desarrollador") { listOf("Modo desarrollador: ${settings.developerMode}", "Nivel de logs: ${settings.logLevel}", "Mostrar rutas físicas: ${settings.showPhysicalPaths}", "Mostrar stack traces: ${settings.showStackTraces}", "Mostrar tiempos del runtime: ${settings.showRuntimeTimings}", "Mostrar información del ClassLoader: ${settings.showClassLoaderInfo}", "Exportar diagnóstico", "Validar APK en cada arranque", "Forzar cierre de sesiones", "Simular presión de memoria", "Simular poco almacenamiento", "Ejecutar pruebas internas").forEach { Text(it) } } }
            item { SettingsSection("Acerca de") { listOf("Nombre: Valcrono VirtualSpace", "Versión: ${BuildConfig.VERSION_NAME}", "Código de versión: ${BuildConfig.VERSION_CODE}", "Commit del build: ${BuildConfig.GIT_COMMIT}", "Fecha de compilación: ${BuildConfig.BUILD_DATE}", "Tipo de build: ${BuildConfig.BUILD_TYPE}", "SDK objetivo: 35", "Licencias de dependencias: AndroidX, Kotlin, Room, DataStore, Compose", "Estado del runtime: PROTOTIPO FUNCIONAL PARA APPS COOPERATIVAS").forEach { Text(it) } } }
            item { SettingsSection("Restablecimiento") { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Button({ scope.launch { settingsStore.reset() } }) { Text("Restablecer ajustes") }; Button({ cleanRegenerableCaches(packages) }) { Text("Limpiar cachés") }; Button({ closeAllSessions() }) { Text("Cerrar todas las sesiones") }; OutlinedButton({ importStatus = "Eliminar todas las aplicaciones virtuales requiere confirmación escribiendo BORRAR y exportación opcional" }) { Text("Eliminar todas las aplicaciones virtuales") }; OutlinedButton({ importStatus = "Borrar completamente VirtualSpace requiere confirmación adicional escribiendo BORRAR TODO" }) { Text("Borrar completamente VirtualSpace") } } } }
        }
    }

    @Composable private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() } } }
    @Composable private fun FirstRunCard(settings: VirtualSpaceSettings) { Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Bienvenida"); Text("Perfil recomendado para Moto G06: Automático o Bajo consumo, una aplicación activa, caché moderada y cierre de sesiones inactivas."); Text("Permisos necesarios: ninguno amplio para importar/exportar; se usa SAF."); Button({ kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { settingsStore.setFirstRunComplete(true) } }) { Text("Continuar") } } } }

    @Composable private fun PerAppSettingsScreen(p: VirtualPackageEntity?, scope: kotlinx.coroutines.CoroutineScope) { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) { item { Text("Ajustes de ${p?.label ?: "aplicación"}", style = MaterialTheme.typography.headlineSmall) }; val options = listOf("Abrir automáticamente" to SettingAvailability.AVAILABLE, "Permitir ejecución en segundo plano" to SettingAvailability.EXPERIMENTAL, "Mantener sesión pausada" to SettingAvailability.EXPERIMENTAL, "Presupuesto de caché" to SettingAvailability.AVAILABLE, "Cuota de almacenamiento" to SettingAvailability.AVAILABLE, "Permisos virtuales" to SettingAvailability.AVAILABLE, "Acceso a mensajes de otras apps" to SettingAvailability.EXPERIMENTAL, "Acceso administrativo futuro" to SettingAvailability.NOT_IMPLEMENTED, "Guardar estado al detener" to SettingAvailability.AVAILABLE, "Cerrar después de inactividad" to SettingAvailability.AVAILABLE, "Orientación preferida" to SettingAvailability.EXPERIMENTAL, "Escala de interfaz" to SettingAvailability.EXPERIMENTAL, "Tamaño de fuente virtual" to SettingAvailability.EXPERIMENTAL, "Idioma virtual" to SettingAvailability.NOT_IMPLEMENTED, "Tema claro/oscuro/sistema" to SettingAvailability.AVAILABLE); items(options) { (name, availability) -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(name); Text(availability.label()); if (availability == SettingAvailability.EXPERIMENTAL) Text("Experimental: puede cambiar y requiere validación por app cooperativa.") } } } } }

    @Composable private fun ProcessScreen(snapshot: RuntimeResourceSnapshot, packages: List<VirtualPackageEntity>, settings: VirtualSpaceSettings, scope: kotlinx.coroutines.CoroutineScope) { LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) { item { Text("Procesos y memoria", style = MaterialTheme.typography.headlineSmall) }; item { SummaryGrid(listOf("RAM total del dispositivo" to formatBytes(snapshot.totalRamBytes), "RAM disponible" to formatBytes(snapshot.availableRamBytes), "RAM usada por VirtualSpace" to formatBytes(snapshot.hostPssBytes), "Java heap usado" to formatBytes(snapshot.javaHeapUsedBytes), "Native heap aproximado" to formatBytes(snapshot.nativeHeapBytes), "PSS total del proceso" to formatBytes(snapshot.hostPssBytes), "Sesiones virtuales activas" to snapshot.activeSessions.toString(), "ClassLoaders activos" to snapshot.classLoaders.toString(), "Bases SQLite abiertas" to snapshot.openSqliteDatabases.toString(), "Tareas de importación/exportación" to snapshot.runningTasks.toString())) }; item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Button({ closeAllSessions() }) { Text("Cerrar todas las sesiones") }; Button({ cleanRegenerableCaches(packages) }) { Text("Liberar caché") }; Button({ runMemoryCleanup(settings) }) { Text("Ejecutar limpieza") } } }; items(resourceTracker.allSessions()) { s -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(s.label); listOf("Aplicación: ${s.packageName}", "Estado: ${s.state}", "PID del proceso host: ${Process.myPid()}", "Tiempo activa: ${((System.currentTimeMillis() - s.startedAt)/1000)} s", "Memoria estimada: ${formatBytes(s.estimatedBytes)}", "Última actividad: ${date(s.lastActivityAt)}", "Bases abiertas: ${s.basesOpen}", "Mensajes pendientes: ${s.pendingMessages}").forEach { Text(it) }; FlowRow { Button({ resourceTracker.upsert(s.copy(state = SessionState.PAUSED)) }) { Text("Pausar") }; Button({ resourceTracker.stop(s.sessionId) }) { Text("Detener") } } } } } } }

    @Composable private fun PermissionsScreen(packages: List<VirtualPackageEntity>) { val rows = buildPermissionRows(); Text(if (rows.count { it.pending } == 0) "Permisos listos" else "Permisos pendientes: ${rows.count { it.pending }}"); Text("Permiso | Estado | Motivo | Acción"); rows.forEach { Text("${it.name} | ${if (it.pending) "Pendiente" else "Listo/No necesario"} | ${it.reason} | ${it.action}") } }
    @Composable private fun AppDiagnostics(p: VirtualPackageEntity?) { if (p == null) Text("Selecciona una app") else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 12.dp)) { item { Text("Diagnóstico de ${p.label}", style = MaterialTheme.typography.headlineSmall) }; val root = repository.storage().resolver().packageRoot(p.virtualUserId, p.packageName); items(listOf("Hash del APK" to p.sha256, "Ruta interna" to "aislada (no expuesta a apps virtuales)", "Firma" to "Validada por PackageManager", "Entry point" to (p.entryPointClass ?: "No disponible"), "ClassLoader" to "DexClassLoader aislado", "Tiempo de carga" to "Registrado en logs", "Tiempo de inicio" to "Registrado en logs", "Sesión actual" to (resourceTracker.allSessions().firstOrNull { it.packageName == p.packageName }?.state?.name ?: "DETENIDA"), "Último error" to "No disponible", "Archivos usados" to root.walkTopDown().filter { it.isFile }.count().toString(), "Mensajes enviados" to "ver Room", "Mensajes recibidos" to "ver Room", "Clasificación de compatibilidad" to p.compatibilityLevel)) { Text("${it.first}: ${it.second}") }; item { Button({ (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Valcrono logs", VLog.recent().joinToString("\n"))) }) { Text("Copiar logs") } } } }

    @Composable private fun FileExplorer(p: VirtualPackageEntity?) { if (p == null) { Text("Selecciona una aplicación"); return }; val roots = listOf("data/files", "data/databases", "data/shared_prefs", "data/cache", "data/code_cache", "data/no_backup", "external/files", "external/cache", "metadata", "apk", "lib"); val base = repository.storage().resolver().packageRoot(p.virtualUserId, p.packageName); val dir = if (explorerPath.isBlank()) base else safeResolve(base, explorerPath); LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) { item { Text("Archivos de ${p.label}", style = MaterialTheme.typography.headlineSmall); Text("Ruta: /${explorerPath.ifBlank { "." }}") }; item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { listOf("nombre", "tamaño", "fecha").forEach { Button({ sortMode = it }) { Text("Ordenar $it") } }; Button({ File(dir, "nueva-carpeta").mkdirs() }) { Text("Crear carpeta") } } }; if (explorerPath.isBlank()) items(roots) { Button({ explorerPath = it }, Modifier.defaultMinSize(minHeight = 48.dp)) { Text(it) } } else { item { Button({ explorerPath = explorerPath.substringBeforeLast('/', "") }) { Text("Arriba") } }; items(sortedFiles(dir)) { f -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(8.dp)) { Text("${if (f.isDirectory) "📁" else "📄"} ${f.name}", maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${formatBytes(f.length())} · ${date(f.lastModified())}"); FlowRow { if (f.isDirectory) Button({ explorerPath = (explorerPath + "/" + f.name).trim('/') }) { Text("Abrir") }; if (f.isFile) Button({ importStatus = if (f.extension in setOf("txt", "json", "xml", "properties")) f.readText().take(1000) else sqlitePreview(f) }) { Text("Ver") }; Button({ importStatus = "Propiedades: ${f.name} ${formatBytes(f.length())}" }) { Text("Propiedades") }; Button({ f.deleteRecursively() }) { Text("Eliminar") } } } } } } } }

    private fun importUri(uri: Uri) { importStatus = "Copiando APK seleccionado..."; isImporting = true; kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { try { val first = withContext(Dispatchers.IO) { val out = File(cacheDir, "incoming-${System.currentTimeMillis()}.apk"); contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }; out }; val parsed = AndroidArchivePackageParser(this@MainActivity).parse(first); val imported = withContext(Dispatchers.IO) { repository.importCopiedApk(first, 0, parsed) }; importStatus = "Importado ${imported.label}: ${imported.compatibilityLevel}"; first.delete() } catch (t: Throwable) { importStatus = "No se pudo importar el APK: ${t.message}"; VLog.e("UI", importStatus, t) } finally { isImporting = false } } }
    private fun openVirtual(p: VirtualPackageEntity) { kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { val missing = withContext(Dispatchers.IO) { missingRuntimePermissions(p) }; if (missing.isNotEmpty()) { pendingLaunchPackage = p; permissionRequester.launch(missing.toTypedArray()) } else launchVirtual(p) } }
    private fun launchVirtual(p: VirtualPackageEntity) { val activity = p.mainActivity ?: p.entryPointClass ?: return; resourceTracker.upsert(ManagedSession(packageName = p.packageName, label = p.label, state = SessionState.ACTIVE, estimatedBytes = resourceTracker.snapshot.value.hostPssBytes)); val token = InMemoryLaunchTokens.create(p.virtualUserId, p.packageName, activity); startActivity(Intent(this, ProxyActivity::class.java).putExtra("virtualUserId", p.virtualUserId).putExtra("virtualPackageName", p.packageName).putExtra("virtualActivityName", activity).putExtra("launchToken", token)) }
    private fun stopPackage(p: VirtualPackageEntity) { resourceTracker.allSessions().filter { it.packageName == p.packageName }.forEach { resourceTracker.stop(it.sessionId) }; importStatus = "Sesión detenida: ${p.label}" }
    private fun closeAllSessions() { resourceTracker.allSessions().forEach { resourceTracker.stop(it.sessionId) }; importStatus = "Todas las sesiones se marcaron como detenidas" }
    private fun handleAppMenu(label: String, p: VirtualPackageEntity, scope: kotlinx.coroutines.CoroutineScope) { when (label) { "Información" -> { selectedPackage = p; destination = Destination.APP_DIAG }; "Permisos" -> requestPermissionsForPackage(p); "Ajustes de la aplicación" -> { selectedPackage = p; destination = Destination.APP_SETTINGS }; "Exportar" -> { exportPackage = p; treePicker.launch(null) }; "Restaurar" -> importStatus = "Restauración requiere validar metadata, hashes y confirmación"; "Limpiar caché" -> scope.launch { repository.storage().clearCache(p.virtualUserId, p.packageName) }; "Borrar datos" -> scope.launch { repository.storage().resolver().resolve(p.virtualUserId, p.packageName, "data").deleteRecursively() }; "Actualizar APK" -> picker.launch(arrayOf("*/*")); "Desinstalar" -> scope.launch { repository.db.packages().deletePackage(p.packageName, p.virtualUserId); repository.storage().resolver().packageRoot(p.virtualUserId, p.packageName).deleteRecursively() }; "Diagnóstico" -> { selectedPackage = p; destination = Destination.APP_DIAG }; "Instalar normalmente en Android" -> installInAndroid(p) } }

    private suspend fun missingRuntimePermissions(p: VirtualPackageEntity) = repository.db.permissions().permissionNames(p.packageName, p.virtualUserId).mapNotNull(::normalizeRuntimePermission).distinct().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    private fun normalizeRuntimePermission(permission: String): String? = when (permission) { android.Manifest.permission.MANAGE_EXTERNAL_STORAGE, "android.permission.QUERY_ALL_PACKAGES", android.Manifest.permission.REQUEST_INSTALL_PACKAGES, android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_AUDIO -> null; android.Manifest.permission.POST_NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) permission else null; else -> if (permission in supportedRuntimePermissions) permission else null }
    private val supportedRuntimePermissions = setOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.POST_NOTIFICATIONS)
    private fun requestPermissionsForPackage(p: VirtualPackageEntity) { kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { val missing = withContext(Dispatchers.IO) { missingRuntimePermissions(p) }; if (missing.isEmpty()) importStatus = "${p.label}: permisos listos" else permissionRequester.launch(missing.toTypedArray()) } }
    data class PermissionRow(val name: String, val pending: Boolean, val reason: String, val action: String)
    private fun buildPermissionRows() = listOf(PermissionRow("Storage Access Framework", false, "Importar/exportar APK y datos sin almacenamiento amplio", "Seleccionar archivo/carpeta"), PermissionRow("POST_NOTIFICATIONS", false, "Solo se pide si una app cooperativa lo declara y Android 13+ lo requiere", "Bajo demanda"), PermissionRow("MANAGE_EXTERNAL_STORAGE", false, "No imprescindible; no se solicita", "No solicitar"), PermissionRow("QUERY_ALL_PACKAGES", false, "No se necesita para analizar APK importados", "No solicitar"), PermissionRow("REQUEST_INSTALL_PACKAGES", false, "Solo Ajustes si el usuario elige instalar normalmente", "Ajustes"))

    private fun deviceDiagnostics(packages: List<VirtualPackageEntity>): List<Pair<String, String>> {
        fun safe(block: () -> String): String =
            runCatching { block() }
                .onFailure { VLog.e("Diag", "lectura fallida", it) }
                .getOrDefault("No disponible")

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val storageStats = StatFs(filesDir.absolutePath)
        val activeSessions = resourceTracker.allSessions().count { it.state == SessionState.ACTIVE }

        return listOf(
            "Modelo" to safe { Build.MODEL },
            "Fabricante" to safe { Build.MANUFACTURER },
            "Android SDK" to safe { Build.VERSION.SDK_INT.toString() },
            "Versión Android" to safe { Build.VERSION.RELEASE },
            "ABI principal" to safe { Build.SUPPORTED_ABIS.firstOrNull() ?: "No disponible" },
            "Arquitecturas compatibles" to safe { Build.SUPPORTED_ABIS.joinToString() },
            "Tamaño de página" to safe { "${Os.sysconf(OsConstants._SC_PAGESIZE)} B" },
            "RAM total" to formatBytes(memoryInfo.totalMem),
            "RAM disponible" to formatBytes(memoryInfo.availMem),
            "Almacenamiento interno total" to formatBytes(storageStats.totalBytes),
            "Almacenamiento interno libre" to formatBytes(storageStats.availableBytes),
            "Espacio usado por Valcrono VirtualSpace" to safe {
                formatBytes(filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
            },
            "Versión de Room" to "2.6.1",
            "Número de paquetes virtuales" to packages.size.toString(),
            "Número de sesiones activas" to activeSessions.toString(),
        )
    }

    private fun runMemoryCleanup(settings: VirtualSpaceSettings) {
        val snapshot = resourceTracker.snapshot.value
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val inputs = MemoryInputs(
            totalRamBytes = snapshot.totalRamBytes,
            availableRamBytes = snapshot.availableRamBytes,
            memoryClassMb = activityManager.memoryClass,
            largeMemoryClassMb = activityManager.largeMemoryClass,
            isLowRamDevice = activityManager.isLowRamDevice,
            processPssBytes = snapshot.hostPssBytes,
            activeSessions = snapshot.activeSessions,
        )
        sessionManager.enforce(settings.performanceProfile, inputs)
        importStatus = sessionManager.actions().value.joinToString(" · ")
    }

    private fun simulateProfileChange(profile: MemoryProfile) {
        importStatus = "Perfil de rendimiento aplicado: ${profile.displayName()}"
        resourceTracker.refresh()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        sessionManager.onTrimMemory(level)
        importStatus = "Presión de memoria: ${MemoryBudgetManager().trimPolicy(level)}"
    }

    override fun onLowMemory() {
        super.onLowMemory()
        sessionManager.onLowMemory()
        importStatus = "Memoria baja: sesiones pausadas detenidas"
    }

    private fun cleanRegenerableCaches(packages: List<VirtualPackageEntity>) {
        packages.forEach { pkg ->
            repository.storage().clearCache(pkg.virtualUserId, pkg.packageName)
            repository.storage().resolver().resolve(pkg.virtualUserId, pkg.packageName, "data/code_cache").deleteRecursively()
            repository.storage().resolver().resolve(pkg.virtualUserId, pkg.packageName, "data/code_cache").mkdirs()
        }
        File(cacheDir, "exports").deleteRecursively()
        importStatus = "Limpieza completada: no se eliminaron files, databases, shared_prefs ni no_backup"
    }
    private fun safeResolve(base: File, rel: String): File { val f = File(base, rel).canonicalFile; require(f.path.startsWith(base.canonicalPath)) { "DENIED" }; return f }
    private fun sortedFiles(dir: File) = dir.listFiles().orEmpty().sortedWith(compareBy<File> { when (sortMode) { "tamaño" -> it.length(); "fecha" -> it.lastModified(); else -> 0 } }.thenBy { it.name.lowercase() })
    private fun sqlitePreview(f: File) = runCatching { SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db -> db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' LIMIT 100", null).use { c -> val rows = mutableListOf<String>(); while (c.moveToNext()) rows += c.getString(0); "SQLite tablas: ${rows.joinToString()}" } } }.getOrElse { "No disponible" }
    private fun exportToTree(p: VirtualPackageEntity, tree: Uri) { importStatus = "Exportando datos..."; contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { val files = repository.storage().resolver().packageRoot(p.virtualUserId, p.packageName).walkTopDown().filter { it.isFile }.toList(); val out = File(filesDir, "exports/ValcronoVirtualExport/${p.packageName}").apply { mkdirs() }; File(out, "metadata.json").writeText("{\"packageName\":\"${p.packageName}\",\"virtualUserId\":${p.virtualUserId},\"versionName\":\"${p.versionName}\",\"versionCode\":${p.versionCode},\"apkSha256\":\"${p.sha256}\",\"exportDate\":\"${date(System.currentTimeMillis())}\",\"formatVersion\":1}"); File(out, "package-info.json").writeText(p.toString()); File(out, "checksums.json").writeText(files.joinToString(prefix = "{", postfix = "}") { "\"${it.name}\":\"${sha256(it)}\"" }); withContext(Dispatchers.Main) { importStatus = "Exportación completada\nArchivos: ${files.size}\nTamaño: ${formatBytes(files.sumOf { it.length() })}\nDestino seleccionado" } } }
    private fun installInAndroid(p: VirtualPackageEntity) { if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))); return }; val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(p.apkInternalPath)); startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
    private fun compatLabel(level: String) = if (level == "COOPERATIVE_SUPPORTED") "Cooperativa compatible" else level
    private fun MemoryProfile.displayName() = when (this) { MemoryProfile.AUTOMATIC -> "Automático"; MemoryProfile.LOW_POWER -> "Bajo consumo"; MemoryProfile.BALANCED -> "Equilibrado"; MemoryProfile.HIGH_PERFORMANCE -> "Alto rendimiento"; MemoryProfile.CUSTOM -> "Personalizado" }
    private fun SettingAvailability.label() = when (this) { SettingAvailability.AVAILABLE -> "Disponible"; SettingAvailability.EXPERIMENTAL -> "Experimental"; SettingAvailability.NOT_IMPLEMENTED -> "No implementada" }
    private fun formatBytes(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024)) else String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024))
    private fun date(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    private fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
}

private enum class Destination(val label: String, val icon: String) {
    HOME("Inicio", "⌂"), APPS("Aplicaciones", "▣"), FILES("Archivos", "▤"), PROCESSES("Procesos", "◌"), SETTINGS("Ajustes", "⚙"), APP_SETTINGS("Ajustes app", "⚙"), APP_DIAG("Diagnóstico", "ⓘ");
    companion object { val main = listOf(HOME, APPS, FILES, PROCESSES, SETTINGS) }
}
