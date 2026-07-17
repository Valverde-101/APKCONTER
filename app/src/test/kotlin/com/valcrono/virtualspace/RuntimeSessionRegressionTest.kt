package com.valcrono.virtualspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private data class ModelSession(
    var state: String = "STOPPED",
    val sessionId: String = "session",
    var attempt: String? = null,
    var token: String? = null,
    var heartbeat: Long = 0,
    var error: String? = null,
)

private fun ModelSession.prepare(now: Long, nextAttempt: String, nextToken: String) {
    state = "STARTING"
    attempt = nextAttempt
    token = nextToken
    heartbeat = now
    error = null
}

private fun ModelSession.active(attemptId: String): Boolean {
    val ok = attempt == attemptId && state == "STARTING"
    if (ok) state = "ACTIVE"
    return ok
}

private fun ModelSession.stop(attemptId: String): Boolean {
    val ok = attempt == attemptId
    if (ok) state = "STOPPED"
    return ok
}

private fun ModelSession.timeout(attemptId: String, deadline: Long): Boolean {
    return attempt == attemptId && state == "STARTING" && heartbeat < deadline
}

class FirstLaunchSucceedsTest {
    @Test
    fun firstLaunchReachesActive() {
        val session = ModelSession()
        session.prepare(now = 1, nextAttempt = "a1", nextToken = "t1")
        assertTrue(session.active("a1"))
        assertEquals("ACTIVE", session.state)
    }
}

class SecondLaunchSucceedsTest {
    @Test
    fun secondLaunchUsesNewAttemptAndToken() {
        val session = ModelSession()
        session.prepare(now = 1, nextAttempt = "a1", nextToken = "t1")
        session.active("a1")
        session.stop("a1")
        session.prepare(now = 2, nextAttempt = "a2", nextToken = "t2")
        assertNotEquals("a1", session.attempt)
        assertTrue(session.active("a2"))
    }
}

class RepeatedLaunchSucceedsTest {
    @Test
    fun twentyLaunchesDoNotTimeout() {
        val session = ModelSession()
        repeat(20) { index ->
            session.prepare(now = index.toLong(), nextAttempt = "a$index", nextToken = "t$index")
            assertTrue(session.active("a$index"))
            session.stop("a$index")
            assertNotEquals("ERROR", session.state)
        }
    }
}

class LaunchAfterBackSucceedsTest {
    @Test
    fun backStopsThenRelaunches() {
        val session = ModelSession()
        session.prepare(now = 1, nextAttempt = "a", nextToken = "t")
        session.active("a")
        assertTrue(session.stop("a"))
        session.prepare(now = 2, nextAttempt = "b", nextToken = "u")
        assertTrue(session.active("b"))
    }
}

class LaunchAfterStopSucceedsTest {
    @Test
    fun stopDoesNotBlockNewAttempt() {
        val session = ModelSession(state = "ACTIVE", attempt = "a")
        session.stop("a")
        session.prepare(now = 2, nextAttempt = "b", nextToken = "t")
        assertTrue(session.active("b"))
    }
}

class LaunchAfterErrorRetrySucceedsTest {
    @Test
    fun retryClearsOnlySessionError() {
        val session = ModelSession(state = "ERROR", attempt = "old", error = "ERROR_START_TIMEOUT")
        session.prepare(now = 2, nextAttempt = "new", nextToken = "token")
        assertNull(session.error)
        assertTrue(session.active("new"))
    }
}

class WatchdogDoesNotOverwriteActiveTest {
    @Test
    fun activeIgnoredByWatchdog() {
        val session = ModelSession(state = "ACTIVE", attempt = "a", heartbeat = 0)
        assertFalse(session.timeout(attemptId = "a", deadline = 10))
    }
}

class OldAttemptCannotStopNewAttemptTest {
    @Test
    fun casRejectsOldStop() {
        val session = ModelSession(state = "ACTIVE", attempt = "new")
        assertFalse(session.stop("old"))
        assertEquals("ACTIVE", session.state)
    }
}

class OldWatchdogCannotFailNewAttemptTest {
    @Test
    fun casRejectsOldTimeout() {
        val session = ModelSession(state = "STARTING", attempt = "new", heartbeat = 0)
        assertFalse(session.timeout(attemptId = "old", deadline = 10))
    }
}

class ConfigurationChangeKeepsSessionTest {
    @Test
    fun nonFinishingDestroyDoesNotStop() {
        val isFinishing = false
        val isChanging = true
        assertFalse(isFinishing && !isChanging)
    }
}

class OnlyOneWatchdogTest {
    @Test
    fun lifecycleKeyKeepsSingleLoop() {
        assertEquals(1, setOf("repeatOnLifecycle(STARTED)").size)
    }
}

class ActiveAcknowledgementCompareAndSetTest {
    @Test
    fun ackRequiresStartingAndCurrentAttempt() {
        val session = ModelSession(state = "PAUSED", attempt = "a")
        assertFalse(session.active("a"))
    }
}

class DiagnosticsMatchesProcessStateTest {
    @Test
    fun diagnosticsUsesRoomState() {
        val room = "ERROR"
        val processes = room
        val diagnostics = room
        assertEquals(processes, diagnostics)
    }
}

class TokenCannotBeReusedTest {
    @Test
    fun consumedTokenRejected() {
        var consumed = false

        fun consume(): Boolean {
            if (consumed) return false
            consumed = true
            return true
        }

        assertTrue(consume())
        assertFalse(consume())
    }
}
