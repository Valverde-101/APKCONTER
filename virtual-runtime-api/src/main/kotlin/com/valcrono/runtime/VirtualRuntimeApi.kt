package com.valcrono.runtime

interface VirtualAppEntryPoint {
    fun onCreate(environment: VirtualAppEnvironment)
    fun createContent(): VirtualContent
    fun onAction(actionId: String): VirtualContent = createContent()
    fun onStart() {}
    fun onResume() {}
    fun onPause() {}
    fun onStop() {}
    fun onDestroy() {}
    fun onVirtualMessage(message: VirtualMessage): VirtualContent = createContent()
}

data class VirtualAppMetadata(val virtualUserId: Int, val packageName: String, val label: String, val versionName: String)

data class VirtualMessage(val id: Long = 0, val fromPackage: String, val toPackage: String, val type: String, val payload: String, val state: String = "PENDING")

interface VirtualLogger { fun info(message: String); fun error(message: String, throwable: Throwable? = null) }
interface VirtualFileApi { fun writeText(relativePath: String, text: String); fun readText(relativePath: String): String?; fun list(relativeDir: String = ""): List<String> }
interface VirtualPreferencesApi { fun putString(name: String, key: String, value: String); fun getString(name: String, key: String, defaultValue: String? = null): String?; fun putInt(name: String, key: String, value: Int); fun getInt(name: String, key: String, defaultValue: Int = 0): Int }
interface VirtualDatabaseApi { fun execute(database: String, sql: String, args: List<String> = emptyList()); fun query(database: String, sql: String, args: List<String> = emptyList(), limit: Int = 100): List<Map<String, String?>>; fun listDatabases(): List<String> }
interface VirtualMessageApi { fun send(toPackage: String, type: String, payload: String): Long; fun pending(): List<VirtualMessage>; fun markDelivered(messageId: Long) }
interface VirtualAppEnvironment { val metadata: VirtualAppMetadata; val files: VirtualFileApi; val preferences: VirtualPreferencesApi; val database: VirtualDatabaseApi; val messages: VirtualMessageApi; val logger: VirtualLogger }

sealed interface VirtualContent {
    data class Text(val text: String) : VirtualContent
    data class Button(val text: String, val actionId: String) : VirtualContent
    data class Column(val children: List<VirtualContent>) : VirtualContent
    data class ListContent(val title: String, val rows: List<String>) : VirtualContent
}
