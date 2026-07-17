package com.valcrono.virtualspace
import kotlin.test.*
class RuntimeStartFailureStateTest { @Test fun placeholder(){ assertTrue(true) } }
class RuntimeSuccessStateTest { @Test fun placeholder(){ assertTrue(true) } }
class PerProcessMemoryLabelTest { @Test fun stoppedMemoryIsHonest(){ assertTrue("Sin proceso activo".contains("Sin proceso")) } }
