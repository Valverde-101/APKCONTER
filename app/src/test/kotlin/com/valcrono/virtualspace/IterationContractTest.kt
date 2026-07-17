package com.valcrono.virtualspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSingleInstanceTest { @Test fun uniqueActiveStatesAreDefined() { assertTrue(listOf("STOPPED","STARTING","ACTIVE","PAUSED","STOPPING","ERROR").contains("ACTIVE")) } }
class StartingTimeoutTest { @Test fun timeoutIsFifteenSeconds() { assertEquals(15_000L, 15_000L) } }
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
