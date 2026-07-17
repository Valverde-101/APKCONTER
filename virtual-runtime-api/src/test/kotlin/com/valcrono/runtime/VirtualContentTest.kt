package com.valcrono.runtime
import org.junit.Assert.assertEquals
import org.junit.Test
class VirtualContentTest { @Test fun contentIsStable(){ assertEquals("x", (VirtualContent.Text("x")).text) } }
