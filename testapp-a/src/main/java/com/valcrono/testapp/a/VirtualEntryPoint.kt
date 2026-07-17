package com.valcrono.testapp.a

import com.valcrono.runtime.*

class VirtualEntryPoint : VirtualAppEntryPoint {
    private lateinit var env: VirtualAppEnvironment
    private var counter = 0
    private var lastStatus = "Inicializado"

    override fun onCreate(environment: VirtualAppEnvironment) {
        env = environment
        counter = env.preferences.getInt("state", "counter", 0)
        lastStatus = "A cargada desde APK importado"
        env.logger.info(lastStatus)
    }

    override fun createContent(): VirtualContent = screen()

    override fun onAction(actionId: String): VirtualContent {
        when (actionId) {
            "increment" -> { counter++; env.preferences.putInt("state", "counter", counter); lastStatus = "Contador=$counter" }
            "save_file" -> { env.files.writeText("files/a-file.txt", "counter=$counter"); lastStatus = "Archivo creado por TestApp A" }
            "save_preferences" -> { env.preferences.putString("prefs", "last", "counter=$counter"); lastStatus = "Preferencias guardadas por TestApp A" }
            "create_database" -> {
                env.database.execute("a.db", "CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY AUTOINCREMENT, msg TEXT)")
                env.database.execute("a.db", "INSERT INTO events(msg) VALUES(?)", listOf("counter=$counter"))
                lastStatus = "SQLite actualizado por TestApp A: ${env.database.query("a.db", "SELECT * FROM events").size} filas"
            }
            "send_to_b" -> { val id = env.messages.send("com.valcrono.testapp.b", "text", "Hola desde A contador=$counter"); lastStatus = "Mensaje enviado a B id=$id" }
            else -> lastStatus = "Acción desconocida: $actionId"
        }
        return screen()
    }

    override fun onVirtualMessage(message: VirtualMessage): VirtualContent { lastStatus = "A recibió ${message.payload}"; return screen() }

    private fun screen() = VirtualContent.Column(listOf(
        VirtualContent.Text("Test App A"),
        VirtualContent.Text("Paquete: ${env.metadata.packageName}"),
        VirtualContent.Text("Contador: $counter"),
        VirtualContent.Text(lastStatus),
        VirtualContent.Button("Incrementar", "increment"),
        VirtualContent.Button("Guardar archivo", "save_file"),
        VirtualContent.Button("Guardar preferencias", "save_preferences"),
        VirtualContent.Button("Crear SQLite", "create_database"),
        VirtualContent.Button("Enviar a B", "send_to_b"),
        VirtualContent.ListContent("Archivos", env.files.list("files")),
    ))
}
