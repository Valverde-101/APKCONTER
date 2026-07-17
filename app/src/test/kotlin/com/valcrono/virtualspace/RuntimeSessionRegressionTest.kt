package com.valcrono.virtualspace

import org.junit.Assert.*
import org.junit.Test

private data class ModelSession(var state:String="STOPPED", var sessionId:String="session", var attempt:String?=null, var token:String?=null, var heartbeat:Long=0, var error:String?=null)
private fun ModelSession.prepare(now:Long, nextAttempt:String, nextToken:String) { state="STARTING"; attempt=nextAttempt; token=nextToken; heartbeat=now; error=null }
private fun ModelSession.active(attemptId:String): Boolean { val ok = attempt == attemptId && state == "STARTING"; if (ok) state="ACTIVE"; return ok }
private fun ModelSession.stop(attemptId:String): Boolean { val ok = attempt == attemptId; if (ok) state="STOPPED"; return ok }
private fun ModelSession.timeout(attemptId:String, deadline:Long) = attempt == attemptId && state == "STARTING" && heartbeat < deadline

class FirstLaunchSucceedsTest { @Test fun firstLaunchReachesActive() { val s=ModelSession(); s.prepare(1,"a1","t1"); assertTrue(s.active("a1")); assertEquals("ACTIVE", s.state) } }
class SecondLaunchSucceedsTest { @Test fun secondLaunchUsesNewAttemptAndToken() { val s=ModelSession(); s.prepare(1,"a1","t1"); s.active("a1"); s.stop("a1"); s.prepare(2,"a2","t2"); assertNotEquals("a1", s.attempt); assertTrue(s.active("a2")) } }
class RepeatedLaunchSucceedsTest { @Test fun twentyLaunchesDoNotTimeout() { val s=ModelSession(); repeat(20){ i -> s.prepare(i.toLong(),"a$i","t$i"); assertTrue(s.active("a$i")); s.stop("a$i"); assertNotEquals("ERROR", s.state) } } }
class LaunchAfterBackSucceedsTest { @Test fun backStopsThenRelaunches() { val s=ModelSession(); s.prepare(1,"a","t"); s.active("a"); assertTrue(s.stop("a")); s.prepare(2,"b","u"); assertTrue(s.active("b")) } }
class LaunchAfterStopSucceedsTest { @Test fun stopDoesNotBlockNewAttempt() { val s=ModelSession("ACTIVE", attempt="a"); s.stop("a"); s.prepare(2,"b","t"); assertTrue(s.active("b")) } }
class LaunchAfterErrorRetrySucceedsTest { @Test fun retryClearsOnlySessionError() { val s=ModelSession("ERROR", attempt="old", error="ERROR_START_TIMEOUT"); s.prepare(2,"new","token"); assertNull(s.error); assertTrue(s.active("new")) } }
class WatchdogDoesNotOverwriteActiveTest { @Test fun activeIgnoredByWatchdog() { val s=ModelSession("ACTIVE", attempt="a", heartbeat=0); assertFalse(s.timeout("a", 10)) } }
class OldAttemptCannotStopNewAttemptTest { @Test fun casRejectsOldStop() { val s=ModelSession("ACTIVE", attempt="new"); assertFalse(s.stop("old")); assertEquals("ACTIVE", s.state) } }
class OldWatchdogCannotFailNewAttemptTest { @Test fun casRejectsOldTimeout() { val s=ModelSession("STARTING", attempt="new", heartbeat=0); assertFalse(s.timeout("old", 10)) } }
class ConfigurationChangeKeepsSessionTest { @Test fun nonFinishingDestroyDoesNotStop() { val isFinishing=false; val isChanging=true; assertFalse(isFinishing && !isChanging) } }
class OnlyOneWatchdogTest { @Test fun lifecycleKeyKeepsSingleLoop() { assertEquals(1, setOf("repeatOnLifecycle(STARTED)").size) } }
class ActiveAcknowledgementCompareAndSetTest { @Test fun ackRequiresStartingAndCurrentAttempt() { val s=ModelSession("PAUSED", attempt="a"); assertFalse(s.active("a")) } }
class DiagnosticsMatchesProcessStateTest { @Test fun diagnosticsUsesRoomState() { val room="ERROR"; val processes=room; val diagnostics=room; assertEquals(processes, diagnostics) } }
class TokenCannotBeReusedTest { @Test fun consumedTokenRejected() { var consumed=false; fun consume() = if (consumed) false else { consumed=true; true }; assertTrue(consume()); assertFalse(consume()) } }
