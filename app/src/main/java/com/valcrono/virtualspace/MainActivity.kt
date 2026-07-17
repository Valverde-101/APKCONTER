package com.valcrono.virtualspace

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.valcrono.core.MemoryBudgetManager
import com.valcrono.core.MemoryInputs
import com.valcrono.core.MemoryProfile
import com.valcrono.core.SessionState
import com.valcrono.core.SettingAvailability
import com.valcrono.core.VLog
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

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3WindowSizeClassApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
class MainActivity : ComponentActivity(), ComponentCallbacks2 {
    private enum class Destination(val label: String, val icon: String) {
        HOME("Inicio", "⌂"),
        APPS("Aplicaciones", "▣"),
        FILES("Archivos", "▤"),
        PROCESSES("Procesos", "◌"),
        SETTINGS("Ajustes", "⚙"),
        APP_SETTINGS("Ajustes app", "⚙"),
        APP_DIAG("Diagnóstico", "ⓘ");

        companion object {
            val main = listOf(HOME, APPS, FILES, PROCESSES, SETTINGS)
        }
    }

    private lateinit var repository: VirtualRepository
    private lateinit var settingsStore: VirtualSpaceSettingsStore
    private lateinit var resourceTracker: RuntimeResourceTracker
    private lateinit var sessionManager: VirtualSessionManager
    private lateinit var runtimeSessions: RuntimeSessionRepository
    private lateinit var runtimeController: RuntimeSessionController
    private lateinit var runtimeMetrics: RuntimeMetricsRepository

