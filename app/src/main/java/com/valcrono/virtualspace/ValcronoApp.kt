package com.valcrono.virtualspace

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ValcronoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeHostRegistry.startRecovery()
        logLaunch("HOST_STARTED", null, null, RuntimeHostRegistry.hostInstanceId, null, null, "pid=${RuntimeHostRegistry.hostProcessPid}", "generation=${RuntimeHostRegistry.runtimeGeneration}")
        CoroutineScope(Dispatchers.IO).launch { RuntimeSessionRepository(DatabaseProvider.get(applicationContext)).reconcileApplicationsWithRuntime() }
    }
}
