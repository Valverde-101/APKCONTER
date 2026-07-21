package com.valcrono.virtualspace

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.room.withTransaction
import com.valcrono.core.VLog

/** Reason for an unconditional host-driven runtime shutdown. */
enum class ShutdownReason { HOST_REMOVED_FROM_RECENTS }

/** Performs the hard cleanup required when the host task is removed from Recents. */
suspend fun shutdownAllVirtualSessions(context: Context, reason: ShutdownReason) {
    RuntimeHostRegistry.beginHostRecentsShutdown()
    val appContext = context.applicationContext
    val db = DatabaseProvider.get(appContext)
    val now = System.currentTimeMillis()
    val elapsed = SystemClock.elapsedRealtime()
    val slots = db.runtimeSlots().getAll()

    db.withTransaction {
        RuntimeSlotId.entries.forEach { slot ->
            db.runtimeSlots().markStopping(slot.name, reason.name, "shutdownAllVirtualSessions", elapsed)
        }
        db.runtime().stopAllByHost(now)
        db.launchTokens().revokeAll(now)
    }

    slots.forEach { slot ->
        val sid = slot.sessionId
        if (sid != null) runCatching { stopRuntimeService(appContext, RuntimeSlotId.valueOf(slot.slotId), sid) }
        runCatching { appContext.stopService(Intent(appContext, serviceFor(RuntimeSlotId.valueOf(slot.slotId)))) }
    }

    runCatching {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        appContext.packageName.let { packageName -> am.appTasks.forEach { task -> runCatching { task.finishAndRemoveTask() } } }
    }

    db.withTransaction {
        RuntimeSlotId.entries.forEach { slot ->
            db.runtimeSlots().forceFreeSlot(slot.name, RuntimeReclaimReason.HOST_REMOVED_FROM_RECENTS.name, "shutdownAllVirtualSessions", now, elapsed)
        }
    }

    slots.mapNotNull { it.hostPid }.distinct().filter { it != Process.myPid() }.forEach { pid ->
        runCatching { Process.killProcess(pid) }
    }
    VLog.i("HostRecentsShutdown", "shutdownAllVirtualSessions completed reason=$reason slots=${slots.size}")
}

private fun stopRuntimeService(context: Context, slotId: RuntimeSlotId, sessionId: String) {
    val intent = Intent(context, serviceFor(slotId)).putExtra("shutdownSessionId", sessionId)
    context.startService(intent)
}
