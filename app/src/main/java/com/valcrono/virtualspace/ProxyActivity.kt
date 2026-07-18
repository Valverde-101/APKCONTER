package com.valcrono.virtualspace

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.valcrono.core.VLog
import com.valcrono.runtime.VirtualContent
import kotlinx.coroutines.runBlocking

abstract class BaseRuntimeProxyActivity : Activity() {
    abstract val slotId: RuntimeSlotId
    private lateinit var running: RunningVirtualApp
    private lateinit var container: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var sessionId: String = ""
    private var attemptId: String = ""
    private val heartbeat = object : Runnable { override fun run() { sendHeartbeat(if (hasWindowFocus()) "ACTIVE_FOREGROUND" else "ACTIVE_BACKGROUND"); handler.postDelayed(this, 3_000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(container) })
        val userId = intent.getIntExtra("virtualUserId", -1)
        val packageName = intent.getStringExtra("virtualPackageName").orEmpty()
        val activity = intent.getStringExtra("virtualActivityName").orEmpty()
        sessionId = intent.getStringExtra("sessionId").orEmpty(); attemptId = intent.getStringExtra("launchAttemptId").orEmpty()
        val token = intent.getStringExtra("launchToken").orEmpty()
        require(intent.getStringExtra("slotId") == slotId.name) { "SLOT_MISMATCH" }
        logLaunch("PROXY_${slotId.name}_ON_CREATE", sessionId, attemptId, token, packageName, userId, null, null)
        try {
            val repository = VirtualRepository(applicationContext)
            val now = System.currentTimeMillis()
            val persistedToken = runBlocking { repository.db.launchTokens().getFresh(token, now) }
            val session = runBlocking { repository.db.runtime().get(sessionId) }
            val slot = runBlocking { repository.db.runtimeSlots().get(slotId.name) }
            require(persistedToken != null) { "LAUNCH_TOKEN_INVALID" }
            require(persistedToken.sessionId == sessionId && persistedToken.launchAttemptId == attemptId && persistedToken.virtualUserId == userId && persistedToken.packageName == packageName && persistedToken.activityName == activity) { "LAUNCH_TOKEN_MISMATCH" }
            require(session?.state == "STARTING" && session.currentLaunchAttemptId == attemptId) { "LAUNCH_ATTEMPT_NOT_CURRENT state=${session?.state} attempt=${session?.currentLaunchAttemptId}" }
            require(slot?.sessionId == sessionId && slot.launchAttemptId == attemptId) { "SLOT_NOT_RESERVED" }
            runBlocking { repository.db.launchTokens().consume(token, System.currentTimeMillis()) }
            heartbeat(repository, "TOKEN_VALIDATED")
            val pkg = runBlocking { repository.getPackage(packageName, userId) } ?: error("PACKAGE_NOT_REGISTERED")
            require(pkg.enabled && !pkg.damaged) { "PACKAGE_DISABLED_OR_DAMAGED" }
            require(runBlocking { repository.verifyPackage(pkg) }) { "APK_HASH_MISMATCH" }
            heartbeat(repository, "APK_VERIFIED")
            running = VirtualRuntimeHost(this, repository).start(pkg)
            heartbeat(repository, "CLASSLOADER_LOADED")
            render(running.entry.createContent())
            val mem = currentSlotMemorySnapshot()
            val changed = runBlocking {
                repository.db.runtime().acknowledgeActive(sessionId, attemptId, System.currentTimeMillis(), mem.pid)
                repository.db.runtimeSlots().acknowledgeStarted(slotId.name, sessionId, attemptId, mem.pid, "ACTIVE_FOREGROUND", mem.pssBytes, System.currentTimeMillis())
            }
            require(changed > 0) { "ACTIVE_ACK_REJECTED" }
            handler.post(heartbeat)
            logLaunch("SESSION_ACTIVE_ACK_${slotId.name}_PID_${mem.pid}", sessionId, attemptId, token, packageName, userId, "STARTING", "ACTIVE_FOREGROUND")
        } catch (t: Throwable) {
            VLog.e("RuntimeProxy", "runtime launch failed slot=$slotId package=$packageName error=${t.message}", t)
            val code = launchErrorCode(t)
            runCatching { runBlocking { val now2=System.currentTimeMillis(); VirtualRepository(applicationContext).db.runtime().compareAndSetState(sessionId, attemptId, "ERROR", "FAILED", now2, code, sanitizeLaunchError(t)); VirtualRepository(applicationContext).db.runtimeSlots().markCrashed(slotId.name, code, sanitizeLaunchError(t), now2) } }
            renderError(sanitizeLaunchError(t))
        }
    }

