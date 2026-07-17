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
        val token = intent.getStringExtra("launchToken").orEmpty()
        try {
            val repository = VirtualRepository(applicationContext)
            val persistedToken = runBlocking { repository.db.launchTokens().getFresh(token) }
            require(persistedToken != null && persistedToken.virtualUserId == userId && persistedToken.packageName == packageName && persistedToken.activityName == activity) { "LAUNCH_TOKEN_INVALID" }
            runBlocking { repository.db.launchTokens().consume(token, System.currentTimeMillis()) }
            runBlocking { repository.db.runtime().timeoutStarting(System.currentTimeMillis() - 15_000, System.currentTimeMillis()) }
            val pkg = runBlocking { repository.getPackage(packageName, userId) } ?: error("PACKAGE_NOT_REGISTERED")
            require(pkg.enabled && !pkg.damaged) { "PACKAGE_DISABLED_OR_DAMAGED" }
            require(runBlocking { repository.verifyPackage(pkg) }) { "APK_HASH_MISMATCH" }
            running = VirtualRuntimeHost(this, repository).start(pkg)
            render(running.entry.createContent())
            runBlocking { repository.db.runtime().updateState(token, "ACTIVE", System.currentTimeMillis(), null, null) }
            VLog.i("ProxyActivity", "started cooperative runtime package=$packageName entry=${pkg.entryPointClass}")
        } catch (t: Throwable) {
            VLog.e("ProxyActivity", "runtime launch failed package=$packageName error=${t.message}", t)
            runCatching { runBlocking { VirtualRepository(applicationContext).db.runtime().updateState(token, "ERROR", System.currentTimeMillis(), "RUNTIME_INITIALIZATION_FAILED", (t.message ?: "RUNTIME_INITIALIZATION_FAILED").take(500)) } }
            renderError(t.message ?: "RUNTIME_INITIALIZATION_FAILED")
        }
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
    override fun onStart() { super.onStart(); if (::running.isInitialized) runCatching { running.entry.onStart() } }
    override fun onResume() { super.onResume(); if (::running.isInitialized) runCatching { running.entry.onResume() } }
    override fun onPause() { if (::running.isInitialized) runCatching { running.entry.onPause() }; super.onPause() }
    override fun onStop() { if (::running.isInitialized) runCatching { running.entry.onStop() }; super.onStop() }
    override fun onDestroy() { if (::running.isInitialized) runCatching { running.entry.onDestroy() }; runCatching { runBlocking { VirtualRepository(applicationContext).db.runtime().updateState(intent.getStringExtra("launchToken").orEmpty(), "STOPPED", System.currentTimeMillis(), null, null) } }; super.onDestroy() }
}
