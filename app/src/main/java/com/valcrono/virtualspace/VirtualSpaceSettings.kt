package com.valcrono.virtualspace

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.valcrono.core.MemoryProfile
import com.valcrono.core.OpenAnotherAppPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.virtualSpaceDataStore by preferencesDataStore("virtualspace_settings")

data class VirtualSpaceSettings(
    val performanceProfile: MemoryProfile = MemoryProfile.AUTOMATIC,
    val maxActiveApps: Int = 1,
    val maxPausedSessions: Int = 1,
    val cacheBudgetMb: Int = 64,
    val inactiveTimeoutMinutes: Int = 5,
    val minAvailableRamMb: Int = 512,
    val openAnotherAppPolicy: OpenAnotherAppPolicy = OpenAnotherAppPolicy.PAUSE_CURRENT,
    val theme: String = "Sistema",
    val accentColor: String = "Predeterminado",
    val textScale: String = "Sistema",
    val cardDensity: String = "Normal",
    val animations: Boolean = true,
    val reduceMotion: Boolean = false,
    val showTechnicalInfo: Boolean = false,
    val confirmDestructiveActions: Boolean = true,
    val maxTotalStorageMb: Int = 0,
    val maxCacheStorageMb: Int = 256,
    val autoDeleteCache: Boolean = true,
    val warnLowStorage: Boolean = true,
    val cleanTempOnStart: Boolean = true,
    val cleanTempOnClose: Boolean = true,
    val analyzeOnImport: Boolean = true,
    val confirmUpdates: Boolean = true,
    val keepDataOnUpdate: Boolean = true,
    val rejectDifferentSignature: Boolean = true,
    val maxApkSizeMb: Int = 500,
    val allowWarningApks: Boolean = true,
    val showCompatibilityReport: Boolean = true,
    val copyApkInternal: Boolean = true,
    val deleteTemporarySource: Boolean = true,
    val includeApkInExport: Boolean = false,
    val includeCacheInExport: Boolean = false,
    val includeLogsInExport: Boolean = false,
    val backupCompression: Boolean = false,
    val backupSha256: Boolean = true,
    val backupBeforeDelete: Boolean = true,
    val backupBeforeUpdate: Boolean = true,
    val pinLockEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    val lockOnExit: Boolean = false,
    val autoLockMinutes: Int = 5,
    val hideRecentContent: Boolean = false,
    val hideFileNamesInPreview: Boolean = false,
    val requireDeleteConfirmation: Boolean = true,
    val requireRestoreConfirmation: Boolean = true,
    val adminActionLog: Boolean = true,
    val flagSecureEnabled: Boolean = false,
    val developerMode: Boolean = false,
    val logLevel: String = "Información",
    val showPhysicalPaths: Boolean = false,
    val showStackTraces: Boolean = false,
    val showRuntimeTimings: Boolean = false,
    val showClassLoaderInfo: Boolean = false,
    val firstRunComplete: Boolean = false,
)

class VirtualSpaceSettingsStore(private val context: Context) {
    val settings: Flow<VirtualSpaceSettings> = context.virtualSpaceDataStore.data.map { prefs ->
        VirtualSpaceSettings(
            performanceProfile = prefs[Keys.performanceProfile]?.let { runCatching { MemoryProfile.valueOf(it) }.getOrNull() } ?: MemoryProfile.AUTOMATIC,
            cardDensity = prefs[Keys.cardDensity] ?: "Normal",
            theme = prefs[Keys.theme] ?: "Sistema",
            developerMode = prefs[Keys.developerMode] ?: false,
            firstRunComplete = prefs[Keys.firstRunComplete] ?: false,
            flagSecureEnabled = prefs[Keys.flagSecureEnabled] ?: false,
        )
    }

    suspend fun setProfile(profile: MemoryProfile) { context.virtualSpaceDataStore.edit { it[Keys.performanceProfile] = profile.name } }
    suspend fun setTheme(theme: String) { context.virtualSpaceDataStore.edit { it[Keys.theme] = theme } }
    suspend fun setCardDensity(density: String) { context.virtualSpaceDataStore.edit { it[Keys.cardDensity] = density } }
    suspend fun setFirstRunComplete(done: Boolean) { context.virtualSpaceDataStore.edit { it[Keys.firstRunComplete] = done } }
    suspend fun reset() { context.virtualSpaceDataStore.edit { it.clear() } }

    fun exportJson(settings: VirtualSpaceSettings, perAppPolicies: Map<String, String> = emptyMap()): String =
        """{"schemaVersion":1,"appVersion":"${BuildConfig.VERSION_NAME}","settings":{"performanceProfile":"${settings.performanceProfile}","theme":"${settings.theme}","cardDensity":"${settings.cardDensity}"},"perAppPolicies":${perAppPolicies.entries.joinToString(prefix="{", postfix="}") { "\"${it.key}\":\"${it.value}\"" }},"createdAt":"${java.time.Instant.now()}"}"""

    private object Keys {
        val performanceProfile = stringPreferencesKey("performance_profile")
        val cardDensity = stringPreferencesKey("card_density")
        val theme = stringPreferencesKey("theme")
        val developerMode = booleanPreferencesKey("developer_mode")
        val firstRunComplete = booleanPreferencesKey("first_run_complete")
        val flagSecureEnabled = booleanPreferencesKey("flag_secure_enabled")
    }
}
