package com.mockonomy.server

import com.mockonomy.data.AdminApiSettingsStore
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.log.RequestLogSink
import com.mockonomy.data.log.RequestLogStore
import com.mockonomy.engine.model.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** The externally visible form of a single running server (notification, list, diagnostics). */
data class RunningServer(
    val environmentId: String,
    val environmentName: String,
    val port: Int,
    /** `null` means the proxy is off; otherwise unmatched requests are forwarded there. */
    val proxyTarget: String? = null,
)

/**
 * A start/update failure. It is a **one-off event**, not a persistent state: the UI
 * shows it as a snackbar. If it were kept as state, the old error would reappear
 * every time the screen recomposes.
 */
data class ServerFailure(val environmentId: String, val environmentName: String, val reason: String)

/**
 * The single owner of the server lifecycle.
 *
 * [MockServer] must not be started directly from an Activity or ViewModel: when the
 * app goes to the background the process is suspended and the socket closes. Starting
 * only happens through [MockServerService], inside a foreground service. This class
 * holds the state and is the single source the UI can observe — the only answer to
 * "which environments are running" is [running]; nothing is persisted (when the
 * process dies the servers die too, so a persisted record would only drift from
 * reality).
 *
 * **Multiple servers:** one [MockServer] instance is kept per environment, each
 * listening on its own port. Ports stay unique here: a second environment cannot be
 * started on a port that is already in use.
 */