    private var destination by mutableStateOf(Destination.HOME)
    private var selectedPackage by mutableStateOf<VirtualPackageEntity?>(null)
    private var explorerPath by mutableStateOf("")
    private var importStatus by mutableStateOf("PROTOTIPO FUNCIONAL PARA APPS COOPERATIVAS")
    private var isImporting by mutableStateOf(false)
    private var pendingLaunchPackage by mutableStateOf<VirtualPackageEntity?>(null)
    private var exportPackage by mutableStateOf<VirtualPackageEntity?>(null)
    private var sortMode by mutableStateOf("nombre")
    private var pendingExternalInstall by mutableStateOf<VirtualPackageEntity?>(null)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) importStatus = "Selección cancelada" else importUri(uri)
    }

    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val pkg = exportPackage
        exportPackage = null
        when {
            pkg == null -> importStatus = "No hay paquete seleccionado para exportar"
            uri == null -> importStatus = "Exportación cancelada"
            else -> exportToTree(pkg, uri)
        }
    }

    private val permissionRequester = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        importStatus = if (grants.values.all { it }) {
            "Permisos listos"
        } else {
            "Permisos pendientes: ${grants.values.count { !it }}"
        }
        pendingLaunchPackage?.let(::launchVirtual)
        pendingLaunchPackage = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = VirtualRepository(applicationContext)
        settingsStore = VirtualSpaceSettingsStore(applicationContext)
        resourceTracker = RuntimeResourceTracker(applicationContext)
        sessionManager = VirtualSessionManager(resourceTracker)
        runtimeMetrics = RuntimeMetricsRepository(resourceTracker)
        runtimeSessions = RuntimeSessionRepository(repository.db)
        runtimeController = RuntimeSessionController(runtimeSessions, repository.db, runtimeMetrics)
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runtimeSessions.reconcileStartup() }
        setContent { AppUi() }
    }

    @Composable
    private fun AppUi() {
        val scope = rememberCoroutineScope()
        val settings by settingsStore.settings.collectAsState(initial = VirtualSpaceSettings())
        val snapshot by resourceTracker.snapshot.collectAsState()
        var packages by remember { mutableStateOf<List<VirtualPackageEntity>>(emptyList()) }
        var sessions by remember { mutableStateOf<List<VirtualRuntimeSessionEntity>>(emptyList()) }
        val snackbarHostState = remember { SnackbarHostState() }
        val widthClass = calculateWindowSizeClass(this).widthSizeClass
        val wide = widthClass != WindowWidthSizeClass.Compact

        LaunchedEffect(Unit) {
            repository.packages().collectLatest { packages = it }
        }
        LaunchedEffect(Unit) {
            repository.sessions().collectLatest { rows ->
                sessions = rows
                runtimeMetrics.replaceMetricsForSessions(rows)
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                withContext(Dispatchers.IO) { runtimeController.watchdogTick() }
                delay(1000)
            }
        }
        LaunchedEffect(destination) {
            while (destination == Destination.PROCESSES) {
                resourceTracker.refresh()
                delay(3000)
            }
        }
        LaunchedEffect(settings.flagSecureEnabled) {
            if (settings.flagSecureEnabled) {
                window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        MaterialTheme {
            pendingExternalInstall?.let { pkg -> ExternalInstallDialog(pkg) }
            Scaffold(
                modifier = Modifier.safeDrawingPadding().imePadding(),
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("Valcrono VirtualSpace", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Estado general: Normal", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        actions = { TextButton(onClick = { destination = Destination.SETTINGS }) { Text("Menú") } },
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (!wide) {
                        NavigationBar {
                            Destination.main.forEach { item ->
                                NavigationBarItem(
                                    selected = destination == item,
                                    onClick = { destination = item },
                                    icon = { Text(item.icon) },
                                    label = { Text(item.label, maxLines = 1) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Row(Modifier.padding(padding).fillMaxSize()) {
                    if (wide) {
                        NavigationRail {
                            Destination.main.forEach { item ->
                                NavigationRailItem(
                                    selected = destination == item,
                                    onClick = { destination = item },
                                    icon = { Text(item.icon) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    }
                    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        when (destination) {
                            Destination.HOME -> HomeScreen(packages, snapshot, settings, sessions)
                            Destination.APPS -> AppsScreen(packages, sessions)
                            Destination.FILES -> GlobalFileExplorer(packages, settings)
                            Destination.PROCESSES -> ProcessScreen(snapshot, packages, settings, sessions)
                            Destination.SETTINGS -> SettingsScreen(settings, packages)
                            Destination.APP_SETTINGS -> PerAppSettingsScreen(selectedPackage)
                            Destination.APP_DIAG -> AppDiagnostics(selectedPackage)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ExternalInstallDialog(pkg: VirtualPackageEntity) {
        AlertDialog(
            onDismissRequest = { pendingExternalInstall = null },
            title = { Text("Instalar fuera de VirtualSpace") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Esta aplicación se instalará en el perfil principal de Android. No utilizará sus archivos ni configuraciones de VirtualSpace. Sus datos normales y virtuales permanecerán separados.")
                Text(externalInstallStatus(pkg))
                Text("La copia virtual se mantendrá intacta.")
            } },
            confirmButton = { Button(onClick = { pendingExternalInstall = null; continueExternalInstall(pkg) }) { Text("Continuar") } },
            dismissButton = { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = { pendingExternalInstall = null }) { Text("Cancelar") }; TextButton(onClick = { pendingExternalInstall = null }) { Text("No volver a mostrar") } } },
        )
    }

    @Composable
    private fun HomeScreen(packages: List<VirtualPackageEntity>, snapshot: RuntimeResourceSnapshot, settings: VirtualSpaceSettings, sessions: List<VirtualRuntimeSessionEntity>) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item { if (!settings.firstRunComplete) FirstRunCard() }
            item {
                InfoCard(
                    title = "Resumen",
                    rows = listOf(
                        "Aplicaciones instaladas" to packages.size.toString(),
                        "Aplicación activa" to (sessions.firstOrNull { it.state == "ACTIVE" }?.packageName ?: "Ninguna"),
                        "RAM usada por el host" to formatBytes(snapshot.hostPssBytes),
                        "Almacenamiento usado" to formatBytes(packages.sumOf { repository.storage().usedBytes(it.virtualUserId, it.packageName) }),
                        "Mensajes pendientes" to "0",
                        "Último error" to "No disponible",
                    ),
                )
            }
            item { Text("El presupuesto de memoria es un objetivo administrado por VirtualSpace; Android conserva el control final de memoria.") }
            item { if (isImporting) Text("Importando…") }
            item { Text(importStatus) }
        }
    }

    @Composable
    private fun AppsScreen(packages: List<VirtualPackageEntity>, sessions: List<VirtualRuntimeSessionEntity>) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { launchApkPicker() }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Importar APK") }
                    Button(onClick = { destination = Destination.FILES }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Archivos") }
                    Button(onClick = { destination = Destination.PROCESSES }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Procesos") }
                }
            }
            items(packages) { pkg -> AppCard(pkg, sessions.firstOrNull { it.packageName == pkg.packageName && it.virtualUserId == pkg.virtualUserId }) }
        }
    }

    @Composable
    private fun AppCard(pkg: VirtualPackageEntity, session: VirtualRuntimeSessionEntity?) {
        var menuExpanded by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val snapshot by resourceTracker.snapshot.collectAsState()
        val state = session?.state?.let { runCatching { SessionState.valueOf(it) }.getOrNull() } ?: SessionState.STOPPED
        Card(Modifier.fillMaxWidth().semantics { contentDescription = "Aplicación ${pkg.label}" }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("▣", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pkg.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(pkg.packageName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("v${pkg.versionName} · ${compatLabel(pkg.compatibilityLevel)} · ${runtimeModeLabel(pkg)}")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Estado: ${sessionStateLabel(state)}")
                    Text(if (state == SessionState.ACTIVE) "RAM del host compartida: ${formatBytes(snapshot.hostPssBytes)}" else "RAM actual: 0 MB · Sin proceso activo")
                    Text("Datos: ${formatBytes(repository.storage().usedBytes(pkg.virtualUserId, pkg.packageName))}")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pkg.compatibilityLevel == "COOPERATIVE_SUPPORTED" && pkg.entryPointClass != null) {
                        Button(
                            onClick = { openVirtual(pkg) },
                            enabled = state != SessionState.STARTING && pkg.enabled && !pkg.damaged,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) { Text(if (state == SessionState.ACTIVE) "Volver" else "Abrir") }
                    } else {
                        Text("APK disponible para inspección; runtime no compatible.")
                        Button(onClick = { selectedPackage = pkg; explorerPath = "/data/app/${pkg.packageName}/base.apk"; destination = Destination.FILES }) { Text("Inspeccionar") }
                    }
                    when (state) {
                        SessionState.STARTING -> OutlinedButton(onClick = { stopPackage(pkg) }) { Text("Cancelar") }
                        SessionState.ACTIVE -> { OutlinedButton(onClick = { pausePackage(pkg) }) { Text("Pausar") }; OutlinedButton(onClick = { stopPackage(pkg) }) { Text("Detener") } }
                        SessionState.PAUSED -> { OutlinedButton(onClick = { openVirtual(pkg) }) { Text("Reanudar") }; OutlinedButton(onClick = { stopPackage(pkg) }) { Text("Detener") } }
                        SessionState.ERROR -> OutlinedButton(onClick = { importStatus = session?.sanitizedError ?: "Error no disponible" }) { Text("Ver error") }
                        else -> Unit
                    }
                    OutlinedButton(
                        onClick = {
                            selectedPackage = pkg
                            explorerPath = ""
                            destination = Destination.FILES
                        },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) { Text("Archivos") }
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) { Text("⋮") }
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    appMenuLabels.forEach { label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                menuExpanded = false
                                handleAppMenu(label, pkg, scope)
                            },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen(settings: VirtualSpaceSettings, packages: List<VirtualPackageEntity>) {
        val scope = rememberCoroutineScope()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item { Text("Ajustes de VirtualSpace", style = MaterialTheme.typography.headlineSmall) }
            item {
                SettingsSection("Rendimiento") {
                    Text("Perfil de rendimiento")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MemoryProfile.entries.forEach { profile ->
                            FilterChip(
                                selected = settings.performanceProfile == profile,
                                onClick = { scope.launch { settingsStore.setProfile(profile); simulateProfileChange(profile) } },
                                label = { Text(profile.displayName()) },
                            )
                        }
                    }
                    Text("El límite seleccionado es un objetivo de uso administrado por VirtualSpace. Android puede finalizar procesos cuando necesite memoria.")
                }
            }
            item { SettingsSection("Aplicaciones virtuales") {
                Text("Máximo de apps activas")
                Slider(value = settings.maxActiveApps.toFloat(), onValueChange = { scope.launch { settingsStore.setMaxActiveApps(it.toInt()) } }, valueRange = 1f..3f, steps = 1)
                Text("${settings.maxActiveApps} app activa")
                Text("Qué hacer al abrir otra app")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Preguntar" to com.valcrono.core.OpenAnotherAppPolicy.ASK, "Pausar actual" to com.valcrono.core.OpenAnotherAppPolicy.PAUSE_CURRENT, "Detener actual" to com.valcrono.core.OpenAnotherAppPolicy.STOP_CURRENT, "Impedir apertura" to com.valcrono.core.OpenAnotherAppPolicy.BLOCK).forEach { (label, policy) -> FilterChip(selected = settings.openAnotherAppPolicy == policy, onClick = { scope.launch { settingsStore.setOpenAnotherAppPolicy(policy) } }, label = { Text(label) }) } }
            } }
            item { SettingsSection("Almacenamiento") {
                SettingSwitch("Eliminar caché automáticamente", settings.autoDeleteCache) { scope.launch { settingsStore.setBool("auto_delete_cache", it) } }
                Text("Caché máxima total")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(64, 128, 256, 512).forEach { mb -> FilterChip(selected = settings.maxCacheStorageMb == mb, onClick = { scope.launch { settingsStore.setMaxCacheStorageMb(mb) } }, label = { Text("$mb MB") }) } }
                SettingSwitch("Advertir cuando quede poco espacio", settings.warnLowStorage) { scope.launch { settingsStore.setWarnLowStorage(it) } }
            } }
            item { SettingsSection("Interfaz") {
                Text("Tema")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Sistema", "Claro", "Oscuro").forEach { FilterChip(selected = settings.theme == it, onClick = { scope.launch { settingsStore.setTheme(it) } }, label = { Text(it) }) } }
                Text("Densidad")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Compacto", "Normal", "Amplio").forEach { FilterChip(selected = settings.cardDensity == it, onClick = { scope.launch { settingsStore.setCardDensity(it) } }, label = { Text(it) }) } }
                SettingSwitch("Animaciones", settings.animations) { scope.launch { settingsStore.setBool("animations", it) } }
                SettingSwitch("Reducir movimiento", settings.reduceMotion) { scope.launch { settingsStore.setBool("reduce_motion", it) } }
                SettingSwitch("Confirmar acciones destructivas", settings.confirmDestructiveActions) { scope.launch { settingsStore.setBool("confirm_destructive_actions", it) } }
            } }
            item { SettingsSection("Permisos") { PermissionsScreen() } }
            item { SettingsSection("Copias de seguridad") { SettingSwitch("Incluir APK en exportación", settings.includeApkInExport) { scope.launch { settingsStore.setBool("include_apk_export", it) } }; SettingSwitch("Incluir caché", settings.includeCacheInExport) { scope.launch { settingsStore.setBool("include_cache_export", it) } }; SettingSwitch("Incluir logs", settings.includeLogsInExport) { scope.launch { settingsStore.setBool("include_logs_export", it) } }; SettingSwitch("Verificación SHA-256", settings.backupSha256) { scope.launch { settingsStore.setBool("backup_sha256", it) } }; SettingSwitch("Backup antes de borrar", settings.backupBeforeDelete) { scope.launch { settingsStore.setBool("backup_before_delete", it) } } } }
            item { SettingsSection("Seguridad") { settingsText("Bloqueo mediante PIN: No implementada", "Desbloqueo biométrico: No implementada", "Impedir capturas en pantallas sensibles: ${settings.flagSecureEnabled}", "Registro de acciones administrativas: ${settings.adminActionLog}") } }
            item { SettingsSection("Diagnóstico") { deviceDiagnostics(packages).forEach { Text("${it.first}: ${it.second}") } } }
            item { SettingsSection("Desarrollador") { SettingSwitch("Modo desarrollador", settings.developerMode) { scope.launch { settingsStore.setDeveloperMode(it) } }; Text("Nivel de logs"); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Error", "Información", "Depuración").forEach { level -> FilterChip(selected = settings.logLevel == level, onClick = { scope.launch { settingsStore.setLogLevel(level) } }, label = { Text(level) }) } }; SettingSwitch("Mostrar stack traces", settings.showStackTraces) { scope.launch { settingsStore.setBool("show_stack_traces", it) } } } }
            item { SettingsSection("Acerca de") { settingsText("Nombre: Valcrono VirtualSpace", "Versión: ${BuildConfig.VERSION_NAME}", "Código de versión: ${BuildConfig.VERSION_CODE}", "Commit del build: ${BuildConfig.GIT_COMMIT}", "Fecha de compilación: ${BuildConfig.BUILD_DATE}", "Tipo de build: ${BuildConfig.BUILD_TYPE}", "SDK objetivo: 35", "Estado del runtime: PROTOTIPO FUNCIONAL PARA APPS COOPERATIVAS") } }
            item {
                SettingsSection("Restablecimiento") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { settingsStore.reset() } }) { Text("Restablecer ajustes") }
                        Button(onClick = { cleanRegenerableCaches(packages) }) { Text("Limpiar cachés") }
                        Button(onClick = { closeAllSessions() }) { Text("Cerrar todas las sesiones") }
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    @Composable
    private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }

    @Composable
    private fun ColumnScope.settingsText(vararg rows: String) {
        rows.forEach { Text(it) }
    }

    @Composable
    private fun FirstRunCard() {
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bienvenida")
                Text("Perfil recomendado para Moto G06: Automático o Bajo consumo, una aplicación activa, caché moderada y cierre de sesiones inactivas.")
                Text("Permisos necesarios: ninguno amplio para importar/exportar; se usa SAF.")
                Button(onClick = { kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { settingsStore.setFirstRunComplete(true) } }) { Text("Continuar") }
            }
        }
    }

    @Composable
    private fun PerAppSettingsScreen(pkg: VirtualPackageEntity?) {
        val title = "Ajustes de ${pkg?.label ?: "aplicación"}"
        val options = listOf(
            "Abrir automáticamente" to SettingAvailability.AVAILABLE,
            "Permitir ejecución en segundo plano" to SettingAvailability.EXPERIMENTAL,
            "Mantener sesión pausada" to SettingAvailability.EXPERIMENTAL,
            "Presupuesto de caché" to SettingAvailability.AVAILABLE,
            "Cuota de almacenamiento" to SettingAvailability.AVAILABLE,
            "Permisos virtuales" to SettingAvailability.AVAILABLE,
            "Acceso a mensajes de otras apps" to SettingAvailability.EXPERIMENTAL,
            "Acceso administrativo futuro" to SettingAvailability.NOT_IMPLEMENTED,
            "Guardar estado al detener" to SettingAvailability.AVAILABLE,
            "Cerrar después de inactividad" to SettingAvailability.AVAILABLE,
            "Orientación preferida" to SettingAvailability.EXPERIMENTAL,
            "Escala de interfaz" to SettingAvailability.EXPERIMENTAL,
            "Tamaño de fuente virtual" to SettingAvailability.EXPERIMENTAL,
            "Idioma virtual" to SettingAvailability.NOT_IMPLEMENTED,
            "Tema claro/oscuro/sistema" to SettingAvailability.AVAILABLE,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item { Text(title, style = MaterialTheme.typography.headlineSmall) }
            items(options) { (name, availability) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(name)
                        Text(availability.label())
                        if (availability == SettingAvailability.EXPERIMENTAL) {
                            Text("Experimental: puede cambiar y requiere validación por app cooperativa.")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ProcessScreen(snapshot: RuntimeResourceSnapshot, packages: List<VirtualPackageEntity>, settings: VirtualSpaceSettings, sessions: List<VirtualRuntimeSessionEntity>) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item { Text("Procesos y memoria", style = MaterialTheme.typography.headlineSmall) }
            item {
                InfoCard(
                    title = "Memoria",
                    rows = listOf(
                        "RAM total del dispositivo" to formatBytes(snapshot.totalRamBytes),
                        "RAM disponible" to formatBytes(snapshot.availableRamBytes),
                        "RAM usada por VirtualSpace" to formatBytes(snapshot.hostPssBytes),
                        "Java heap usado" to formatBytes(snapshot.javaHeapUsedBytes),
                        "Native heap aproximado" to formatBytes(snapshot.nativeHeapBytes),
                        "PSS total del proceso" to formatBytes(snapshot.hostPssBytes),
                        "Sesiones virtuales activas" to snapshot.activeSessions.toString(),
                        "ClassLoaders activos" to snapshot.classLoaders.toString(),
                        "Bases SQLite abiertas" to snapshot.openSqliteDatabases.toString(),
                        "Tareas de importación/exportación" to snapshot.runningTasks.toString(),
                    ),
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { closeAllSessions() }) { Text("Cerrar todas las sesiones") }
                    Button(onClick = { cleanRegenerableCaches(packages) }) { Text("Liberar caché") }
                    Button(onClick = { runMemoryCleanup(settings) }) { Text("Ejecutar limpieza") }
                }
            }
            items(sessions) { session -> SessionCard(session, packages.firstOrNull { it.packageName == session.packageName }?.label ?: session.packageName) }
        }
    }

    @Composable
    private fun SessionCard(session: VirtualRuntimeSessionEntity, label: String) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val state = runCatching { SessionState.valueOf(session.state) }.getOrDefault(SessionState.ERROR)
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text("Aplicación: ${session.packageName}")
                Text("Estado: ${sessionStateLabel(state)}")
                Text("Tiempo activa: ${(System.currentTimeMillis() - (session.startedAt ?: session.createdAt)) / 1000} s")
                Text("Última actividad: ${date(session.lastActivityAt)}")
                session.sanitizedError?.let { Text("Error: $it") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state == SessionState.ACTIVE) Button(onClick = { pausePackageBySession(session) }) { Text("Pausar") }
                    if (state != SessionState.STOPPED) Button(onClick = { stopPackageBySession(session) }) { Text("Detener") }
                }
            }
        }
    }

    @Composable
    private fun PermissionsScreen() {
        val rows = buildPermissionRows()
        Text(if (rows.none { it.pending }) "Permisos listos" else "Permisos pendientes: ${rows.count { it.pending }}")
        Text("Permiso | Estado | Motivo | Acción")
        rows.forEach { row ->
            val state = if (row.pending) "Pendiente" else "Listo/No necesario"
            Text("${row.name} | $state | ${row.reason} | ${row.action}")
        }
    }

    @Composable
    private fun AppDiagnostics(pkg: VirtualPackageEntity?) {
        if (pkg == null) {
            Text("Selecciona una app")
            return
        }
        val root = repository.storage().resolver().packageRoot(pkg.virtualUserId, pkg.packageName)
        val rows = listOf(
            "Hash del APK" to pkg.sha256,
            "Ruta interna" to "aislada (no expuesta a apps virtuales)",
            "Firma" to "Validada por PackageManager",
            "Entry point" to (pkg.entryPointClass ?: "No disponible"),
            "ClassLoader" to "DexClassLoader aislado",
            "Sesión actual" to (resourceTracker.allSessions().firstOrNull { it.packageName == pkg.packageName }?.state?.name ?: "DETENIDA"),
            "Último error" to "No disponible",
            "Archivos usados" to root.walkTopDown().count { it.isFile }.toString(),
            "Clasificación de compatibilidad" to pkg.compatibilityLevel,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item { Text("Diagnóstico de ${pkg.label}", style = MaterialTheme.typography.headlineSmall) }
            items(rows) { row -> Text("${row.first}: ${row.second}") }
            item {
                Button(onClick = {
                    val logs = VLog.recent().joinToString("\n")
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Valcrono logs", logs))
                }) { Text("Copiar logs") }
            }
        }
    }


    @Composable
    private fun GlobalFileExplorer(packages: List<VirtualPackageEntity>, settings: VirtualSpaceSettings) {
        val vfs = remember(packages) { com.valcrono.virtualstorage.VirtualFileSystem(com.valcrono.virtualstorage.VirtualFsNamespace(filesDir) { packages.map { it.packageName } }) }
        val path = explorerPath.ifBlank { "/" }
        val node = runCatching { vfs.resolve(path, com.valcrono.virtualstorage.VirtualFsAccessContext.admin()) }.getOrNull()
        val children = runCatching { vfs.list(path, com.valcrono.virtualstorage.VirtualFsAccessContext.admin()) }.getOrDefault(emptyList())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item {
                TopAppBar(
                    title = { Column { Text("Archivos", maxLines = 1, overflow = TextOverflow.Ellipsis); Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) } },
                    navigationIcon = { IconButton(onClick = { explorerPath = path.substringBeforeLast('/', "").ifBlank { "/" } }) { Text("←") } },
                    actions = { IconButton(onClick = { importStatus = "Búsqueda preparada para $path" }) { Text("🔍") }; IconButton(onClick = {}) { Text("▦") }; IconButton(onClick = {}) { Text("⋮") } },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Sistema", "Apps", "Compartido", "APKs", "Procesos").forEach { FilterChip(selected = false, onClick = {}, label = { Text(it) }) } }
                if (path == "/") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { mapOf("Datos privados" to "/data/data", "Datos externos" to "/storage/emulated/0/Android/data", "Almacenamiento compartido" to "/storage/emulated/0", "APKs instalados" to "/data/app", "Bases de datos" to "/data/data", "Preferencias" to "/data/data", "Caché" to "/data/data", "Procesos" to "/proc").forEach { (label, target) -> Button(onClick = { explorerPath = target }) { Text(label) } } }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { items(listOf("Raíz").plus(path.split('/').filter { it.isNotBlank() })) { crumb -> val parts = path.split('/').filter { it.isNotBlank() }; val index = listOf("Raíz").plus(parts).indexOf(crumb); FilterChip(selected = false, onClick = { explorerPath = if (index == 0) "/" else "/" + parts.take(index).joinToString("/") }, label = { Text(crumb, maxLines = 1) }) } }
                Text("${children.size} elementos")
                node?.let { Text("Tipo: ${it.type} · Tamaño: ${formatBytes(it.size)} · Fecha: ${date(if (it.modifiedAt > 0) it.modifiedAt else System.currentTimeMillis())} · Permisos virtuales: ${it.permissions} · Propietario virtual: ${it.owner} · Paquete asociado: ${it.packageName ?: "—"}") }
                if (settings.developerMode) node?.physicalFile?.let { Text("Ruta física: ${it.absolutePath}") }
                if (path != "/") Button(onClick = { explorerPath = path.substringBeforeLast('/', "").ifBlank { "/" } }) { Text("Arriba") }
            }
            items(children) { child ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(8.dp)) {
                    Text("${if (child.type == com.valcrono.virtualstorage.VirtualFsNodeType.DIRECTORY) "📁" else "📄"} ${child.name}")
                    Text("Tipo: ${child.type}")
                    Text("${child.type} · ${if (child.type == com.valcrono.virtualstorage.VirtualFsNodeType.DIRECTORY) "" else formatBytes(child.size) + " · "}${date(if (child.modifiedAt > 0) child.modifiedAt else System.currentTimeMillis())} · ${child.permissions} · ${child.owner} · ${child.packageName ?: "—"}")
                    Button(onClick = { explorerPath = child.virtualPath }) { Text(if (child.type == com.valcrono.virtualstorage.VirtualFsNodeType.DIRECTORY) "Abrir" else "Ver") }
                } }
            }
        }
    }

    @Composable
    private fun FileExplorer(pkg: VirtualPackageEntity?) {
        if (pkg == null) {
            Text("Selecciona una aplicación")
            return
        }
        val roots = listOf("data/files", "data/databases", "data/shared_prefs", "data/cache", "data/code_cache", "data/no_backup", "external/files", "external/cache", "metadata", "apk", "lib")
        val base = repository.storage().resolver().packageRoot(pkg.virtualUserId, pkg.packageName)
        val dir = if (explorerPath.isBlank()) base else safeResolve(base, explorerPath)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item {
                Text("Archivos de ${pkg.label}", style = MaterialTheme.typography.headlineSmall)
                Text("Ruta: /${explorerPath.ifBlank { "." }}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("nombre", "tamaño", "fecha").forEach { mode -> Button(onClick = { sortMode = mode }) { Text("Ordenar $mode") } }
                    Button(onClick = { File(dir, "nueva-carpeta").mkdirs() }) { Text("Crear carpeta") }
                }
            }
            if (explorerPath.isBlank()) {
                items(roots) { root -> Button(onClick = { explorerPath = root }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(root) } }
            } else {
                item { Button(onClick = { explorerPath = explorerPath.substringBeforeLast('/', "") }) { Text("Arriba") } }
                items(sortedFiles(dir)) { file -> FileRow(file) }
            }
        }
    }

    @Composable
    private fun FileRow(file: File) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${if (file.isDirectory) "📁" else "📄"} ${file.name}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${formatBytes(file.length())} · ${date(file.lastModified())}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (file.isDirectory) Button(onClick = { explorerPath = (explorerPath + "/" + file.name).trim('/') }) { Text("Abrir") }
                    if (file.isFile) Button(onClick = { importStatus = previewFile(file) }) { Text("Ver") }
                    Button(onClick = { importStatus = "Propiedades: ${file.name} ${formatBytes(file.length())}" }) { Text("Propiedades") }
                    Button(onClick = { file.deleteRecursively() }) { Text("Eliminar") }
                }
            }
        }
    }

    @Composable
    private fun InfoCard(title: String, rows: List<Pair<String, String>>) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                rows.forEach { row -> Text("${row.first}: ${row.second}") }
            }
        }
    }

    private fun launchApkPicker() {
        picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip", "*/*"))
    }

    private fun importUri(uri: Uri) {
        importStatus = "Copiando APK seleccionado..."
        isImporting = true
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            try {
                val incoming = withContext(Dispatchers.IO) {
                    val out = File(cacheDir, "incoming-${System.currentTimeMillis()}.apk")
                    contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                        ?: error("No se pudo abrir el APK")
                    out
                }
                val parsed = AndroidArchivePackageParser(this@MainActivity).parse(incoming)
                val imported = withContext(Dispatchers.IO) { repository.importCopiedApk(incoming, 0, parsed) }
                incoming.delete()
                importStatus = "Importado ${imported.label}: ${imported.compatibilityLevel}"
            } catch (t: Throwable) {
                importStatus = "No se pudo importar el APK: ${t.message}"
                VLog.e("UI", importStatus, t)
            } finally {
                isImporting = false
            }
        }
    }

    private fun openVirtual(pkg: VirtualPackageEntity) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            val missing = withContext(Dispatchers.IO) { missingRuntimePermissions(pkg) }
            if (missing.isNotEmpty()) {
                pendingLaunchPackage = pkg
                permissionRequester.launch(missing.toTypedArray())
            } else {
                launchVirtual(pkg)
            }
        }
    }

    private fun launchVirtual(pkg: VirtualPackageEntity) {
        val activity = pkg.entryPointClass ?: run { importStatus = "No ejecutable: entry point no declarado"; return }
        if (pkg.compatibilityLevel != "COOPERATIVE_SUPPORTED") { importStatus = "Importada · No compatible con runtime cooperativo · Entry point no declarado"; return }
        val token = java.util.UUID.randomUUID().toString()
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            val shouldStart = withContext(Dispatchers.IO) {
                val row = runtimeController.prepareLaunch(pkg, token, activity)
                row.sessionId == token && row.state == "STARTING"
            }
            if (!shouldStart) { importStatus = "Sesión existente reutilizada: ${pkg.label}"; return@launch }
            startActivity(
                Intent(this@MainActivity, ProxyActivity::class.java)
                    .putExtra("virtualUserId", pkg.virtualUserId)
                    .putExtra("virtualPackageName", pkg.packageName)
                    .putExtra("virtualActivityName", activity)
                    .putExtra("launchToken", token),
            )
        }
    }

    private fun stopPackage(pkg: VirtualPackageEntity) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runtimeController.stop(pkg.packageName, pkg.virtualUserId) }
        importStatus = "Sesión detenida: ${pkg.label}"
    }

    private fun pausePackage(pkg: VirtualPackageEntity) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { repository.db.runtime().forPackage(pkg.packageName, pkg.virtualUserId)?.let { repository.db.runtime().updateState(it.sessionId, "PAUSED", System.currentTimeMillis(), null, null) } }
        importStatus = "Sesión pausada: ${pkg.label}"
    }

    private fun stopPackageBySession(session: VirtualRuntimeSessionEntity) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { repository.db.runtime().updateState(session.sessionId, "STOPPED", System.currentTimeMillis(), null, null); runtimeMetrics.removeMetrics(session.sessionId) }
    }

    private fun pausePackageBySession(session: VirtualRuntimeSessionEntity) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { repository.db.runtime().updateState(session.sessionId, "PAUSED", System.currentTimeMillis(), null, null) }
    }

    private fun closeAllSessions() {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { repository.db.runtime().stopAll(System.currentTimeMillis()) }
        resourceTracker.allSessions().forEach { resourceTracker.stop(it.sessionId) }
        importStatus = "Todas las sesiones se marcaron como detenidas"
    }

    private fun handleAppMenu(label: String, pkg: VirtualPackageEntity, scope: kotlinx.coroutines.CoroutineScope) {
        when (label) {
            "Información", "Diagnóstico" -> {
                selectedPackage = pkg
                destination = Destination.APP_DIAG
            }
            "Permisos" -> requestPermissionsForPackage(pkg)
            "Ajustes de la aplicación" -> {
                selectedPackage = pkg
                destination = Destination.APP_SETTINGS
            }
            "Exportar" -> {
                exportPackage = pkg
                treePicker.launch(null)
            }
            "Restaurar" -> importStatus = "Restauración requiere validar metadata, hashes y confirmación"
            "Limpiar caché" -> scope.launch { repository.storage().clearCache(pkg.virtualUserId, pkg.packageName) }
            "Borrar datos" -> scope.launch { repository.storage().resolver().resolve(pkg.virtualUserId, pkg.packageName, "data").deleteRecursively() }
            "Actualizar APK" -> launchApkPicker()
            "Desinstalar" -> scope.launch {
                repository.db.packages().deletePackage(pkg.packageName, pkg.virtualUserId)
                repository.storage().resolver().packageRoot(pkg.virtualUserId, pkg.packageName).deleteRecursively()
            }
            "Instalar fuera de VirtualSpace" -> installInAndroid(pkg)
        }
    }

    private suspend fun missingRuntimePermissions(pkg: VirtualPackageEntity): List<String> =
        repository.db.permissions().permissionNames(pkg.packageName, pkg.virtualUserId)
            .mapNotNull(::normalizeRuntimePermission)
            .distinct()
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun normalizeRuntimePermission(permission: String): String? = when (permission) {
        android.Manifest.permission.MANAGE_EXTERNAL_STORAGE,
        "android.permission.QUERY_ALL_PACKAGES",
        android.Manifest.permission.REQUEST_INSTALL_PACKAGES,
        android.Manifest.permission.READ_MEDIA_IMAGES,
        android.Manifest.permission.READ_MEDIA_VIDEO,
        android.Manifest.permission.READ_MEDIA_AUDIO
        -> null
        android.Manifest.permission.POST_NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) permission else null
        else -> if (permission in supportedRuntimePermissions) permission else null
    }

    private val supportedRuntimePermissions = setOf(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.POST_NOTIFICATIONS,
    )

    private fun requestPermissionsForPackage(pkg: VirtualPackageEntity) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            val missing = withContext(Dispatchers.IO) { missingRuntimePermissions(pkg) }
            if (missing.isEmpty()) importStatus = "${pkg.label}: permisos listos" else permissionRequester.launch(missing.toTypedArray())
        }
    }

    private data class PermissionRow(val name: String, val pending: Boolean, val reason: String, val action: String)

    private fun buildPermissionRows(): List<PermissionRow> = listOf(
        PermissionRow("Storage Access Framework", false, "Importar/exportar APK y datos sin almacenamiento amplio", "Seleccionar archivo/carpeta"),
        PermissionRow("POST_NOTIFICATIONS", false, "Solo se pide si una app cooperativa lo declara y Android 13+ lo requiere", "Bajo demanda"),
        PermissionRow("MANAGE_EXTERNAL_STORAGE", false, "No imprescindible; no se solicita", "No solicitar"),
        PermissionRow("QUERY_ALL_PACKAGES", false, "No se necesita para analizar APK importados", "No solicitar"),
        PermissionRow("REQUEST_INSTALL_PACKAGES", false, "Solo Ajustes si el usuario elige instalar normalmente", "Ajustes"),
    )

    private fun deviceDiagnostics(packages: List<VirtualPackageEntity>): List<Pair<String, String>> {
        fun safe(block: () -> String): String = runCatching { block() }
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
            "Espacio usado por Valcrono VirtualSpace" to safe { formatBytes(filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }) },
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
        importStatus = "Alerta de memoria reciente: ${trimPolicyLabel(MemoryBudgetManager().trimPolicy(level).name)}"
    }

    @Deprecated("Android framework deprecated onLowMemory; retained for ComponentCallbacks compatibility")
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

    private fun safeResolve(base: File, relative: String): File {
        val resolved = File(base, relative).canonicalFile
        require(resolved.path.startsWith(base.canonicalPath)) { "DENIED" }
        return resolved
    }

    private fun sortedFiles(dir: File): List<File> = dir.listFiles().orEmpty().sortedWith(
        compareBy<File> {
            when (sortMode) {
                "tamaño" -> it.length()
                "fecha" -> it.lastModified()
                else -> 0L
            }
        }.thenBy { it.name.lowercase() },
    )

    private fun previewFile(file: File): String = if (file.extension in setOf("txt", "json", "xml", "properties")) {
        file.readText().take(1000)
    } else {
        sqlitePreview(file)
    }

    private fun sqlitePreview(file: File): String = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' LIMIT 100", null).use { cursor ->
                val rows = mutableListOf<String>()
                while (cursor.moveToNext()) rows += cursor.getString(0)
                "SQLite tablas: ${rows.joinToString()}"
            }
        }
    }.getOrElse { "No disponible" }

    private fun exportToTree(pkg: VirtualPackageEntity, tree: Uri) {
        importStatus = "Exportando datos..."
        contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val root = repository.storage().resolver().packageRoot(pkg.virtualUserId, pkg.packageName)
            val files = root.walkTopDown().filter { it.isFile }.toList()
            val out = File(filesDir, "exports/ValcronoVirtualExport/${pkg.packageName}").apply { mkdirs() }
            File(out, "metadata.json").writeText("{\"packageName\":\"${pkg.packageName}\",\"virtualUserId\":${pkg.virtualUserId},\"versionName\":\"${pkg.versionName}\",\"versionCode\":${pkg.versionCode},\"apkSha256\":\"${pkg.sha256}\",\"exportDate\":\"${date(System.currentTimeMillis())}\",\"formatVersion\":1}")
            File(out, "package-info.json").writeText(pkg.toString())
            File(out, "checksums.json").writeText(files.joinToString(prefix = "{", postfix = "}") { "\"${it.name}\":\"${sha256(it)}\"" })
            withContext(Dispatchers.Main) {
                importStatus = "Exportación completada\nArchivos: ${files.size}\nTamaño: ${formatBytes(files.sumOf { it.length() })}\nDestino seleccionado"
            }
        }
    }

    private fun installInAndroid(pkg: VirtualPackageEntity) {
        pendingExternalInstall = pkg
    }

    private fun continueExternalInstall(pkg: VirtualPackageEntity) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(pkg.apkInternalPath))
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun externalInstallStatus(pkg: VirtualPackageEntity): String {
        val installed = runCatching { packageManager.getPackageInfo(pkg.packageName, 0) }.getOrNull()
        return when {
            installed == null -> "Estado: NOT_INSTALLED. Android permitirá una instalación normal."
            installed.versionCodeCompat() == pkg.versionCode -> "Estado: SAME_VERSION. Ya está instalada la misma versión en Android."
            installed.versionCodeCompat() > pkg.versionCode -> "Estado: NEWER_VERSION_INSTALLED. Sería una versión anterior."
            else -> "Estado: UPDATE_AVAILABLE. Android lo tratará como actualización si la firma coincide."
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        versionCode.toLong()
    }

    private fun compatLabel(level: String): String = when (level) { "COOPERATIVE_SUPPORTED" -> "Cooperativa compatible"; "HIGH_RISK" -> "Riesgo alto"; "UNSUPPORTED" -> "No ejecutable"; "IMPORTED_NOT_RUNNABLE" -> "Importada · No compatible con runtime cooperativo"; else -> level }

    private fun runtimeModeLabel(pkg: VirtualPackageEntity): String = pkg.runtimeMode

    private fun trimPolicyLabel(name: String): String = when (name) { "STOP_PAUSED" -> "Sesiones pausadas cerradas por presión de memoria"; else -> name.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() } }

    private fun boolLabel(value: Boolean): String = if (value) "Activado" else "Desactivado"

    private fun openPolicyLabel(policy: com.valcrono.core.OpenAnotherAppPolicy): String = when (policy.name) { "ASK" -> "Preguntar"; "PAUSE_CURRENT" -> "Pausar la aplicación actual"; "STOP_CURRENT" -> "Detener la aplicación actual"; "BLOCK" -> "Impedir apertura"; else -> policy.name }

    private fun sessionStateLabel(state: SessionState): String = when (state) { SessionState.STOPPED -> "Detenida"; SessionState.STARTING -> "Iniciando"; SessionState.ACTIVE -> "Activa"; SessionState.PAUSED -> "Pausada"; SessionState.STOPPING -> "Deteniendo"; SessionState.ERROR -> "Error al iniciar"; SessionState.SAVING -> "Guardando" }

    private fun MemoryProfile.displayName(): String = when (this) {
        MemoryProfile.AUTOMATIC -> "Automático"
        MemoryProfile.LOW_POWER -> "Bajo consumo"
        MemoryProfile.BALANCED -> "Equilibrado"
        MemoryProfile.HIGH_PERFORMANCE -> "Alto rendimiento"
        MemoryProfile.CUSTOM -> "Personalizado"
    }

    private fun SettingAvailability.label(): String = when (this) {
        SettingAvailability.AVAILABLE -> "Disponible"
        SettingAvailability.EXPERIMENTAL -> "Experimental"
        SettingAvailability.NOT_IMPLEMENTED -> "No implementada"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.2f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun date(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private val appMenuLabels = listOf(
        "Información",
        "Permisos",
        "Ajustes de la aplicación",
        "Exportar",
        "Restaurar",
        "Limpiar caché",
        "Borrar datos",
        "Actualizar APK",
        "Desinstalar",
        "Diagnóstico",
        "Instalar fuera de VirtualSpace",
    )
}
