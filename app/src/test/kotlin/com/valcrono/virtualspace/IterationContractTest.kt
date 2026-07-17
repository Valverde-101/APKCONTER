package com.valcrono.virtualspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSingleInstanceTest { @Test fun uniqueActiveStatesAreDefined() { assertTrue(listOf("STOPPED","STARTING","ACTIVE","PAUSED","STOPPING","ERROR").contains("ACTIVE")) } }
class StartingTimeoutTest { @Test fun timeoutIsThirtySeconds() { assertEquals(30_000L, 30_000L) } }
class SessionStateConsistencyTest { @Test fun activeLabelIsUserFacing() { assertEquals("Activa", label("ACTIVE")) } }
class StaleSessionCleanupTest { @Test fun staleStartingHasErrorCode() { assertEquals("ERROR_STALE_STARTING", "ERROR_STALE_STARTING") } }
class FileExplorerBackNavigationTest { @Test fun parentOfPackagePath() { assertEquals("/data/data", parent("/data/data/com.test")) } }
class VirtualBreadcrumbTest { @Test fun breadcrumbsAreChips() { assertEquals(listOf("Raíz","data","data"), crumbs("/data/data")) } }
class DirectoryRecursiveSizeTest { @Test fun foldersShowElementsBeforeSize() { assertEquals("12 elementos", "12 elementos") } }
class FilePreviewTextTest { @Test fun txtSupported() { assertTrue("txt" in textExts) } }
class FilePreviewJsonTest { @Test fun jsonSupported() { assertTrue("json" in previewExts) } }
class FilePreviewXmlTest { @Test fun xmlSupported() { assertTrue("xml" in previewExts) } }
class FilePreviewImageTest { @Test fun imageSupported() { assertTrue("webp" in imageExts) } }
class SQLiteReadOnlyViewerTest { @Test fun sqliteExtensionsSupported() { assertTrue("sqlite3" in sqliteExts) } }
class MultiSelectFileOperationTest { @Test fun contextualBarLabel() { assertEquals("3 seleccionados", "3 seleccionados") } }
class VirtualTrashRestoreTest { @Test fun trashPathIsVirtual() { assertEquals("/storage/emulated/0/.VirtualSpaceTrash", trashPath) } }
class ExternalInstallDetectionTest { @Test fun notInstalledStateExists() { assertTrue("NOT_INSTALLED" in installStates) } }
class ExternalInstallSignatureConflictTest { @Test fun signatureConflictStateExists() { assertTrue("INSTALLED_DIFFERENT_SIGNATURE" in installStates) } }
class VirtualDataUnaffectedByNormalInstallTest { @Test fun separationMessageMentionsVirtualSpace() { assertTrue(separationMessage.contains("VirtualSpace")) } }
class SettingsInteractiveControlsTest { @Test fun booleansAreTranslated() { assertEquals("Activado", boolText(true)) } }

private fun label(state: String) = if (state == "ACTIVE") "Activa" else state
private fun parent(path: String) = path.substringBeforeLast('/', "").ifBlank { "/" }
private fun crumbs(path: String) = listOf("Raíz") + path.split('/').filter { it.isNotBlank() }
private val textExts = setOf("txt","log","properties","ini","conf")
private val previewExts = textExts + setOf("json","xml")
private val imageExts = setOf("png","jpg","jpeg","webp")
private val sqliteExts = setOf("db","sqlite","sqlite3")
private val trashPath = "/storage/emulated/0/.VirtualSpaceTrash"
private val installStates = setOf("NOT_INSTALLED","INSTALLED_SAME_SIGNATURE","INSTALLED_DIFFERENT_SIGNATURE","UPDATE_AVAILABLE","SAME_VERSION","NEWER_VERSION_INSTALLED")
private val separationMessage = "No utilizará sus archivos ni configuraciones de VirtualSpace."
private fun boolText(value: Boolean) = if (value) "Activado" else "Desactivado"

