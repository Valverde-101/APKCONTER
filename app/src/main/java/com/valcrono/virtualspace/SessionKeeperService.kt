package com.valcrono.virtualspace

import android.app.*
import android.content.*
import android.os.Build
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.valcrono.core.VLog

class SessionKeeperService : Service(), ComponentCallbacks2 {
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = mutableMapOf<RuntimeSlotId, ServiceConnection>()
    override fun onCreate() { super.onCreate(); createChannel(); startForeground(NOTIFICATION_ID, notification()) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { bindActiveSlots(); return START_STICKY }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdownScope.launch {
            shutdownAllVirtualSessions(
                context = applicationContext,
                reason = ShutdownReason.HOST_REMOVED_FROM_RECENTS
            )
            stopForeground(true)
            stopSelf()
            Process.killProcess(Process.myPid())
        }
    }
    override fun onDestroy() { connections.values.forEach { runCatching { unbindService(it) } }; connections.clear(); super.onDestroy() }
    override fun onTrimMemory(level: Int) { VLog.i("SessionKeeperService", "onTrimMemory($level): cachés reconstruibles liberadas; sesiones activas conservadas") }
    override fun onLowMemory() { onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) }

    private fun bindActiveSlots() {
        RuntimeSlotId.entries.forEach { slot ->
            if (connections.containsKey(slot)) return@forEach
            val intent = Intent(this, serviceFor(slot))
            startService(intent)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) { VLog.i("SessionKeeperService", "binder conectado ${slot.name} alive=${service?.isBinderAlive}") }
                override fun onServiceDisconnected(name: ComponentName?) { connections.remove(slot); VLog.i("SessionKeeperService", "binder desconectado ${slot.name}") }
            }
            val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            if (bindService(intent, conn, flags)) connections[slot] = conn
        }
    }

    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sesiones activas", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(): Notification = Notification.Builder(this, if (Build.VERSION.SDK_INT >= 26) CHANNEL_ID else "")
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle("Valcrono VirtualSpace")
        .setContentText("Valcrono VirtualSpace mantiene 2 aplicaciones activas")
        .setOngoing(true)
        .build()
    companion object { private const val CHANNEL_ID = "session_keeper"; private const val NOTIFICATION_ID = 2001
        fun startFromUserAction(context: Context): String? = try { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(Intent(context, SessionKeeperService::class.java)) else context.startService(Intent(context, SessionKeeperService::class.java)); null } catch (t: Throwable) { "No se pudo activar el modo de permanencia: ${t.javaClass.simpleName}" }
        fun stop(context: Context) { context.stopService(Intent(context, SessionKeeperService::class.java)) }
    }
}
