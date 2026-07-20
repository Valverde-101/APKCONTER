package com.valcrono.virtualspace

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/** Reads Android's historical process-exit records; never infers memory pressure without evidence. */
class ProcessExitReasonRepository(private val context: Context) {
    data class ExitRecord(
        val processName: String?, val pid: Int, val timestamp: Long, val reason: Int, val status: Int,
        val importance: Int, val pss: Long, val rss: Long, val description: String?, val traceAvailable: Boolean,
        val initiator: TerminationInitiator, val userMessage: String,
    )

    fun findLatest(processSuffix: String, pid: Int? = null): ExitRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val lowMemoryReports = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) am.isLowMemoryKillReportSupported() else false
        return am.getHistoricalProcessExitReasons(context.packageName, pid ?: 0, 32)
            .filter { it.processName?.endsWith(processSuffix) == true && (pid == null || it.pid == pid) }
            .maxByOrNull { it.timestamp }
            ?.toRecord(lowMemoryReports)
    }

    private fun ApplicationExitInfo.toRecord(lowMemoryReports: Boolean): ExitRecord {
        val mapped = when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> TerminationInitiator.ANDROID_LMK to "Android LMK confirmado"
            ApplicationExitInfo.REASON_SIGNALED -> if (status == 9) TerminationInitiator.ANDROID_SIGKILL to if (lowMemoryReports) "SIGKILL confirmado" else "SIGKILL confirmado; puede corresponder a LMK en dispositivos sin reporte específico" else TerminationInitiator.UNKNOWN to "Señal del sistema status=$status"
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR -> TerminationInitiator.PROCESS_CRASH to "Crash/ANR confirmado por Android"
            ApplicationExitInfo.REASON_USER_REQUESTED -> TerminationInitiator.USER to "Cierre solicitado por usuario o sistema"
            else -> TerminationInitiator.UNKNOWN to "Proceso finalizado; causa del sistema no confirmada"
        }
        val trace = runCatching { traceInputStream?.close(); true }.getOrDefault(false)
        return ExitRecord(processName, pid, timestamp, reason, status, importance, pss, rss, description, trace, mapped.first, mapped.second)
    }
}

enum class TerminationInitiator { USER, INTERNAL_MEMORY_POLICY, HEARTBEAT_WATCHDOG, BINDER_DEATH, ANDROID_LMK, ANDROID_SIGKILL, HOST_SHUTDOWN, SERVICE_DESTROYED, PROCESS_CRASH, UNKNOWN }
