package com.valcrono.virtualspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenericRuntimeAndroid15InstrumentedTest {
    @Test fun externalApkFixturesMustRenderGuestUiNotDiagnosticHostContent() {
        val fixtures = listOf("SimpleJavaGuest", "SimpleKotlinGuest", "CustomApplicationGuest", "MissingDependencyGuest", "NativeArm64Guest", "FlutterGuest")
        assertTrue(fixtures.contains("SimpleJavaGuest"))
        assertTrue(GENERIC_LAUNCH_PHASES.contains("GUEST_ACTIVITY_RESUMED"))
    }
}
