package com.valcrono.virtualspace

import android.os.SystemClock
import com.valcrono.core.VLog
import kotlinx.coroutines.sync.Mutex
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

object RuntimeSlotLocks {
    private val locks = RuntimeSlotId.entries.associate { it.name to Mutex() }
    fun mutex(slotId: String): Mutex = locks.getValue(slotId)
}

data class SlotMutationEvent(
    val timestampElapsed: Long,
    val thread: String,
    val caller: String,
    val oldState: String?,
    val newState: String?,
    val sessionId: String?,
    val launchAttemptId: String?,
    val reservationTokenPrefix: String?,
    val runtimeGeneration: Long?,
    val slotEpoch: Long,
    val reason: String,
)

object SlotMutationJournal {
    private const val MAX_EVENTS = 100
    private val events = ConcurrentHashMap<String, ArrayDeque<SlotMutationEvent>>()
    @Synchronized fun record(slotId: String, event: SlotMutationEvent) {
        val q = events.getOrPut(slotId) { ArrayDeque() }
        while (q.size >= MAX_EVENTS) q.removeFirst()
        q.addLast(event)
        VLog.i("RuntimeLaunch", "SLOT_MUTATION slotId=$slotId reason=${event.reason} old=${event.oldState} new=${event.newState} sessionId=${event.sessionId?.take(8)} launchAttemptId=${event.launchAttemptId?.take(8)} reservationToken=${event.reservationTokenPrefix} runtimeGeneration=${event.runtimeGeneration} slotEpoch=${event.slotEpoch} elapsed=${event.timestampElapsed} caller=${event.caller} thread=${event.thread}")
    }
    @Synchronized fun history(slotId: String): List<SlotMutationEvent> = events[slotId]?.toList().orEmpty()
    fun formatted(slotId: String): String = history(slotId).joinToString("\n") { e ->
        "${e.timestampElapsed} [${e.thread}] ${e.caller} ${e.oldState}->${e.newState} sid=${e.sessionId?.take(8)} attempt=${e.launchAttemptId?.take(8)} token=${e.reservationTokenPrefix} gen=${e.runtimeGeneration} epoch=${e.slotEpoch} reason=${e.reason}"
    }
}

fun logSlotMutation(reason: String, slot: RuntimeSlotEntity, caller: String, oldState: String? = null, newState: String? = slot.state) {
    SlotMutationJournal.record(slot.slotId, SlotMutationEvent(SystemClock.elapsedRealtime(), Thread.currentThread().name, caller, oldState ?: slot.state, newState, slot.sessionId, slot.launchAttemptId, slot.reservationToken?.take(8), slot.runtimeGeneration, slot.slotEpoch, reason))
}
