package com.valcrono.vproc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualIntentRouterTest {
    @Test fun routesExplicit() {
        val router = VirtualIntentRouter { setOf("com.b" to "com.b.Main") }
        assertEquals("com.b.Main", router.resolve(VirtualIntent("com.a", "com.b", "com.b.Main", mapOf("m" to "hi"), 0)))
    }

    @Test fun rejectsUnknown() {
        val router = VirtualIntentRouter { emptySet() }
        assertThrows(IllegalArgumentException::class.java) {
            router.resolve(VirtualIntent("com.a", "com.b", "com.b.Main", emptyMap(), 0))
        }
    }

    @Test fun launchTokensAreSingleUseAndBoundToTarget() {
        var now = 1000L
        val store = LaunchTokenStore { now }
        val token = store.create(0, "com.a", "com.a.Main", ttlMillis = 100)
        assertFalse(store.consume(token, 0, "com.b", "com.a.Main"))
        val token2 = store.create(0, "com.a", "com.a.Main", ttlMillis = 100)
        assertTrue(store.consume(token2, 0, "com.a", "com.a.Main"))
        assertFalse(store.consume(token2, 0, "com.a", "com.a.Main"))
        val token3 = store.create(0, "com.a", "com.a.Main", ttlMillis = 100)
        now = 2000L
        assertFalse(store.consume(token3, 0, "com.a", "com.a.Main"))
    }
}
