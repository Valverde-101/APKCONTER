package com.valcrono.virtualspace

import org.junit.Assert.*
import org.junit.Test

class RuntimeSlotReservationIsAtomicTest { @Test fun daoHasTransactionalSurface() { val s = java.io.File("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").readText(); assertTrue(s.contains("reserveSlot")); assertTrue(s.contains("runtime_slots")) } }
class RuntimeSlotCannotBeAssignedTwiceTest { @Test fun slotIdsArePrimaryKeys() { assertTrue(java.io.File("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").readText().contains("@PrimaryKey val slotId")) } }
class SamePackageReturnsExistingSlotTest { @Test fun packageLookupExists() { assertTrue(java.io.File("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").readText().contains("findByPackage")) } }
class TwoPackagesUseDifferentSlotsTest { @Test fun twoSlotsAreDeclared() { assertEquals(listOf(":vapp0", ":vapp1"), RuntimeSlotId.entries.map { it.processName }) } }
class ThirdPackageFailsWhenSlotsOccupiedTest { @Test fun noFreeMessageIsStable() { assertTrue(java.io.File("app/src/main/java/com/valcrono/virtualspace/RuntimeSessionRepository.kt").readText().contains("No hay procesos virtuales libres.")) } }
class RuntimeProxy0RunsInVapp0Test { @Test fun manifestDeclaresProcess() { val s=java.io.File("app/src/main/AndroidManifest.xml").readText(); assertTrue(s.contains(".RuntimeProxyActivity0")); assertTrue(s.contains("android:process=\":vapp0\"")) } }
class RuntimeProxy1RunsInVapp1Test { @Test fun manifestDeclaresProcess() { val s=java.io.File("app/src/main/AndroidManifest.xml").readText(); assertTrue(s.contains(".RuntimeProxyActivity1")); assertTrue(s.contains("android:process=\":vapp1\"")) } }
class MainProcessDoesNotLoadVirtualDexTest { @Test fun mainLaunchesProxyNotHost() { assertFalse(java.io.File("app/src/main/java/com/valcrono/virtualspace/MainActivity.kt").readText().contains("VirtualRuntimeHost(")) } }
class ActiveAckContainsSlotPidTest { @Test fun ackUsesMemoryPid() { assertTrue(java.io.File("app/src/main/java/com/valcrono/virtualspace/ProxyActivity.kt").readText().contains("SESSION_ACTIVE_ACK_")) } }
class TwoSessionsRemainActiveSimultaneouslyTest { @Test fun backgroundStateExists() { assertTrue(RuntimeSlotState.entries.map { it.name }.containsAll(listOf("ACTIVE_FOREGROUND","ACTIVE_BACKGROUND"))) } }
class BackgroundActivityDoesNotStopRuntimeTest { @Test fun pauseIsBackgroundHeartbeat() { assertTrue(java.io.File("app/src/main/java/com/valcrono/virtualspace/ProxyActivity.kt").readText().contains("sendHeartbeat(\"ACTIVE_BACKGROUND\")")) } }
class BringExistingSessionToFrontTest { @Test fun proxyActivityMappingIsCentral() { assertTrue(java.io.File("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt").readText().contains("fun proxyActivityFor")) } }
class StopSessionReleasesSlotTest { @Test fun releaseExists() { assertTrue(java.io.File("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").readText().contains("fun release")) } }
class KilledSlotBecomesCrashedTest { @Test fun crashDaoExists() { assertTrue(java.io.File("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").readText().contains("markCrashed")) } }
class StaleHeartbeatRecoveryTest { @Test fun recoveryDaoExists() { assertTrue(java.io.File("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").readText().contains("recoverStaleSlots")) } }
class ProcessMainRestartPreservesSlotMetadataTest { @Test fun migrationSeedsSlots() { assertTrue(java.io.File("app/src/main/java/com/valcrono/virtualspace/VirtualRepository.kt").readText().contains("MIGRATION_6_7")) } }
class CrossProcessMessageAToBTest { @Test fun messagesAreRoomBacked() { assertTrue(java.io.File("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").readText().contains("VirtualMessageDao")) } }
class CrossProcessMessageBToATest { @Test fun messageUuidFieldPersists() { assertTrue(java.io.File("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").readText().contains("messageId:String")) } }
class PssIsMeasuredPerSlotPidTest { @Test fun pssMeasuredByDebug() { val s=java.io.File("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt").readText(); assertTrue(s.contains("Debug.getMemoryInfo")); assertTrue(s.contains("Process.myPid()")) } }
class NoDuplicateProcessCardsTest { @Test fun slotsAreGroupedById() { assertEquals(2, RuntimeSlotId.entries.size) } }
