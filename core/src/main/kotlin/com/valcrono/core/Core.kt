package com.valcrono.core

import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant

data class OperationResult<T>(val value:T?=null,val error:String?=null){ val ok get()=error==null; companion object{ fun <T> ok(v:T)=OperationResult(v,null); fun <T> fail(e:String)=OperationResult<T>(null,e) } }
object VLog { private val lines=ArrayDeque<String>(); @Synchronized fun i(tag:String,msg:String){ add("I/$tag ${Instant.now()} $msg")}; @Synchronized fun e(tag:String,msg:String,t:Throwable?=null){ add("E/$tag ${Instant.now()} $msg ${t?.javaClass?.simpleName ?: ""}")}; @Synchronized private fun add(s:String){ if(lines.size>300) lines.removeFirst(); lines.addLast(s)}; @Synchronized fun recent()=lines.toList() }
object Sha256 { fun hex(input:InputStream):String{ val md=MessageDigest.getInstance("SHA-256"); val buf=ByteArray(8192); while(true){ val n=input.read(buf); if(n<0) break; if(n>0) md.update(buf,0,n)}; return md.digest().joinToString(""){"%02x".format(it)} } }
interface Clock { fun now():Long; object System:Clock{ override fun now()=java.lang.System.currentTimeMillis() } }
