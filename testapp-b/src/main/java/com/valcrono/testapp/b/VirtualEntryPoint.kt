package com.valcrono.testapp.b

import com.valcrono.runtime.*

class VirtualEntryPoint : VirtualAppEntryPoint {
    private lateinit var env: VirtualAppEnvironment
    private val messages = mutableListOf<String>()

    override fun onCreate(environment: VirtualAppEnvironment) {
        env = environment
        messages += env.files.readText("files/received.txt")?.lines().orEmpty().filter { it.isNotBlank() }
        env.messages.pending().forEach { onVirtualMessage(it); env.messages.markDelivered(it.id) }
    }

    override fun createContent(): VirtualContent = screen("B cargada desde APK importado")

    override fun onVirtualMessage(message: VirtualMessage): VirtualContent {
        messages += "${message.fromPackage}: ${message.payload}"
        env.files.writeText("files/received.txt", messages.joinToString("\n"))
        env.preferences.putString("messages", "last", message.payload)
        env.database.execute("b.db", "CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT, payload TEXT)")
        env.database.execute("b.db", "INSERT INTO messages(sender,payload) VALUES(?,?)", listOf(message.fromPackage, message.payload))
        return screen("Mensaje recibido y guardado")
    }

    override fun onAction(actionId: String): VirtualContent = screen("Acción $actionId")

    private fun screen(status: String) = VirtualContent.Column(listOf(
        VirtualContent.Text("Test App B"),
        VirtualContent.Text(status),
        VirtualContent.ListContent("Mensajes", messages),
    ))
}
