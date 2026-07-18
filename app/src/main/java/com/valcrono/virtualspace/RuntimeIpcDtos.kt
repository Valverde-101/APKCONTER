package com.valcrono.virtualspace

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RuntimeLaunchRequest(
    val virtualUserId: Int,
    val virtualPackageName: String,
    val virtualActivityName: String,
    val sessionId: String,
    val launchAttemptId: String,
    val launchToken: String,
    val slotId: String,
) : Parcelable

@Parcelize
data class RuntimeStatusDto(val slotId: String, val sessionId: String?, val pid: Int?, val state: String, val lastHeartbeatAt: Long?, val pssBytes: Long?) : Parcelable
