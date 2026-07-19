package com.valcrono.virtualspace

import android.os.Process
import com.valcrono.core.VLog
import com.valcrono.runtime.VirtualContent
import com.valcrono.runtime.VirtualMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MessageDeliveryDispatcher(
    private val repository: VirtualRepository,
    private val scope: CoroutineScope,
    private val sessionId: String,
    private val slotId: RuntimeSlotId,
    private val packageName: String,
    private val virtualUserId: Int,
    private val maxAttempts: Int = 5,
    private val staleMs: Long = 60_000,
    private val onDeliver: suspend (VirtualMessageEntity) -> VirtualContent,
    private val onContentChanged: suspend (VirtualContent) -> Unit,
) {
    private var job: Job? = null
    val dispatcherSessionId: String = "$sessionId:${slotId.name}:$packageName:$virtualUserId"
    val active: Boolean get() = job?.isActive == true

    fun start(): String {
        stop("RESTART")
        log("MESSAGE_DISPATCHER_STARTED")
        job = scope.launch {
            recoverStaleDelivering()
            launch { slowStaleRecoveryLoop() }
            repository.db.messages()
                .observePendingFor(packageName, virtualUserId)
                .onEach { log("MESSAGE_FLOW_EMITTED", extra = "count=${it.size}") }
                .catch { log("MESSAGE_DISPATCHER_STOPPED", error = it.message) }
                .collect { pending -> pending.forEach { deliverOne(it) } }
        }
        return dispatcherSessionId
    }

    fun stop(reason: String = "STOP") {
        job?.cancel()
        if (job != null) log("MESSAGE_DISPATCHER_STOPPED", extra = "reason=$reason")
        job = null
    }

    suspend fun recoverStaleDelivering() {
        val now = System.currentTimeMillis()
        repository.db.messages().requeueStaleDelivering(packageName, virtualUserId, now - staleMs)
    }

    private suspend fun slowStaleRecoveryLoop() {
        while (scope.coroutineContext.isActive) {
            delay(staleMs)
            recoverStaleDelivering()
        }
    }

    private suspend fun deliverOne(entity: VirtualMessageEntity) {
        val claimed = repository.db.messages().claimPending(entity.messageId)
        if (claimed != 1) { log("MESSAGE_DUPLICATE_SKIPPED", entity); return }
        val claimedEntity = entity.copy(status = "DELIVERING", attemptCount = entity.attemptCount + 1)
        log("MESSAGE_CLAIMED", claimedEntity)
        try {
            log("MESSAGE_CALLBACK_BEGIN", claimedEntity)
            val content = onDeliver(claimedEntity)
            log("MESSAGE_CALLBACK_SUCCESS", claimedEntity)
            onContentChanged(content)
            val now = System.currentTimeMillis()
            repository.db.messages().markConsumedAfterCallback(entity.messageId, now)
            log("MESSAGE_CONSUMED", claimedEntity)
        } catch (t: Throwable) {
            log("MESSAGE_CALLBACK_FAILED", claimedEntity, error = t.message)
            val now = System.currentTimeMillis()
            if (claimedEntity.attemptCount >= maxAttempts) {
                repository.db.messages().markFailed(entity.messageId, now)
            } else {
                repository.db.messages().requeueDelivering(entity.messageId)
                log("MESSAGE_REQUEUED", claimedEntity)
            }
        }
    }

    private fun log(event: String, entity: VirtualMessageEntity? = null, extra: String? = null, error: String? = null) {
        VLog.i("MessageDelivery", listOfNotNull(
            event,
            "messageId=${entity?.messageId.orEmpty()}",
            "senderPackage=${entity?.senderPackage.orEmpty()}",
            "receiverPackage=$packageName",
            "virtualUserId=$virtualUserId",
            "sessionId=$sessionId",
            "slotId=${slotId.name}",
            "pid=${Process.myPid()}",
            "attemptCount=${entity?.attemptCount ?: 0}",
            "timestamp=${System.currentTimeMillis()}",
            extra,
            error?.let { "error=$it" },
        ).joinToString(" "))
    }
}
