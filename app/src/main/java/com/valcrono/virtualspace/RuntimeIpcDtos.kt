package com.valcrono.virtualspace

import android.os.Parcel
import android.os.Parcelable

data class RuntimeLaunchRequest(
    val virtualUserId: Int,
    val virtualPackageName: String,
    val virtualActivityName: String,
    val sessionId: String,
    val launchAttemptId: String,
    val launchToken: String,
    val slotId: String,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
    )
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(virtualUserId); parcel.writeString(virtualPackageName); parcel.writeString(virtualActivityName)
        parcel.writeString(sessionId); parcel.writeString(launchAttemptId); parcel.writeString(launchToken); parcel.writeString(slotId)
    }
    override fun describeContents(): Int = 0
    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<RuntimeLaunchRequest> = object : Parcelable.Creator<RuntimeLaunchRequest> {
            override fun createFromParcel(parcel: Parcel): RuntimeLaunchRequest = RuntimeLaunchRequest(parcel)
            override fun newArray(size: Int): Array<RuntimeLaunchRequest?> = arrayOfNulls(size)
        }
    }
}

data class RuntimeStatusDto(
    val slotId: String,
    val sessionId: String?,
    val pid: Int?,
    val state: String,
    val lastHeartbeatAt: Long?,
    val pssBytes: Long?,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString().orEmpty(),
        parcel.readString(),
        parcel.readValue(RuntimeStatusDto::class.java.classLoader) as? Int,
        parcel.readString().orEmpty(),
        parcel.readValue(RuntimeStatusDto::class.java.classLoader) as? Long,
        parcel.readValue(RuntimeStatusDto::class.java.classLoader) as? Long,
    )
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(slotId); parcel.writeString(sessionId); parcel.writeValue(pid); parcel.writeString(state)
        parcel.writeValue(lastHeartbeatAt); parcel.writeValue(pssBytes)
    }
    override fun describeContents(): Int = 0
    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<RuntimeStatusDto> = object : Parcelable.Creator<RuntimeStatusDto> {
            override fun createFromParcel(parcel: Parcel): RuntimeStatusDto = RuntimeStatusDto(parcel)
            override fun newArray(size: Int): Array<RuntimeStatusDto?> = arrayOfNulls(size)
        }
    }
}
