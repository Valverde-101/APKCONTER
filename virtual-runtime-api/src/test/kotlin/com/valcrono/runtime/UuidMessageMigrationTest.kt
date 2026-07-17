package com.valcrono.runtime
import org.junit.Assert.*
import org.junit.Test
class UuidMessageMigrationTest { @Test fun messageIdIsStringUuid(){ val id="a3f7c291-0000-4000-8000-000000000000"; val m=VirtualMessage(id,"a","b","text","hola"); assertEquals(id, m.id); assertEquals("a3f7c291…", m.id.take(8)+"…") } }
