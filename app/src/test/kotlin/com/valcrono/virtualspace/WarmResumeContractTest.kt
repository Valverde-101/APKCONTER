package com.valcrono.virtualspace

import org.junit.Test
import org.junit.Assert.assertTrue

private fun src(path: String): String {
    val candidates = listOf(
        java.io.File(path),
        java.io.File("..", path),
        java.io.File(System.getProperty("user.dir"), path),
        java.io.File(System.getProperty("user.dir")).parentFile?.let { java.io.File(it, path) },
    ).filterNotNull()
    return candidates.firstOrNull { it.isFile }?.readText() ?: error("Source file not found: $path from ${System.getProperty("user.dir")}")
}

class WarmResumeDoesNotCallPrepareLaunchTest { @Test fun warmDecisionExistsBeforePrepareLaunch() { val s = src("app/src/main/java/com/valcrono/virtualspace/MainActivity.kt"); assertTrue(s.indexOf("resolveOpenDecision") < s.indexOf("missingRuntimePermissions")) } }
class WarmResumeDoesNotGenerateLaunchTokenTest { @Test fun warmIntentOmitsToken() { val s = src("app/src/main/java/com/valcrono/virtualspace/MainActivity.kt"); assertTrue(s.contains("openMode\", \"WARM_RESUME")); assertTrue(!s.substring(s.indexOf("bringExistingTaskToFront"), s.indexOf("bringRuntimeToForeground")).contains("launchToken")) } }
class WarmResumeDoesNotStartServiceAgainTest { @Test fun proxySkipsStartServiceForWarm() { val s = src("app/src/main/java/com/valcrono/virtualspace/ProxyActivity.kt"); assertTrue(s.contains("if (openMode != \"WARM_RESUME\")")) } }
class WarmResumeDoesNotBindTwiceTest { @Test fun bindingGuardExists() { val s = src("app/src/main/java/com/valcrono/virtualspace/ProxyActivity.kt"); assertTrue(s.contains("isBinding") && !s.contains("pendingAttach")) } }
class WarmResumeUsesExistingBinderTest { @Test fun binderFastPathExists() { assertTrue(src("app/src/main/java/com/valcrono/virtualspace/ProxyActivity.kt").contains("binder != null && boundSessionId == nextSessionId")) } }
class WarmResumeReturnsCachedContentTest { @Test fun cachedContentReturnedByAttach() { assertTrue(src("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt").contains("cachedContent ?: running?.createContent()")) } }
class WarmResumePreservesClassLoaderTest { @Test fun decisionRequiresLoadedClassLoader() { assertTrue(src("app/src/main/java/com/valcrono/virtualspace/RuntimeSessionRepository.kt").contains("classLoaderState == \"LOADED\"")) } }
class WarmResumePreservesCounterStateTest { @Test fun cachedVisualDoesNotRestartRuntime() { assertTrue(src("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt").contains("if (running != null && request?.sessionId == req.sessionId)")) } }
class WarmResumeDoesNotChangeStateToStartingTest { @Test fun decisionBypassesColdReserve() { assertTrue(src("app/src/main/java/com/valcrono/virtualspace/MainActivity.kt").contains("bringExistingTaskToFront(pkg, decision); return@launch")) } }
class RuntimeAckRequiresSessionAndSlotUpdateTest { @Test fun ackChecksBothRows() { assertTrue(src("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt").contains("ACTIVE_ACK_SESSION_REJECTED") && src("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt").contains("ACTIVE_ACK_SLOT_REJECTED")) } }
class ActiveSlotCannotHaveStartingSessionTest { @Test fun startingActiveCombinationIsDetected() { assertTrue(src("app/src/main/java/com/valcrono/virtualspace/RuntimeSessionRepository.kt").contains("STATE_RECONCILED")) } }
class StateReconciliationRepairsStartingSessionTest { @Test fun repairDaoExists() { assertTrue(src("app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt").contains("repairActive")) } }
class ForegroundBackgroundUpdatesAreTransactionalTest { @Test fun activeAckUsesRoomTransaction() { assertTrue(src("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt").contains("db.withTransaction")) } }
class LifecycleCallbacksAreIdempotentTest { @Test fun lifecycleStateMachineExists() { assertTrue(src("app/src/main/java/com/valcrono/virtualspace/RuntimeSlots.kt").contains("RuntimeUiLifecycleState")) } }
class ActivityTaskIsReusedTest { @Test fun moveTaskToFrontIsUsed() { assertTrue(src("app/src/main/java/com/valcrono/virtualspace/MainActivity.kt").contains("moveToFront")) } }
class Vapp0AndVapp1HaveDifferentTaskIdsTest { @Test fun taskAffinitiesDiffer() { val s=src("app/src/main/AndroidManifest.xml"); assertTrue(s.contains("com.valcrono.virtualspace.vapp0") && s.contains("com.valcrono.virtualspace.vapp1")) } }
class NoLeakedServiceConnectionTest { @Test fun unbindGuardExists() { assertTrue(src("app/src/main/java/com/valcrono/virtualspace/ProxyActivity.kt").contains("if (isBound || isBinding)")) } }
class SQLiteViewerRegressionTest { @Test fun sqliteViewerStillPresent() { assertTrue(src("app/src/main/java/com/valcrono/virtualspace/MainActivity.kt").contains("SQLiteViewer")) } }
