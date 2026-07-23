package com.valcrono.virtualspace.runtime

import com.valcrono.virtualspace.*

import android.app.*
import android.content.*
import android.os.*
import android.system.Os
import androidx.room.withTransaction
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class HostTaskSupervisorService : Service() {
    private val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var activityManager: ActivityManager
    private val globalShutdownStarted = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REGISTER_HOST -> prefs().edit()
                .putInt(KEY_HOST_TASK_ID, intent.getIntExtra(EXTRA_TASK_ID, -1))
                .putString(KEY_HOST_COMPONENT, intent.getStringExtra(EXTRA_COMPONENT))
                .putString(KEY_HOST_INSTANCE, intent.getStringExtra(EXTRA_HOST_INSTANCE))
                .apply()
            ACTION_REGISTER_VIRTUAL -> supervisorScope.launch {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                val slotId = intent.getStringExtra(EXTRA_SLOT_ID).orEmpty()
                val taskId = intent.getIntExtra(EXTRA_TASK_ID, -1)
                if (sessionId.isNotBlank() && slotId.isNotBlank() && taskId > 0) {
                    DatabaseProvider.get(applicationContext).runtimeSlots().markActivityAttached(slotId, sessionId, taskId, "supervisor", System.currentTimeMillis(), intent.getIntExtra(EXTRA_PID, -1))
                }
            }
        }
        supervisorScope.launch { monitorHostTask() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        supervisorScope.launch { verifyWhetherHostTaskWasRemoved("ON_TASK_REMOVED", rootIntent) }
    }

    private suspend fun verifyWhetherHostTaskWasRemoved(source: String, removedRootIntent: Intent?) {
        if (registeredHostTaskId() > 0) shutdownAllBecauseHostTaskWasRemoved(source)
    }

    private suspend fun monitorHostTask() {
        var missingChecks = 0
        while (hasVirtualSessions()) {
            val registeredHostTaskId = registeredHostTaskId()
            if (registeredHostTaskId <= 0) { delay(500); continue }
            val hostExists = activityManager.appTasks.any { appTask ->
                val info = runCatching { appTask.taskInfo }.getOrNull()
                taskInfoId(info) == registeredHostTaskId
            }
            if (hostExists) missingChecks = 0 else missingChecks++
            if (missingChecks >= 2) { shutdownAllBecauseHostTaskWasRemoved("MONITOR_HOST_TASK"); return }
            delay(500)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun hasVirtualSessions(): Boolean = DatabaseProvider.get(applicationContext).runtime().resumableSessions().isNotEmpty()

    suspend fun shutdownAllBecauseHostTaskWasRemoved(source: String = "HOST_REMOVED_FROM_RECENTS") {
        if (!globalShutdownStarted.compareAndSet(false, true)) return
        RuntimeHostRegistry.beginHostRecentsShutdown()
        val db = DatabaseProvider.get(applicationContext)
        val sessions = db.runtime().resumableSessions()
        val slots = db.runtimeSlots().getAll().filter { it.sessionId in sessions.map { s -> s.sessionId }.toSet() }
        removeVirtualTasks(slots)
        requestVirtualRuntimeShutdown(slots)
        waitForRuntimeDeaths(slots, 1_500)
        forceKillRemainingRuntimes(slots)
        waitForRuntimeDeaths(slots, 1_000)
        val now = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime()
        db.withTransaction {
            db.runtime().stopAllByHost(now)
            db.launchTokens().revokeAll(now)
            slots.forEach { db.runtimeSlots().forceFreeSlot(it.slotId, RuntimeReclaimReason.HOST_REMOVED_FROM_RECENTS.name, source, now, elapsed) }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeVirtualTasks(sessions: List<RuntimeSlotEntity>) {
        val virtualTaskIds = sessions.mapNotNull { it.taskId }.toSet()
        activityManager.appTasks.forEach { appTask ->
            val info = runCatching { appTask.taskInfo }.getOrNull() ?: return@forEach
            if (taskInfoId(info) in virtualTaskIds) runCatching { appTask.finishAndRemoveTask() }
        }
    }

    private fun requestVirtualRuntimeShutdown(sessions: List<RuntimeSlotEntity>) {
        sessions.forEach { slot ->
            val sid = slot.sessionId ?: return@forEach
            runCatching { startService(Intent(this, serviceFor(RuntimeSlotId.valueOf(slot.slotId))).putExtra("shutdownSessionId", sid)) }
        }
    }

    private suspend fun waitForRuntimeDeaths(sessions: List<RuntimeSlotEntity>, timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sessions.all { it.hostPid == null || runCatching { Os.kill(it.hostPid, 0); false }.getOrDefault(true) }) return
            delay(100)
        }
    }

    @Suppress("DEPRECATION")
    private fun taskInfoId(info: ActivityManager.RecentTaskInfo?): Int? = when {
        info == null -> null
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> info.taskId
        else -> info.id
    }

    private fun forceKillRemainingRuntimes(sessions: List<RuntimeSlotEntity>) {
        val expectedNames = setOf("${packageName}:vapp0", "${packageName}:vapp1")
        sessions.forEach { session ->
            val pid = session.hostPid ?: return@forEach
            if (pid > 0 && session.processName in expectedNames && session.sessionId != null) runCatching { Process.killProcess(pid) }
        }
    }

    private fun registeredHostTaskId(): Int = prefs().getInt(KEY_HOST_TASK_ID, -1)
    private fun prefs() = getSharedPreferences("host_task_supervisor", MODE_PRIVATE)
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Supervisor VirtualSpace", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(): Notification = Notification.Builder(this, if (Build.VERSION.SDK_INT >= 26) CHANNEL_ID else "").setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("Valcrono VirtualSpace").setContentText("Supervisando aplicaciones virtuales activas").setOngoing(true).build()

    companion object {
        private const val CHANNEL_ID = "host_task_supervisor"; private const val NOTIFICATION_ID = 2002
        private const val ACTION_REGISTER_HOST = "com.valcrono.virtualspace.REGISTER_HOST_TASK"
        private const val ACTION_REGISTER_VIRTUAL = "com.valcrono.virtualspace.REGISTER_VIRTUAL_TASK"
        private const val KEY_HOST_TASK_ID = "hostTaskId"
        private const val KEY_HOST_COMPONENT = "hostComponent"
        private const val KEY_HOST_INSTANCE = "hostInstanceId"
        private const val EXTRA_TASK_ID = "taskId"; private const val EXTRA_COMPONENT = "component"; private const val EXTRA_HOST_INSTANCE = "hostInstance"; private const val EXTRA_SESSION_ID = "sessionId"; private const val EXTRA_SLOT_ID = "slotId"; private const val EXTRA_PID = "pid"
        fun registerHostTask(context: Context, taskId: Int, componentName: String, hostInstanceId: String) = start(context, Intent(context, HostTaskSupervisorService::class.java).setAction(ACTION_REGISTER_HOST).putExtra(EXTRA_TASK_ID, taskId).putExtra(EXTRA_COMPONENT, componentName).putExtra(EXTRA_HOST_INSTANCE, hostInstanceId))
        fun registerVirtualTask(context: Context, sessionId: String, slotId: RuntimeSlotId, taskId: Int) = start(context, Intent(context, HostTaskSupervisorService::class.java).setAction(ACTION_REGISTER_VIRTUAL).putExtra(EXTRA_SESSION_ID, sessionId).putExtra(EXTRA_SLOT_ID, slotId.name).putExtra(EXTRA_TASK_ID, taskId).putExtra(EXTRA_PID, Process.myPid()))
        fun ensureRunning(context: Context) = start(context, Intent(context, HostTaskSupervisorService::class.java))
        private fun start(context: Context, intent: Intent) { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent) }
    }
}
