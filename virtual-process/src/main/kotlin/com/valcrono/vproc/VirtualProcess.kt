package com.valcrono.vproc

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

enum class VirtualProcessState { STARTING, RUNNING, STOPPED, CRASHED }

data class VirtualRuntimeSession(
    val id: String,
    val virtualPackageName: String,
    val virtualUserId: Int,
    val activityName: String,
    val startTime: Long = System.currentTimeMillis(),
)

data class VirtualProcessRecord(
    val virtualPackageName: String,
    val virtualUserId: Int,
    val runtimeSessionId: String,
    val hostProcessName: String,
    val pid: Int,
    val startTime: Long,
    val state: VirtualProcessState,
)

class VirtualProcessRegistry {
    private val records = ConcurrentHashMap<String, VirtualProcessRecord>()
    fun register(record: VirtualProcessRecord) { records[record.runtimeSessionId] = record }
    fun update(id: String, state: VirtualProcessState) { records.computeIfPresent(id) { _, old -> old.copy(state = state) } }
    fun all(): List<VirtualProcessRecord> = records.values.sortedBy { it.startTime }
}

data class VirtualIntent(
    val fromPackage: String,
    val toPackage: String,
    val toActivity: String,
    val extras: Map<String, String>,
    val virtualUserId: Int,
)

class VirtualIntentRouter(private val activities: () -> Set<Pair<String, String>>) {
    fun resolve(intent: VirtualIntent): String {
        require(intent.virtualUserId >= 0) { "Invalid virtualUserId" }
        require(intent.fromPackage.isNotBlank()) { "Missing sender" }
        require(intent.toPackage.isNotBlank()) { "Missing receiver" }
        val key = intent.toPackage to intent.toActivity
        require(activities().contains(key)) { "Target not registered: ${intent.toPackage}/${intent.toActivity}" }
        return intent.toActivity
    }
}

data class LaunchToken(
    val value: String,
    val virtualUserId: Int,
    val virtualPackageName: String,
    val virtualActivityName: String,
    val createdAt: Long,
    val expiresAt: Long,
)

class LaunchTokenStore(private val now: () -> Long = { System.currentTimeMillis() }) {
    private val random = SecureRandom()
    private val tokens = ConcurrentHashMap<String, LaunchToken>()

    fun create(userId: Int, packageName: String, activityName: String, ttlMillis: Long = 60_000): String {
        require(userId >= 0) { "Invalid virtualUserId" }
        val value = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val start = now()
        tokens[value] = LaunchToken(value, userId, packageName, activityName, start, start + ttlMillis)
        return value
    }

    fun consume(value: String, userId: Int, packageName: String, activityName: String): Boolean {
        val token = tokens.remove(value) ?: return false
        return token.virtualUserId == userId &&
            token.virtualPackageName == packageName &&
            token.virtualActivityName == activityName &&
            token.expiresAt >= now()
    }
}

object InMemoryLaunchTokens {
    val store = LaunchTokenStore()
    fun create(userId: Int, packageName: String, activityName: String): String = store.create(userId, packageName, activityName)
    fun consume(value: String, userId: Int, packageName: String, activityName: String): Boolean =
        store.consume(value, userId, packageName, activityName)
}

interface VirtualApkClassLoader
interface VirtualResourcesLoader
interface VirtualApplicationLoader

class VirtualActivityLauncher(private val registry: VirtualProcessRegistry) {
    fun start(packageName: String, userId: Int, activityName: String): VirtualRuntimeSession {
        val session = VirtualRuntimeSession(
            id = InMemoryLaunchTokens.create(userId, packageName, activityName),
            virtualPackageName = packageName,
            virtualUserId = userId,
            activityName = activityName,
        )
        registry.register(
            VirtualProcessRecord(
                virtualPackageName = packageName,
                virtualUserId = userId,
                runtimeSessionId = session.id,
                hostProcessName = "host",
                pid = ProcessHandle.current().pid().toInt(),
                startTime = session.startTime,
                state = VirtualProcessState.RUNNING,
            ),
        )
        return session
    }

    fun stop(sessionId: String) = registry.update(sessionId, VirtualProcessState.STOPPED)
}

interface VirtualAdministrativeAccess { fun allowed(packageName: String): Boolean }
object VirtualAdministrativePolicy : VirtualAdministrativeAccess { override fun allowed(packageName: String): Boolean = false }
data class VirtualAuditEvent(val packageName: String, val action: String, val allowed: Boolean, val time: Long = System.currentTimeMillis())
