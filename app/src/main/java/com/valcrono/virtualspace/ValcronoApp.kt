package com.valcrono.virtualspace

import android.app.Application

class ValcronoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeHostRegistry.startRecovery()
        logLaunch("HOST_STARTED", null, null, RuntimeHostRegistry.hostInstanceId, null, null, "pid=${RuntimeHostRegistry.hostProcessPid}", "generation=${RuntimeHostRegistry.runtimeGeneration}")
    }
}
