package com.valcrono.compat

import java.io.File

object Android15Diagnostics {
    fun pageSize(): Long = runCatching {
        Runtime.getRuntime().exec(arrayOf("getconf", "PAGESIZE")).inputStream.bufferedReader().use { it.readText().trim().toLong() }
    }.getOrDefault(4096L)

    fun summary(): String = buildString {
        append("SDK=Android host ")
        append("abi=${System.getProperty("os.arch")} ")
        append("page=${pageSize()} ")
        append("free=${File(".").freeSpace}")
    }
}
