package com.valcrono.virtualspace

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.valcrono.runtime.VirtualContent

abstract class BaseRuntimeProxyActivity : Activity() {
    abstract val slotId: RuntimeSlotId
    private lateinit var container: LinearLayout
    private var sessionId: String = ""
    private var binder: RuntimeProcessService.RuntimeBinder? = null
    private val handler = Handler(Looper.getMainLooper())
    private val heartbeatTick = object : Runnable { override fun run() { binder?.requestHeartbeat(); handler.postDelayed(this, 3_000) } }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? RuntimeProcessService.RuntimeBinder
            val content = binder?.attachUi(sessionId)
            if (content?.result?.success == true && content.content != null) render(content.content) else renderError(content?.result?.sanitizedMessage ?: "PROCESS_LOST")
            handler.post(heartbeatTick)
        }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null; renderError("PROCESS_LOST: servicio desconectado") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(container) })
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

    private fun handleIntent(intent: Intent) {
        require(intent.getStringExtra("slotId") == slotId.name) { "SLOT_MISMATCH" }
        sessionId = intent.getStringExtra("sessionId").orEmpty()
        val request = RuntimeLaunchRequest(
            intent.getIntExtra("virtualUserId", -1),
            intent.getStringExtra("virtualPackageName").orEmpty(),
            intent.getStringExtra("virtualActivityName").orEmpty(),
            sessionId,
            intent.getStringExtra("launchAttemptId").orEmpty(),
            intent.getStringExtra("launchToken").orEmpty(),
            slotId.name,
        )
        val serviceIntent = Intent(this, serviceFor(slotId)).putExtra("runtimeLaunchRequest", request)
        startService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun render(content: VirtualContent) { container.removeAllViews(); addContent(container, content) }
    private fun addContent(parent: LinearLayout, content: VirtualContent) { when (content) { is VirtualContent.Text -> parent.addView(TextView(this).apply { text = content.text; textSize = 16f }); is VirtualContent.Button -> parent.addView(Button(this).apply { text = content.text; setOnClickListener { runAction(content.actionId) } }); is VirtualContent.Column -> content.children.forEach { addContent(parent, it) }; is VirtualContent.ListContent -> { parent.addView(TextView(this).apply { text = content.title; textSize = 18f }); content.rows.forEach { parent.addView(TextView(this).apply { text = "• $it" }) } } } }
    private fun runAction(actionId: String) { val result = binder?.dispatchAction(sessionId, actionId); if (result?.result?.success == true && result.content != null) render(result.content) else renderError(result?.result?.sanitizedMessage ?: "RUNTIME_ACTION_FAILED") }
    private fun renderError(message: String) { container.removeAllViews(); container.addView(TextView(this).apply { text = message }) }
    override fun onStart() { super.onStart(); binder?.bringToForeground(sessionId) }
    override fun onResume() { super.onResume(); binder?.resumeSession(sessionId) }
    override fun onPause() { binder?.detachUi(sessionId); super.onPause() }
    override fun onDestroy() { handler.removeCallbacks(heartbeatTick); runCatching { unbindService(connection) }; binder = null; super.onDestroy() }
}

fun serviceFor(slotId: RuntimeSlotId): Class<out android.app.Service> = when (slotId) { RuntimeSlotId.VAPP0 -> RuntimeSlot0Service::class.java; RuntimeSlotId.VAPP1 -> RuntimeSlot1Service::class.java }
class RuntimeProxyActivity0 : BaseRuntimeProxyActivity() { override val slotId = RuntimeSlotId.VAPP0 }
class RuntimeProxyActivity1 : BaseRuntimeProxyActivity() { override val slotId = RuntimeSlotId.VAPP1 }
