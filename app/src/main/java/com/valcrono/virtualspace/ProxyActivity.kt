package com.valcrono.virtualspace

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.valcrono.core.VLog
import com.valcrono.runtime.VirtualContent
import kotlinx.coroutines.runBlocking

class ProxyActivity : Activity() {
    private lateinit var running: RunningVirtualApp
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(container) })
        val userId = intent.getIntExtra("virtualUserId", -1)
        val packageName = intent.getStringExtra("virtualPackageName").orEmpty()
        val activity = intent.getStringExtra("virtualActivityName").orEmpty()
        val sessionId = intent.getStringExtra("sessionId").orEmpty()
        val attemptId = intent.getStringExtra("launchAttemptId").orEmpty()
        val token = intent.getStringExtra("launchToken").orEmpty()
        logLaunch("PROXY_ON_CREATE", sessionId, attemptId, token, packageName, userId, null, null)
        try {
            val repository = VirtualRepository(applicationContext)
            val now = System.currentTimeMillis()
            val persistedToken = runBlocking { repository.db.launchTokens().getFresh(token, now) }
            val session = runBlocking { repository.db.runtime().get(sessionId) }
            require(persistedToken != null) { "LAUNCH_TOKEN_INVALID" }
            require(persistedToken.sessionId == sessionId && persistedToken.launchAttemptId == attemptId && persistedToken.virtualUserId == userId && persistedToken.packageName == packageName && persistedToken.activityName == activity) { "LAUNCH_TOKEN_MISMATCH" }
            require(session?.state == "STARTING" && session.currentLaunchAttemptId == attemptId) { "LAUNCH_ATTEMPT_NOT_CURRENT state=${session?.state} attempt=${session?.currentLaunchAttemptId}" }
            runBlocking { repository.db.launchTokens().consume(token, System.currentTimeMillis()) }
            heartbeat(repository, "TOKEN_VALIDATED")
            val pkg = runBlocking { repository.getPackage(packageName, userId) } ?: error("PACKAGE_NOT_REGISTERED")
            require(pkg.enabled && !pkg.damaged) { "PACKAGE_DISABLED_OR_DAMAGED" }
            require(runBlocking { repository.verifyPackage(pkg) }) { "APK_HASH_MISMATCH" }
            heartbeat(repository, "APK_VERIFIED")
            heartbeat(repository, "CLASSLOADER_CREATED")
            running = VirtualRuntimeHost(this, repository).start(pkg)
            heartbeat(repository, "ENTRYPOINT_CREATED")
            render(running.entry.createContent())
            heartbeat(repository, "CONTENT_RENDERED")
            val changed = runBlocking { repository.db.runtime().acknowledgeActive(sessionId, attemptId, System.currentTimeMillis(), android.os.Process.myPid()) }
            if (changed == 0) { val current = runBlocking { repository.db.runtime().get(sessionId) }; logLaunch("ACTIVE_ACK_REJECTED", sessionId, attemptId, token, packageName, userId, "STARTING", current?.state); error("ACTIVE_ACK_REJECTED state=${current?.state} attempt=${current?.currentLaunchAttemptId}") }
            logLaunch("SESSION_ACTIVE_ACK", sessionId, attemptId, token, packageName, userId, "STARTING", "ACTIVE")
            VLog.i("ProxyActivity", "started cooperative runtime package=$packageName entry=${pkg.entryPointClass}")
        } catch (t: Throwable) {
            VLog.e("ProxyActivity", "runtime launch failed package=$packageName error=${t.message}", t)
            val code = launchErrorCode(t)
            runCatching { runBlocking { VirtualRepository(applicationContext).db.runtime().compareAndSetState(sessionId, attemptId, "ERROR", "FAILED", System.currentTimeMillis(), code, sanitizeLaunchError(t)) } }
            renderError(sanitizeLaunchError(t))
        }
    }

    private fun heartbeat(repository: VirtualRepository, phase: String) {
        logLaunch(phase, intent.getStringExtra("sessionId"), intent.getStringExtra("launchAttemptId"), intent.getStringExtra("launchToken"), intent.getStringExtra("virtualPackageName"), intent.getIntExtra("virtualUserId", -1), "STARTING", "STARTING")
        runBlocking { repository.db.runtime().heartbeat(intent.getStringExtra("sessionId").orEmpty(), intent.getStringExtra("launchAttemptId").orEmpty(), phase, System.currentTimeMillis()) }
    }

    private fun render(content: VirtualContent) {
        container.removeAllViews()
        addContent(container, content)
    }

    private fun addContent(parent: LinearLayout, content: VirtualContent) {
        when (content) {
            is VirtualContent.Text -> parent.addView(TextView(this).apply { text = content.text; textSize = 16f })
            is VirtualContent.Button -> parent.addView(Button(this).apply { text = content.text; setOnClickListener { runAction(content.actionId) } })
            is VirtualContent.Column -> content.children.forEach { addContent(parent, it) }
            is VirtualContent.ListContent -> {
                parent.addView(TextView(this).apply { text = content.title; textSize = 18f })
                content.rows.forEach { parent.addView(TextView(this).apply { text = "• $it" }) }
            }
        }
    }

    private fun runAction(actionId: String) {
        try { render(running.entry.onAction(actionId)) } catch (t: Throwable) { VLog.e("ProxyActivity", "RUNTIME_CRASHED action=$actionId", t); renderError("RUNTIME_CRASHED: ${t.message}") }
    }

    private fun renderError(message: String) { container.removeAllViews(); container.addView(TextView(this).apply { text = message }) }
    private fun launchErrorCode(t: Throwable): String {
        val message = t.message.orEmpty()
        return listOf("LAUNCH_TOKEN_INVALID", "LAUNCH_TOKEN_MISMATCH", "LAUNCH_ATTEMPT_NOT_CURRENT", "ACTIVE_ACK_REJECTED", "PACKAGE_NOT_REGISTERED", "PACKAGE_DISABLED_OR_DAMAGED", "APK_HASH_MISMATCH", "ENTRY_POINT_NOT_DECLARED", "ENTRY_POINT_CLASS_NOT_FOUND", "ENTRY_POINT_INTERFACE_MISMATCH").firstOrNull { message.contains(it) } ?: "RUNTIME_INITIALIZATION_FAILED"
    }
    private fun sanitizeLaunchError(t: Throwable): String = when (launchErrorCode(t)) {
        "LAUNCH_TOKEN_INVALID" -> "Token de inicio inválido o vencido. Vuelve a abrir la app."
        "PACKAGE_NOT_REGISTERED" -> "La APK no está registrada en VirtualSpace."
        "PACKAGE_DISABLED_OR_DAMAGED" -> "La APK está deshabilitada o marcada como dañada."
        "APK_HASH_MISMATCH" -> "El hash del APK no coincide con el importado."
        "ENTRY_POINT_NOT_DECLARED" -> "La APK no declara entry point cooperativo."
        "ENTRY_POINT_CLASS_NOT_FOUND" -> "No se encontró la clase entry point."
        "ENTRY_POINT_INTERFACE_MISMATCH" -> "El entry point no implementa la interfaz requerida."
        else -> (t.message ?: "No se pudo inicializar el runtime.").take(500)
    }
    override fun onStart() { super.onStart(); logLaunch("PROXY_ON_START", intent.getStringExtra("sessionId"), intent.getStringExtra("launchAttemptId"), intent.getStringExtra("launchToken"), intent.getStringExtra("virtualPackageName"), intent.getIntExtra("virtualUserId", -1), null, null); if (::running.isInitialized) runCatching { running.entry.onStart() } }
    override fun onResume() { super.onResume(); logLaunch("PROXY_ON_RESUME", intent.getStringExtra("sessionId"), intent.getStringExtra("launchAttemptId"), intent.getStringExtra("launchToken"), intent.getStringExtra("virtualPackageName"), intent.getIntExtra("virtualUserId", -1), null, null); if (::running.isInitialized) { runCatching { running.entry.onResume() } } }
    override fun onPause() { logLaunch("PROXY_ON_PAUSE", intent.getStringExtra("sessionId"), intent.getStringExtra("launchAttemptId"), intent.getStringExtra("launchToken"), intent.getStringExtra("virtualPackageName"), intent.getIntExtra("virtualUserId", -1), null, null); if (::running.isInitialized) { runCatching { running.entry.onPause() }; if (!isChangingConfigurations && !isFinishing) runCatching { runBlocking { VirtualRepository(applicationContext).db.runtime().compareAndSetState(intent.getStringExtra("sessionId").orEmpty(), intent.getStringExtra("launchAttemptId").orEmpty(), "PAUSED", "PAUSED", System.currentTimeMillis(), null, null) } } }; super.onPause() }
    override fun onStop() { logLaunch("PROXY_ON_STOP", intent.getStringExtra("sessionId"), intent.getStringExtra("launchAttemptId"), intent.getStringExtra("launchToken"), intent.getStringExtra("virtualPackageName"), intent.getIntExtra("virtualUserId", -1), null, null); if (::running.isInitialized) runCatching { running.entry.onStop() }; super.onStop() }
    override fun onDestroy() {
        logLaunch("PROXY_ON_DESTROY", intent.getStringExtra("sessionId"), intent.getStringExtra("launchAttemptId"), intent.getStringExtra("launchToken"), intent.getStringExtra("virtualPackageName"), intent.getIntExtra("virtualUserId", -1), null, null)
        if (::running.isInitialized) runCatching { running.entry.onDestroy() }
        if (::running.isInitialized && isFinishing && !isChangingConfigurations) {
            runCatching { runBlocking { VirtualRepository(applicationContext).db.runtime().compareAndSetState(intent.getStringExtra("sessionId").orEmpty(), intent.getStringExtra("launchAttemptId").orEmpty(), "STOPPED", "STOPPED", System.currentTimeMillis(), null, null) } }
        }
        super.onDestroy()
    }
}