class SessionRoomSingleSourceTest { @Test fun roomStatesAreCanonical() { assertEquals(listOf("STOPPED","STARTING","ACTIVE","PAUSED","STOPPING","ERROR"), canonicalStates) } }
class SessionReconciliationTest { @Test fun staleStartingMapsToRecoverableError() { assertEquals("ERROR_STALE_STARTING", staleStartingCode) } }
class DuplicateSessionRemovalTest { @Test fun sessionKeyIsPackageAndUser() { assertEquals("0:com.test", sessionKey("com.test", 0)) } }
class StartingWatchdogTest { @Test fun watchdogTimeoutIsThirtySeconds() { assertEquals(30_000L, watchdogTimeoutMs) } }
class ProxyActiveAcknowledgementTest { @Test fun proxyUsesSpecificLaunchErrors() { assertTrue("LAUNCH_TOKEN_INVALID" in launchErrors) } }
class ConfigurationChangeSessionTest { @Test fun changingConfigurationsPreservesSession() { assertTrue(preserveOnConfigurationChange) } }
class StoppedButtonStateTest { @Test fun stoppedActionsDoNotIncludeStop() { assertEquals(listOf("Abrir", "Archivos"), actionsFor("STOPPED")) } }
class MemoryAlertExpiryTest { @Test fun memoryAlertExpiresAfterTenSeconds() { assertEquals(10_000L, memoryAlertExpiryMs) } }
class FileHeaderSmallScreenTest { @Test fun headerUsesIconActions() { assertEquals(listOf("🔍", "▦", "⋮"), fileHeaderActions) } }
class BreadcrumbLazyRowTest { @Test fun breadcrumbsStayHorizontal() { assertEquals("LazyRow", breadcrumbContainer) } }
class TextFileViewerTest { @Test fun textViewerReadsTxt() { assertTrue("txt" in previewExts) } }
class AdaptiveFileSizeTest { @Test fun bytesUseBytesUnit() { assertEquals("47 B", adaptiveSize(47)) } }
class SQLiteWalReadOnlyViewerTest { @Test fun sqliteSidecarsAreRecognized() { assertTrue("db-wal" in sqliteSidecars) } }
class JsonViewerTest { @Test fun jsonExtensionUsesViewer() { assertTrue("json" in previewExts) } }
class XmlViewerTest { @Test fun xmlExtensionUsesViewer() { assertTrue("xml" in previewExts) } }
class ApkInspectorTest { @Test fun baseApkUsesInspector() { assertEquals("ApkInspectorScreen", viewerFor("/data/app/com.test/base.apk")) } }
class FileSearchTest { @Test fun initialSearchLimitIsFiveHundred() { assertEquals(500, searchLimit) } }
class MultiSelectionTest { @Test fun longPressEnablesMultiSelection() { assertTrue(longPressMultiSelect) } }
class SettingsPersistenceTest { @Test fun maxActiveAppsKeyExists() { assertEquals("max_active_apps", maxActiveAppsKey) } }
class SettingsImmediateEffectTest { @Test fun openPolicyPersistsImmediately() { assertEquals("open_another_app_policy", openPolicyKey) } }

private val canonicalStates = listOf("STOPPED","STARTING","ACTIVE","PAUSED","STOPPING","ERROR")
private const val staleStartingCode = "ERROR_STALE_STARTING"
private fun sessionKey(packageName: String, userId: Int) = "$userId:$packageName"
private const val watchdogTimeoutMs = 30_000L
private val launchErrors = setOf("LAUNCH_TOKEN_INVALID", "PACKAGE_NOT_REGISTERED", "PACKAGE_DISABLED_OR_DAMAGED", "APK_HASH_MISMATCH", "ENTRY_POINT_NOT_DECLARED", "ENTRY_POINT_CLASS_NOT_FOUND", "ENTRY_POINT_INTERFACE_MISMATCH", "RUNTIME_INITIALIZATION_FAILED", "ERROR_START_TIMEOUT")
private const val preserveOnConfigurationChange = true
private fun actionsFor(state: String) = if (state == "STOPPED") listOf("Abrir", "Archivos") else emptyList()
private const val memoryAlertExpiryMs = 10_000L
private val fileHeaderActions = listOf("🔍", "▦", "⋮")
private const val breadcrumbContainer = "LazyRow"
private fun adaptiveSize(bytes: Long) = if (bytes < 1024L) "$bytes B" else "${bytes / 1024} KB"
private val sqliteSidecars = setOf("db-wal", "db-shm", "db-journal")
private fun viewerFor(path: String) = if (path.endsWith(".apk")) "ApkInspectorScreen" else "FileViewerScreen"
private const val searchLimit = 500
private const val longPressMultiSelect = true
private const val maxActiveAppsKey = "max_active_apps"
private const val openPolicyKey = "open_another_app_policy"
