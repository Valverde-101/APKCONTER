package com.valcrono.vpm

import org.junit.Assert.*
import org.junit.Test

class MultidexNotDynamicCodeTest { @Test fun multidexWithEntryPointIsCooperative(){ val m=meta(entry="a.b.VirtualEntry", dynamic=false); val (level, issues)=CompatibilityScanner().scan(m); assertEquals(CompatibilityLevel.COOPERATIVE_SUPPORTED, level); assertFalse(issues.any{it.code=="DYNAMIC_CODE"}) } }
class AndroidXProviderDoesNotBlockCooperativeTest { @Test fun androidxProviderIsWarningNotBlocker(){ val m=meta(entry="a.b.VirtualEntry", components=listOf(VirtualComponent("a.b","androidx.startup.InitializationProvider",ComponentType.PROVIDER))); assertEquals(CompatibilityLevel.COOPERATIVE_SUPPORTED, CompatibilityScanner().scan(m).first) } }
class ExistingPackageRescanTest { @Test fun rescanKeepsEntryPointSupported(){ assertEquals(CompatibilityLevel.COOPERATIVE_SUPPORTED, CompatibilityScanner().scan(meta(entry="a.b.VirtualEntry")).first) } }
class NonCooperativeOpenDisabledTest { @Test fun noEntryPointIsNotCooperative(){ assertNotEquals(CompatibilityLevel.COOPERATIVE_SUPPORTED, CompatibilityScanner().scan(meta(entry=null)).first) } }

private fun meta(entry:String?, dynamic:Boolean=false, components:List<VirtualComponent> = emptyList()) = ApkMetadata("a.b","AB",1,"1",23,35,components, emptyList(), false,null,"a.b.Main", usesDynamicCodeLoading=dynamic, entryPointClass=entry, entryPointImplementsInterface=entry!=null, signingCertificateSha256="sha")