@Singleton
class MockServerController @Inject constructor(
    // NO default value: Kotlin generates a second constructor from an @Inject
    // constructor with defaults, and Hilt rejects it ("only one injected constructor").
    // Callers that do not want recording pass [NoRequestLog].
    private val logSink: RequestLogSink,
    // The automation admin API needs these dependencies: activating/listing
    // environments, reading/clearing logs, and the settings gate.
    private val environmentStore: EnvironmentStore,
    private val logStore: RequestLogStore,
    adminSettings: AdminApiSettingsStore,
) {

    /**
     * Stateless: the [AdminControl] passed during `install` decides which server it
     * manages. That is why all servers can share one instance.
     */
    private val adminApi = AdminApi(adminSettings.settings, environmentStore, logStore)

    private class Entry(val server: MockServer, var info: RunningServer, var stateMirror: Job)

    /** envId → running server. `LinkedHashMap`: order = start order. */
    private val servers = LinkedHashMap<String, Entry>()

    /** All mutations of [servers] are serialized (the service and automation can arrive at the same time). */
    private val mutex = Mutex()

    /** Long-lived scope that mirrors the servers' `activeState` flows into a single map. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _running = MutableStateFlow<List<RunningServer>>(emptyList())

    /** Running servers in start order. An empty list = none is running. */
    val running: StateFlow<List<RunningServer>> = _running.asStateFlow()

    private val _failures = MutableSharedFlow<ServerFailure>(extraBufferCapacity = FAILURE_BUFFER)
    val failures: SharedFlow<ServerFailure> = _failures.asSharedFlow()

    private val _activeStates = MutableStateFlow<Map<String, String>>(emptyMap())

    /** envId → the live active state of that environment's state machine. */
    val activeStates: StateFlow<Map<String, String>> = _activeStates.asStateFlow()

    val isRunning: Boolean get() = servers.isNotEmpty()

    fun isRunning(environmentId: String): Boolean = servers.containsKey(environmentId)

    /** Changes the active state by hand (only effective while that environment is running). */
    fun setActiveState(environmentId: String, state: String) {
        servers[environmentId]?.server?.setActiveState(state)
    }

    /**
     * Starts the server and updates the state.
     *
     * Failures are not swallowed: if the port is used by another app the user must see
     * it, with a clear message — there is NO automatic port increment. Silently moving
     * to another port would cause a more confusing error, because the app under test
     * would still be looking at the old port.
     *
     * If the port belongs to another one of our servers the socket is never opened: the
     * conflict is caught before starting and reported by name.
     */
    suspend fun start(environment: Environment, port: Int = environment.port) {
        mutex.withLock {
            if (servers.containsKey(environment.id)) return
            portRangeError(port)?.let { reason ->
                fail(environment, reason)
                return
            }
            conflictingServer(port)?.let { conflict ->
                fail(environment, "Port $port is already used by \"${conflict.environmentName}\".")
                return
            }

            val server = MockServer(logSink = logSink, adminApi = adminApi)
            runCatching {
                server.start(environment, port)
                server.resolvedPort()
            }.onSuccess { resolvedPort ->
                register(environment.id, server, RunningServer(environment.id, environment.name, resolvedPort, activeProxyTarget(environment)))
            }.onFailure { error ->
                runCatching { server.stop() }
                fail(environment, friendlyStartError(error, port))
            }
        }
    }

    /** Stops a single environment; the others keep running. */
    suspend fun stop(environmentId: String) {
        mutex.withLock { unregister(environmentId) }
    }

    /** Stops all of them — "Stop all" in the notification and service shutdown. */
    suspend fun stopAll() {
        mutex.withLock { servers.keys.toList().forEach { unregister(it) } }
    }

    /**
     * Fire-and-forget forms of [stop] / [stopAll]. The service cancels its own scope while
     * shutting down, so the stop work must run in **this** scope; otherwise sockets stay
     * open (`MockServerService.onDestroy`).
     */
    fun stopAsync(environmentId: String) {
        scope.launch { stop(environmentId) }
    }

    fun stopAllAsync() {
        scope.launch { stopAll() }
    }

    /**
     * Updates the configuration of a running server. Does nothing if that environment is
     * not running — the caller should use [start] instead.
     *
     * Port changes are handled transparently by [MockServer.update]. If the new port
     * belongs to another one of our servers the change is **not applied**: closing the
     * socket and moving to a port it cannot bind would silently kill the running server.
     * The user sees the error and the server stays on its old port.
     */
    suspend fun update(environment: Environment) {
        mutex.withLock {
            val entry = servers[environment.id] ?: return
            portRangeError(environment.port)?.let { reason ->
                fail(environment, "$reason Still serving on ${entry.info.port}.")
                return
            }
            val conflict = if (environment.port == entry.info.port) null else conflictingServer(environment.port)
            if (conflict != null) {
                fail(environment, "Port ${environment.port} is already used by \"${conflict.environmentName}\". Still serving on ${entry.info.port}.")
                return
            }
            runCatching {
                entry.server.update(environment)
                entry.server.resolvedPort()
            }.onSuccess { resolvedPort ->
                entry.info = RunningServer(environment.id, environment.name, resolvedPort, activeProxyTarget(environment))
                publish()
            }.onFailure { error ->
                unregister(environment.id)
                fail(environment, friendlyStartError(error, environment.port))
            }
        }
    }

    // --- Automation ---

    /**
     * Runs an automation command on the running servers (ADB broadcast).
     *
     * **Targeting with multiple servers:** state-changing commands (`setState`,
     * `setResponse`, `reset`, `clearLogs`) are applied to **all** running servers and take
     * effect on every server where the command makes sense; if it applies to none, the
     * first error is returned. `activate` is the exception — switching a socket to
     * another environment is inherently a single-socket operation, see [activateOne].
     *
     * The HTTP admin API does not go through here: each server serves its own
     * `/__mockonomy` endpoints on its own port, so the port is already the target.
     */
    suspend fun runAutomationCommand(command: AutomationCommand): AutomationResult {
        val targets = mutex.withLock { servers.values.map { it.info.environmentName to it.server } }
        if (targets.isEmpty()) return AutomationResult.NoEnvironment()
        if (command is AutomationCommand.Activate) return activateOne(command)
        return combineAutomationResults(
            targets.map { (name, server) -> name to handlerFor(server).handle(command) },
        )
    }

    /**
     * `activate` applies to a single socket. If the target is already running nothing
     * happens (idempotent); otherwise the server holding the target's port is switched,
     * or, failing that, the only running server. With more than one candidate the command
     * is rejected — silently switching the wrong socket would hide the test's mistake.
     */
    private suspend fun activateOne(command: AutomationCommand.Activate): AutomationResult {
        val snapshot = mutex.withLock { servers.values.map { Triple(it.info, it.server, it.server.currentEnvironment) } }
        val alreadyRunning = snapshot.firstOrNull { (_, _, env) ->
            env != null && (env.id == command.environmentKey || env.key == command.environmentKey)
        }
        if (alreadyRunning != null) {
            val handler = handlerFor(alreadyRunning.second)
            // If a state was requested it is still applied; the activation itself is a no-op.
            return handler.handle(command)
        }

        val target = environmentStore.list()
            .filterIsInstance<com.mockonomy.data.EnvironmentSummary.Valid>()
            .map { it.environment }
            .resolveEnvironment(command.environmentKey)
        val byPort = target?.let { wanted -> snapshot.firstOrNull { it.first.port == wanted.port } }
        val chosen = byPort ?: snapshot.singleOrNull()
            ?: return AutomationResult.BadRequest(
                "ambiguous_activate_target",
                "${snapshot.size} servers are running; activation targets a single socket. Stop the others or use the HTTP admin API on the port you mean.",
                snapshot.mapNotNull { it.third?.ref() },
            )
        return handlerFor(chosen.second).handle(command)
    }

    private fun handlerFor(server: MockServer) = AutomationCommandHandler(server, environmentStore, logStore)

    // --- internal helpers (all called under [mutex]) ---

    /**
     * An out-of-range port is rejected **before a socket is opened**. Ktor's bind error is
     * not thrown from `embeddedServer.start()` but from its own accept coroutine; the
     * `IllegalArgumentException` there escapes every `runCatching` and crashes the whole
     * process (seen on a device: port 80918 was typed and hot reload killed the app). So
     * the check lives here, at the outermost level.
     *
     * `0` = "let the operating system choose" (used by tests) and counts as valid.
     */
    private fun portRangeError(port: Int): String? =
        if (port == 0 || port in Environment.MIN_PORT..Environment.MAX_PORT) {
            null
        } else {
            "Port $port is out of range. Use a port between ${Environment.MIN_PORT} and ${Environment.MAX_PORT}."
        }

    private fun conflictingServer(port: Int): RunningServer? =
        // Port 0 = "let the operating system choose" (used by tests); not a conflict.
        if (port == 0) null else servers.values.firstOrNull { it.info.port == port }?.info

    private fun register(environmentId: String, server: MockServer, info: RunningServer) {
        server.onActivated = { activated -> onActivated(server, activated) }
        servers[environmentId] = Entry(server, info, mirrorActiveState(environmentId, server))
        publish()
    }

    private fun unregister(environmentId: String) {
        val entry = servers.remove(environmentId) ?: return
        entry.stateMirror.cancel()
        _activeStates.update { it - environmentId }
        runCatching { entry.server.stop() }
        publish()
    }

    /**
     * When the admin API hot-swaps an environment on a running socket, external state is
     * synchronized: the map key and the notification must show the new environment.
     * Otherwise the UI would show the old environment as running.
     */
    private suspend fun onActivated(server: MockServer, environment: Environment) {
        mutex.withLock {
            val previousId = servers.entries.firstOrNull { it.value.server === server }?.key ?: return
            if (previousId == environment.id) return
            val entry = servers.remove(previousId) ?: return
            entry.stateMirror.cancel()
            _activeStates.update { it - previousId }

            val port = runCatching { server.resolvedPort() }.getOrDefault(entry.info.port)
            entry.info = RunningServer(environment.id, environment.name, port, activeProxyTarget(environment))
            entry.stateMirror = mirrorActiveState(environment.id, server)
            servers[environment.id] = entry
            publish()
        }
    }

    /** Moves the server's own state flow into the shared map; cancelled when the server stops. */
    private fun mirrorActiveState(environmentId: String, server: MockServer): Job =
        scope.launch {
            server.activeState.collect { state ->
                _activeStates.update { current ->
                    if (state == null) current - environmentId else current + (environmentId to state)
                }
            }
        }

    private fun publish() {
        _running.value = servers.values.map { it.info }
    }

    private fun fail(environment: Environment, reason: String) {
        _failures.tryEmit(ServerFailure(environment.id, environment.name, reason))
    }

    /** For the notification and UI: the proxy target if the proxy is on and a target is set, otherwise `null`. */
    private fun activeProxyTarget(environment: Environment): String? =
        environment.proxy?.takeIf { it.enabled && it.target.isNotBlank() }?.target

    /**
     * Turns Ktor CIO's raw bind error (JDK messages like "Address already in use") into
     * a sentence the user understands. Errors that do not match (e.g. an invalid port
     * range) are left as they are.
     */
    private fun friendlyStartError(error: Throwable, port: Int): String =
        if (isPortConflict(error)) {
            "Port $port is already in use by another app. Choose a different port."
        } else {
            error.message ?: error::class.simpleName.orEmpty()
        }

    /**
     * The real `BindException` is usually not at the top — a `JobCancellationException`
     * wrapper sits in between while Ktor CIO's engine job is cancelled (verified by
     * hand). So the whole `cause` chain is scanned.
     */
    private fun isPortConflict(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .take(MAX_CAUSE_CHAIN_DEPTH)
            .any { candidate ->
                candidate is java.net.BindException ||
                    candidate.message?.contains("address already in use", ignoreCase = true) == true
            }

    private companion object {
        const val MAX_CAUSE_CHAIN_DEPTH = 10
        const val FAILURE_BUFFER = 8
    }
}