    private fun heartbeat(repository: VirtualRepository, phase: String) { runBlocking { repository.db.runtime().heartbeat(sessionId, attemptId, phase, System.currentTimeMillis()) } }
    private fun sendHeartbeat(state: String) { runCatching { val repo=VirtualRepository(applicationContext); val mem=currentSlotMemorySnapshot(); runBlocking { repo.db.runtimeSlots().heartbeat(slotId.name, sessionId, mem.pid, state, mem.pssBytes, mem.timestamp) } } }
    private fun render(content: VirtualContent) { container.removeAllViews(); addContent(container, content) }
    private fun addContent(parent: LinearLayout, content: VirtualContent) { when (content) { is VirtualContent.Text -> parent.addView(TextView(this).apply { text = content.text; textSize = 16f }); is VirtualContent.Button -> parent.addView(Button(this).apply { text = content.text; setOnClickListener { runAction(content.actionId) } }); is VirtualContent.Column -> content.children.forEach { addContent(parent, it) }; is VirtualContent.ListContent -> { parent.addView(TextView(this).apply { text = content.title; textSize = 18f }); content.rows.forEach { parent.addView(TextView(this).apply { text = "• $it" }) } } } }
    private fun runAction(actionId: String) { try { render(running.entry.onAction(actionId)) } catch (t: Throwable) { VLog.e("RuntimeProxy", "RUNTIME_CRASHED action=$actionId", t); runCatching { runBlocking { VirtualRepository(applicationContext).db.runtimeSlots().markCrashed(slotId.name, "RUNTIME_CRASHED", t.message, System.currentTimeMillis()) } }; renderError("RUNTIME_CRASHED: ${t.message}") } }
    private fun renderError(message: String) { container.removeAllViews(); container.addView(TextView(this).apply { text = message }) }
    private fun launchErrorCode(t: Throwable): String = listOf("SLOT_MISMATCH","LAUNCH_TOKEN_INVALID","LAUNCH_TOKEN_MISMATCH","LAUNCH_ATTEMPT_NOT_CURRENT","SLOT_NOT_RESERVED","ACTIVE_ACK_REJECTED","PACKAGE_NOT_REGISTERED","PACKAGE_DISABLED_OR_DAMAGED","APK_HASH_MISMATCH","ENTRY_POINT_NOT_DECLARED","ENTRY_POINT_CLASS_NOT_FOUND","ENTRY_POINT_INTERFACE_MISMATCH").firstOrNull { t.message.orEmpty().contains(it) } ?: "RUNTIME_INITIALIZATION_FAILED"
    private fun sanitizeLaunchError(t: Throwable): String = (t.message ?: "No se pudo inicializar el runtime.").take(500)
    override fun onStart() { super.onStart(); if (::running.isInitialized) runCatching { running.entry.onStart() } }
    override fun onResume() { super.onResume(); if (::running.isInitialized) { runCatching { running.entry.onResume() }; sendHeartbeat("ACTIVE_FOREGROUND") } }
    override fun onPause() { if (::running.isInitialized) { runCatching { running.entry.onPause() }; sendHeartbeat("ACTIVE_BACKGROUND") }; super.onPause() }
    override fun onStop() { if (::running.isInitialized) { runCatching { running.entry.onStop() }; sendHeartbeat("ACTIVE_BACKGROUND") }; super.onStop() }
    override fun onDestroy() { handler.removeCallbacks(heartbeat); if (::running.isInitialized) runCatching { running.entry.onDestroy() }; if (isFinishing && !isChangingConfigurations) runCatching { runBlocking { VirtualRepository(applicationContext).db.runtimeSlots().release(slotId.name, System.currentTimeMillis()) } }; super.onDestroy() }
}
class RuntimeProxyActivity0 : BaseRuntimeProxyActivity() { override val slotId = RuntimeSlotId.VAPP0 }
class RuntimeProxyActivity1 : BaseRuntimeProxyActivity() { override val slotId = RuntimeSlotId.VAPP1 }
