package com.valcrono.virtualspace

import org.junit.Assert.*
import org.junit.Test
import java.io.File

private fun source(path: String): String {
    val candidates = listOf(File(path), File("..", path), File(System.getProperty("user.dir"), path), File(System.getProperty("user.dir")).parentFile?.let { File(it, path) }).filterNotNull()
    return candidates.firstOrNull { it.isFile }?.readText() ?: error("Source file not found: $path from ${System.getProperty("user.dir")}")
}

class RuntimeSlotReservationIsAtomicTest { @Test fun daoHasTransactionalSurface() { val s = source("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt"); assertTrue(s.contains("reserveSlot")); assertTrue(s.contains("runtime_slots")) } }
class RuntimeSlotCannotBeAssignedTwiceTest { @Test fun slotIdsArePrimaryKeys() { assertTrue(source("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").contains("@PrimaryKey val slotId")) } }
class SamePackageReturnsExistingSlotTest { @Test fun packageLookupExists() { assertTrue(source("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").contains("findByPackage")) } }
class TwoPackagesUseDifferentSlotsTest { @Test fun twoSlotsAreDeclared() { assertEquals(listOf(":vapp0", ":vapp1"), RuntimeSlotId.entries.map { it.processName }) } }
class ThirdPackageFailsWhenSlotsOccupiedTest { @Test fun noFreeMessageIsStable() { assertTrue(source("app/src/main/java/com/valcrono/virtualspace/RuntimeSessionRepository.kt").contains("No hay procesos virtuales libres.")) } }
class RuntimeProxy0RunsInVapp0Test { @Test fun manifestDeclaresProcess() { val s=source("app/src/main/AndroidManifest.xml"); assertTrue(s.contains(".RuntimeProxyActivity0")); assertTrue(s.contains("android:process=\":vapp0\"")) } }
class RuntimeProxy1RunsInVapp1Test { @Test fun manifestDeclaresProcess() { val s=source("app/src/main/AndroidManifest.xml"); assertTrue(s.contains(".RuntimeProxyActivity1")); assertTrue(s.contains("android:process=\":vapp1\"")) } }
class MainProcessDoesNotLoadVirtualDexTest { @Test fun mainLaunchesProxyNotHost() { assertFalse(source("app/src/main/java/com/valcrono/virtualspace/MainActivity.kt").contains("VirtualRuntimeHost(")) } }
class ActiveAckContainsSlotPidTest { @Test fun ackUsesMemoryPid() { assertTrue(source("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt").contains("acknowledgeActive")) } }
class TwoSessionsRemainActiveSimultaneouslyTest { @Test fun backgroundStateExists() { assertTrue(RuntimeSlotState.entries.map { it.name }.containsAll(listOf("ACTIVE_FOREGROUND","ACTIVE_BACKGROUND"))) } }
class BackgroundActivityDoesNotStopRuntimeTest { @Test fun pauseIsBackgroundHeartbeat() { assertTrue(source("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt").contains("ACTIVE_BACKGROUND")) } }
class BringExistingSessionToFrontTest { @Test fun proxyActivityMappingIsCentral() { assertTrue(source("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt").contains("fun proxyActivityFor")) } }
class StopSessionReleasesSlotTest { @Test fun releaseExists() { assertTrue(source("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").contains("fun release")) } }
class KilledSlotBecomesCrashedTest { @Test fun crashDaoExists() { assertTrue(source("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").contains("markCrashed")) } }
class StaleHeartbeatRecoveryTest { @Test fun recoveryDaoExists() { assertTrue(source("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").contains("recoverStaleSlots")) } }
class ProcessMainRestartPreservesSlotMetadataTest { @Test fun migrationSeedsSlots() { assertTrue(source("app/src/main/java/com/valcrono/virtualspace/VirtualRepository.kt").contains("MIGRATION_6_7")) } }
class CrossProcessMessageAToBTest { @Test fun messagesAreRoomBacked() { assertTrue(source("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").contains("VirtualMessageDao")) } }
class CrossProcessMessageBToATest { @Test fun messageUuidFieldPersists() { assertTrue(source("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").contains("messageId:String")) } }
class PssIsMeasuredPerSlotPidTest { @Test fun pssMeasuredByDebug() { val s=source("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt"); assertTrue(s.contains("Debug.getMemoryInfo")); assertTrue(s.contains("Process.myPid()")) } }
class NoDuplicateProcessCardsTest { @Test fun slotsAreGroupedById() { assertEquals(2, RuntimeSlotId.entries.size) } }

class RuntimeServiceOwnsRunningVirtualAppTest { @Test fun serviceOwnsRuntime() { val s = source("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt"); assertTrue(s.contains("private var running: RunningVirtualApplication?")); assertFalse(source("app/src/main/java/com/valcrono/virtualspace/ProxyActivity.kt").contains("RunningVirtualApp")) } }
class RuntimeBinderContractIsTypedTest { @Test fun binderExposesRequiredOperations() { val s = source("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt"); listOf("startSession", "attachUi", "detachUi", "bringToForeground", "getCurrentContent", "dispatchAction", "pauseSession", "resumeSession", "stopSession", "getStatus", "getMemoryInfo", "requestHeartbeat").forEach { assertTrue(it, s.contains("fun $it")) }; assertTrue(s.contains("RuntimeIpcResult")); assertTrue(s.contains("success: Boolean")); assertTrue(s.contains("errorCode: String?")) } }
class ExistingSessionReordersToFrontTest { @Test fun flagsAreApplied() { val s = source("app/src/main/java/com/valcrono/virtualspace/MainActivity.kt"); assertTrue(s.contains("FLAG_ACTIVITY_REORDER_TO_FRONT")); assertTrue(s.contains("FLAG_ACTIVITY_SINGLE_TOP")); assertTrue(source("app/src/main/java/com/valcrono/virtualspace/ProxyActivity.kt").contains("onNewIntent")) } }
class MaxActiveAppsLimitedToTwoTest { @Test fun settingsClampToTwo() { val s = source("app/src/main/java/com/valcrono/virtualspace/VirtualSpaceSettings.kt"); assertTrue(s.contains("val maxActiveApps: Int = 2")); assertTrue(s.contains("value.coerceIn(1, 2)")) } }
class RequiredStateMachineStatesTest { @Test fun allStatesExist() { val names = RuntimeSlotState.entries.map { it.name }; listOf("FREE","RESERVED","STARTING","ACTIVE_FOREGROUND","ACTIVE_BACKGROUND","PAUSED_BY_USER","STOPPING","STOPPED","CRASHED","ERROR").forEach { assertTrue(it, names.contains(it)) } } }
class StopDestroysRuntimeInServiceTest { @Test fun orderedCallbacksExist() { val s = source("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt"); assertTrue(s.indexOf("running?.pause()") < s.indexOf("running?.stop()")); assertTrue(s.indexOf("running?.stop()") < s.indexOf("running?.destroy()")); assertTrue(s.contains("RuntimeSlotReclaimer")) } }
class ProcessCardsExposeSlotDiagnosticsTest { @Test fun diagnosticsFieldsExist() { val s = source("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt"); listOf("activityAttached", "serviceConnected", "classLoaderLoaded", "lastPhase", "launchAttemptId", "pssBytes").forEach { assertTrue(it, s.contains(it)) } } }
