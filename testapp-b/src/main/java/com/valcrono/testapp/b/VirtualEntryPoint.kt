package com.valcrono.testapp.b

import com.valcrono.runtime.*

class VirtualEntryPoint : VirtualAppEntryPoint {
    private lateinit var env: VirtualAppEnvironment
    private val messages = mutableListOf<String>()

    override fun onCreate(environment: VirtualAppEnvironment) {
        env = environment
        messages += env.files.readText("files/received.txt")?.lines().orEmpty().filter { it.isNotBlank() }
        env.database.execute("b.db", "CREATE TABLE IF NOT EXISTS messages(message_id TEXT PRIMARY KEY, sender TEXT NOT NULL, payload TEXT NOT NULL, received_at INTEGER NOT NULL)")
    }

    override fun createContent(): VirtualContent = screen("B cargada desde APK importado")

    override fun onVirtualMessage(message: VirtualMessage): VirtualContent {
        val row = "${message.id.take(8)} · ${message.fromPackage}: ${message.payload}"
        messages += row
        env.files.writeText("files/received.txt", messages.joinToString("\n"))
        env.preferences.putString("messages", "last", message.payload)
        env.database.execute("b.db", "CREATE TABLE IF NOT EXISTS messages(message_id TEXT PRIMARY KEY, sender TEXT NOT NULL, payload TEXT NOT NULL, received_at INTEGER NOT NULL)")
        env.database.execute("b.db", "INSERT OR IGNORE INTO messages(message_id,sender,payload,received_at) VALUES(?,?,?,?)", listOf(message.id, message.fromPackage, message.payload, System.currentTimeMillis().toString()))
        return screen("Mensaje recibido y guardado")
    }

    override fun onAction(actionId: String): VirtualContent { if (actionId == "clear_history") { messages.clear(); env.files.writeText("files/received.txt", ""); return screen("Historial borrado") }; return screen("Acción $actionId") }

    private fun screen(status: String) = VirtualContent.Column(listOf(
        VirtualContent.Text("Test App B"),
        VirtualContent.Text(status),
        VirtualContent.ListContent("Mensajes", messages.toList()),
        VirtualContent.Button("Borrar historial", "clear_history"),
    ))
}
