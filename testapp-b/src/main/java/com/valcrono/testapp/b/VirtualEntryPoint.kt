package com.valcrono.testapp.b

import com.valcrono.runtime.*

class VirtualEntryPoint : VirtualAppEntryPoint {
    private lateinit var env: VirtualAppEnvironment
    private val messages = linkedSetOf<String>()

    override fun onCreate(environment: VirtualAppEnvironment) {
        env = environment
        messages += env.files.readText("files/received.txt")?.lines().orEmpty().filter { it.isNotBlank() }
        env.messages.pending().forEach { onVirtualMessage(it); env.messages.markDelivered(it.id) }
    }

    override fun createContent(): VirtualContent = screen("B cargada desde APK importado")

    override fun onVirtualMessage(message: VirtualMessage): VirtualContent {
        messages += "${message.id}:${message.fromPackage}: ${message.payload}"
        env.files.writeText("files/received.txt", messages.joinToString("\n"))
        env.preferences.putString("messages", "last", message.payload)
        env.database.execute("b.db", "CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT, payload TEXT)")
        env.database.execute("b.db", "INSERT INTO messages(sender,payload) VALUES(?,?)", listOf(message.fromPackage, message.payload))
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
