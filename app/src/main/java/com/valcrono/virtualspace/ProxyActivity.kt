package com.valcrono.virtualspace

import com.valcrono.virtualspace.runtime.HostTaskSupervisorService

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Process
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import com.valcrono.runtime.VirtualContent

abstract class BaseRuntimeProxyActivity : Activity() {
    var genericContext: GenericPackageContext? = null
    var genericApplication: android.app.Application? = null
    var genericGuestActivity: Activity? = null
    val genericPhases: MutableList<String> = mutableListOf()
    abstract val slotId: RuntimeSlotId
    private lateinit var container: LinearLayout
    private var sessionId: String = ""
    private var binder: RuntimeProcessService.RuntimeBinder? = null
    private var isBound = false
    private var isBinding = false
    private var boundSessionId: String? = null
    private val activityInstanceId = UUID.randomUUID().toString()
    private val handler = Handler(Looper.getMainLooper())
    private val deathRecipient = IBinder.DeathRecipient {
        val sid = sessionId
        handler.post { binder = null; isBound = false; isBinding = false; boundSessionId = null; renderError("PROCESS_LOST: binder muerto") }
        if (sid.isNotBlank()) CoroutineScope(Dispatchers.IO).launch {
            DatabaseProvider.get(applicationContext).runtimeSlots().findBySession(sid)?.let { RuntimeSlotReclaimer(DatabaseProvider.get(applicationContext)).reclaimSlot(it.slotId, sid, it.launchAttemptId, it.reservationToken, RuntimeReclaimReason.BINDER_DIED) }
        }
    }
    private val uiCallback = object : RuntimeProcessService.RuntimeUiCallback {
        override fun onContentChanged(sessionId: String, version: Long, content: VirtualContent) {
            handler.post { if (this@BaseRuntimeProxyActivity.sessionId == sessionId) render(content) }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? RuntimeProcessService.RuntimeBinder
            runCatching { service?.linkToDeath(deathRecipient, 0) }
            isBound = true; isBinding = false; boundSessionId = sessionId
            recordActivityAttached()
            val content = binder?.attachUi(sessionId, uiCallback)
            if (content?.result?.success == true) { if (content.content != null) render(content.content) else attachGuestView() } else renderError(content?.result?.sanitizedMessage ?: "PROCESS_LOST")
        }
        override fun onServiceDisconnected(name: ComponentName?) { val sid = boundSessionId; binder = null; isBound = false; isBinding = false; boundSessionId = null; if (sid != null) CoroutineScope(Dispatchers.IO).launch { DatabaseProvider.get(applicationContext).runtimeSlots().findBySession(sid)?.let { RuntimeSlotReclaimer(DatabaseProvider.get(applicationContext)).reclaimSlot(it.slotId, sid, it.launchAttemptId, it.reservationToken, RuntimeReclaimReason.BINDER_DIED) } }; renderError("PROCESS_LOST: servicio desconectado") }
        override fun onBindingDied(name: ComponentName?) { onServiceDisconnected(name) }
        override fun onNullBinding(name: ComponentName?) { onServiceDisconnected(name) }
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
        val nextSessionId = intent.getStringExtra("sessionId").orEmpty()
        if (binder != null && boundSessionId == nextSessionId) {
            sessionId = nextSessionId
            recordActivityAttached()
            val content = binder?.attachUi(sessionId, uiCallback)
            if (content?.result?.success == true) { if (content.content != null) render(content.content) else attachGuestView() } else renderError(content?.result?.sanitizedMessage ?: "PROCESS_LOST")
            return
        }
        if (isBinding && sessionId == nextSessionId) return
        if (isBound && boundSessionId != nextSessionId) { runCatching { unbindService(connection) }; isBound = false; isBinding = false; binder = null; boundSessionId = null }
        sessionId = nextSessionId
        val openMode = intent.getStringExtra("openMode") ?: "COLD_START"
        val serviceIntent = Intent(this, serviceFor(slotId))
        if (openMode != "WARM_RESUME") {
            val request = RuntimeLaunchRequest(intent.getIntExtra("virtualUserId", -1), intent.getStringExtra("virtualPackageName").orEmpty(), intent.getStringExtra("virtualActivityName").orEmpty(), sessionId, intent.getStringExtra("launchAttemptId").orEmpty(), intent.getStringExtra("launchToken").orEmpty(), slotId.name, intent.getStringExtra("reservationToken").orEmpty(), intent.getLongExtra("runtimeGeneration", -1L), intent.getLongExtra("slotEpoch", -1L))
            serviceIntent.putExtra("runtimeLaunchRequest", request)
            startService(serviceIntent)
        }
        isBinding = true
        try {
            val bound = bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) { isBinding = false; renderError("RECOVER_BIND_FAILED slot=${slotId.name} sessionId=$sessionId pid=${Process.myPid()}") }
        } catch (t: SecurityException) {
            isBinding = false; renderError("RECOVER_BIND_FAILED slot=${slotId.name} sessionId=$sessionId pid=${Process.myPid()}: ${t.message}")
        } catch (t: IllegalArgumentException) {
            isBinding = false; renderError("RECOVER_BIND_FAILED slot=${slotId.name} sessionId=$sessionId pid=${Process.myPid()}: ${t.message}")
        }
    }

    private fun recordActivityAttached() { if (sessionId.isNotBlank()) CoroutineScope(Dispatchers.IO).launch { runCatching { DatabaseProvider.get(applicationContext).runtimeSlots().markActivityAttached(slotId.name, sessionId, taskId, activityInstanceId, System.currentTimeMillis(), Process.myPid()) } } }
    private fun recordActivityDetached() { if (sessionId.isNotBlank()) CoroutineScope(Dispatchers.IO).launch { runCatching { DatabaseProvider.get(applicationContext).runtimeSlots().markActivityDetached(slotId.name, sessionId, activityInstanceId) } } }
    private fun render(content: VirtualContent) { container.removeAllViews(); addContent(container, content) }
    private fun attachGuestView() { val view = binder?.attachGuestView(sessionId, this); if (view != null) { container.removeAllViews(); (view.parent as? android.view.ViewGroup)?.removeView(view); setContentView(view) } else renderError("GENERIC_GUEST_VIEW_UNAVAILABLE") }
    private fun addContent(parent: LinearLayout, content: VirtualContent) { when (content) { is VirtualContent.Text -> parent.addView(TextView(this).apply { text = content.text; textSize = 16f }); is VirtualContent.Button -> parent.addView(Button(this).apply { text = content.text; setOnClickListener { runAction(content.actionId) } }); is VirtualContent.Column -> content.children.forEach { addContent(parent, it) }; is VirtualContent.ListContent -> { parent.addView(TextView(this).apply { text = content.title; textSize = 18f }); content.rows.forEach { parent.addView(TextView(this).apply { text = "• $it" }) } } } }
    private fun runAction(actionId: String) { val result = binder?.dispatchAction(sessionId, actionId); if (result?.result?.success == true && result.content != null) render(result.content) else renderError(result?.result?.sanitizedMessage ?: "RUNTIME_ACTION_FAILED") }
    private fun renderError(message: String) { container.removeAllViews(); container.addView(TextView(this).apply { text = message }) }
    override fun onStart() { super.onStart(); recordActivityAttached(); binder?.bringToForeground(sessionId) }
    override fun onResume() { super.onResume(); recordActivityAttached(); HostTaskSupervisorService.registerVirtualTask(this, sessionId, slotId, taskId); binder?.resumeSession(sessionId) }
    override fun onPause() { binder?.detachUi(sessionId); super.onPause() }
    override fun onStop() { recordActivityDetached(); super.onStop() }
    override fun onDestroy() { runCatching { binder?.unlinkToDeath(deathRecipient, 0) }; if (isBound || isBinding) runCatching { unbindService(connection) }; isBound = false; isBinding = false; boundSessionId = null; binder = null; super.onDestroy() }
}

fun serviceFor(slotId: RuntimeSlotId): Class<out android.app.Service> = when (slotId) { RuntimeSlotId.VAPP0 -> RuntimeSlot0Service::class.java; RuntimeSlotId.VAPP1 -> RuntimeSlot1Service::class.java }
class RuntimeProxyActivity0 : BaseRuntimeProxyActivity() { override val slotId = RuntimeSlotId.VAPP0 }
class RuntimeProxyActivity1 : BaseRuntimeProxyActivity() { override val slotId = RuntimeSlotId.VAPP1 }
