package com.valcrono.virtualspace

import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Identity and readiness barrier for one main-host process lifetime. */
object RuntimeHostRegistry {
    val hostInstanceId: String = UUID.randomUUID().toString()
    val hostStartedElapsedRealtime: Long = SystemClock.elapsedRealtime()
    val hostProcessPid: Int = Process.myPid()
    private val generation = AtomicLong(System.currentTimeMillis())
    @Volatile var runtimeState: RuntimeState = RuntimeState.RECOVERING
        private set
    @Volatile var lastRecoveryStartedAt: Long = 0
        private set
    @Volatile var lastRecoveryCompletedAt: Long = 0
        private set
    @Volatile var lastRecoveryDurationMs: Long = 0
        private set
    @Volatile private var ready = CompletableDeferred<Unit>()
    val runtimeGeneration: Long get() = generation.get()

    fun startRecovery() {
        runtimeState = RuntimeState.RECOVERING
        lastRecoveryStartedAt = System.currentTimeMillis()
        ready = CompletableDeferred()
        logLaunch("HOST_RECOVERY_STARTED", null, null, hostInstanceId, null, null, "generation=$runtimeGeneration", "RECOVERING")
    }

    fun completeRecovery() {
        lastRecoveryCompletedAt = System.currentTimeMillis()
        lastRecoveryDurationMs = lastRecoveryCompletedAt - lastRecoveryStartedAt
        generation.incrementAndGet()
        runtimeState = RuntimeState.READY
        if (!ready.isCompleted) ready.complete(Unit)
        logLaunch("HOST_RECOVERY_COMPLETED", null, null, hostInstanceId, null, null, "durationMs=$lastRecoveryDurationMs", "READY")
    }

    suspend fun awaitReady() = ready.await()
}

enum class RuntimeState { RECOVERING, READY }
enum class RuntimeRegistrationResult { ADOPTED, RE_REGISTER_REQUIRED, STALE_SESSION, SLOT_CONFLICT, PROCESS_MUST_TERMINATE }
enum class RuntimeHeartbeatDisposition { ACCEPTED, SESSION_UNKNOWN, SESSION_STALE, OWNER_GENERATION_MISMATCH, SLOT_EMPTY, SLOT_OWNED_BY_OTHER_SESSION, HOST_RECOVERING }

data class VirtualProcessRegistration(
    val slotId: String,
    val packageName: String?,
    val sessionId: String?,
    val pid: Int,
    val processStartElapsedRealtime: Long,
    val launchAttemptId: String?,
    val runtimeToken: String?,
    val lastHeartbeatElapsedRealtime: Long?,
    val classLoaderLoaded: Boolean,
    val currentState: String,
)
