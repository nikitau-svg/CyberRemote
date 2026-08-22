package dev.companionremote.app

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.quicksettings.TileService
import dev.companionremote.app.data.CredentialsRepository
import dev.companionremote.app.data.SettingsRepository
import dev.companionremote.app.discovery.AtvDiscovery
import dev.companionremote.app.discovery.DiscoveredAtv
import dev.companionremote.app.diagnostics.Diagnostics
import dev.companionremote.app.diagnostics.Diagnostics.DiagnosticToken
import dev.companionremote.app.quick.LockScreenRemotePolicy
import dev.companionremote.app.quick.LockedRemoteRateLimiter
import dev.companionremote.app.quick.QuickRemoteAction
import dev.companionremote.app.quick.RemoteTileService
import dev.companionremote.protocol.client.CompanionClient
import dev.companionremote.protocol.client.KeyboardFocusState
import dev.companionremote.protocol.client.TouchPhase
import dev.companionremote.protocol.companion.CompanionConnection
import dev.companionremote.protocol.hap.HapCredentials
import dev.companionremote.protocol.transport.SocketTransport
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

enum class ConnectionState { Connecting, Connected, Disconnected }

/**
 * The single process-wide Apple TV session used by the full UI, Quick Settings
 * panel and notification controls. Keeping one owner prevents duplicate HAP
 * sessions and serializes button presses so a fast D-pad sequence stays ordered.
 */
class RemoteSessionManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val credentialsRepository = CredentialsRepository(appContext)
    private val settingsRepository = SettingsRepository(appContext)
    private val discovery = AtvDiscovery(appContext)
    private val keyguard = appContext.getSystemService(KeyguardManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val lockedRateLimiter = LockedRemoteRateLimiter { SystemClock.elapsedRealtime() }

    private var client: CompanionClient? = null
    private var credentials: HapCredentials? = null
    private var keyboardJob: Job? = null
    private var uiOwner = false
    private var panelOwner = false
    private var quickRemoteOwner = false
    private var lockScreenRemoteOwner = false
    private var openFullRemoteToken: String? = null
    @Volatile private var lockScreenControlsEnabled = false

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError

    private val _activeDevice = MutableStateFlow<DiscoveredAtv?>(null)
    val activeDevice: StateFlow<DiscoveredAtv?> = _activeDevice

    private val _keyboardFocus = MutableStateFlow(KeyboardFocusState.Unknown)
    val keyboardFocus: StateFlow<KeyboardFocusState> = _keyboardFocus

    private data class CommandRequest(
        val block: suspend (CompanionClient) -> Unit,
        val result: CompletableDeferred<Boolean>? = null,
        val requireUnlocked: Boolean = true,
        val allowReconnect: Boolean = true,
        val expiresAtElapsedMs: Long = Long.MAX_VALUE,
        val lockScreenAction: QuickRemoteAction? = null,
        val authorizationStillValid: (() -> Boolean)? = null,
    )

    private val commandQueue = Channel<CommandRequest>(capacity = COMMAND_QUEUE_CAPACITY)

    init {
        scope.launch {
            for (request in commandQueue) {
                try {
                    val success = executeImmediate(request)
                    request.result?.complete(success)
                } catch (e: CancellationException) {
                    request.result?.cancel(e)
                    throw e
                } catch (e: Exception) {
                    Diagnostics.exception(appContext, "remote_session", "command_loop", e)
                    _connectionState.value = ConnectionState.Disconnected
                    _connectionError.value = friendlyError(e)
                    request.result?.complete(false)
                }
            }
        }
        scope.launch {
            settingsRepository.lockScreenControls.collect {
                lockScreenControlsEnabled = it
                Diagnostics.updateRuntimeState(lockScreenControls = it)
            }
        }
        scope.launch {
            connectionState.collect { state ->
                Diagnostics.updateRuntimeState(connection = DiagnosticToken(state.name))
                Diagnostics.record(appContext, "remote_session", "connection", "state" to state)
                TileService.requestListeningState(
                    appContext,
                    ComponentName(appContext, RemoteTileService::class.java),
                )
            }
        }
    }

    fun setUiOwner(active: Boolean) {
        synchronized(this) { uiOwner = active }
        if (!active) scope.launch { disconnectIfUnused() }
    }

    fun setQuickRemoteOwner(active: Boolean) {
        synchronized(this) { quickRemoteOwner = active }
        if (!active) scope.launch { disconnectIfUnused() }
    }

    fun setPanelOwner(active: Boolean) {
        synchronized(this) { panelOwner = active }
        if (!active) scope.launch { disconnectIfUnused() }
    }

    fun setLockScreenRemoteOwner(active: Boolean) {
        synchronized(this) { lockScreenRemoteOwner = active }
        if (!active) scope.launch { disconnectIfUnused() }
    }

    /** One-use capability for the non-exported panel to steer MainActivity. */
    fun issueOpenFullRemoteToken(): String = synchronized(this) {
        UUID.randomUUID().toString().also { openFullRemoteToken = it }
    }

    fun consumeOpenFullRemoteToken(token: String?): Boolean = synchronized(this) {
        if (token == null || token != openFullRemoteToken) return@synchronized false
        openFullRemoteToken = null
        true
    }

    suspend fun connect(device: DiscoveredAtv, credentials: HapCredentials): Boolean =
        sessionMutex.withLock {
            connectLocked(device, credentials) { !keyguard.isKeyguardLocked }
        }

    suspend fun connectLastPaired(
        requireUnlocked: Boolean = true,
        authorizationStillValid: () -> Boolean = { true },
    ): Boolean = sessionMutex.withLock {
        if (!authorizationStillValid()) return@withLock false
        if (requireUnlocked && keyguard.isKeyguardLocked) return@withLock false
        if (client != null && _connectionState.value == ConnectionState.Connected) return@withLock true
        val connected = connectLastPairedLocked {
            authorizationStillValid() && (!requireUnlocked || !keyguard.isKeyguardLocked)
        }
        if (!authorizationStillValid() || (requireUnlocked && keyguard.isKeyguardLocked)) {
            closeLocked()
            false
        } else {
            connected
        }
    }

    suspend fun reconnect(): Boolean = sessionMutex.withLock {
        if (keyguard.isKeyguardLocked) return@withLock false
        val device = _activeDevice.value ?: settingsRepository.lastDevice()
        if (device == null) {
            val connected = connectLastPairedLocked { !keyguard.isKeyguardLocked }
            if (keyguard.isKeyguardLocked) {
                closeLocked()
                return@withLock false
            }
            return@withLock connected
        }
        val creds = credentials ?: credentialsRepository.load(device.name)?.let { stored ->
            runCatching { HapCredentials.parse(stored) }.getOrNull()
        } ?: return@withLock failLocked("Pairing credentials are invalid")
        val connected = connectLocked(device, creds) { !keyguard.isKeyguardLocked }
        if (keyguard.isKeyguardLocked) {
            closeLocked()
            false
        } else {
            connected
        }
    }

    fun launchCommand(
        requireUnlocked: Boolean = true,
        allowReconnect: Boolean = true,
        maxAgeMs: Long? = null,
        lockScreenAction: QuickRemoteAction? = null,
        authorizationStillValid: (() -> Boolean)? = null,
        block: suspend (CompanionClient) -> Unit,
    ) {
        commandQueue.trySend(
            CommandRequest(
                block = block,
                requireUnlocked = requireUnlocked,
                allowReconnect = allowReconnect,
                expiresAtElapsedMs = expiryFor(maxAgeMs),
                lockScreenAction = lockScreenAction,
                authorizationStillValid = authorizationStillValid,
            ),
        )
    }

    suspend fun execute(
        requireUnlocked: Boolean = true,
        allowReconnect: Boolean = true,
        maxAgeMs: Long? = null,
        lockScreenAction: QuickRemoteAction? = null,
        authorizationStillValid: (() -> Boolean)? = null,
        block: suspend (CompanionClient) -> Unit,
    ): Boolean {
        val result = CompletableDeferred<Boolean>()
        commandQueue.send(
            CommandRequest(
                block = block,
                result = result,
                requireUnlocked = requireUnlocked,
                allowReconnect = allowReconnect,
                expiresAtElapsedMs = expiryFor(maxAgeMs),
                lockScreenAction = lockScreenAction,
                authorizationStillValid = authorizationStillValid,
            ),
        )
        return result.await()
    }

    private suspend fun executeImmediate(request: CommandRequest): Boolean =
        sessionMutex.withLock {
            try {
                // Discard stale queued input after every visible/foreground
                // owner has gone away; it must not resurrect a hidden socket.
                if (!hasOwner()) return@withLock false
                if (request.authorizationStillValid?.invoke() == false) return@withLock false
                if (request.isExpired()) return@withLock false
                if (!isCommandAllowed(request, consumeLockedRate = false)) return@withLock false
                if (client == null && !request.allowReconnect) return@withLock false
                if (
                    client == null &&
                    !connectLastPairedLocked { request.isStillAuthorizedForConnection() }
                ) {
                    return@withLock false
                }
                val current = client ?: return@withLock false
                // Connecting can take several seconds. Re-check immediately
                // before the command so closed UI and queued shade input
                // cannot cross a newly displayed lock screen.
                if (!hasOwner()) return@withLock false
                if (request.authorizationStillValid?.invoke() == false) return@withLock false
                if (request.isExpired()) return@withLock false
                if (!isCommandAllowed(request, consumeLockedRate = true)) {
                    if (request.requireUnlocked && keyguard.isKeyguardLocked) closeLocked()
                    return@withLock false
                }
                withTimeout(COMMAND_TIMEOUT_MS) { request.block(current) }
                true
            } catch (e: TimeoutCancellationException) {
                closeLocked()
                _connectionError.value = "Apple TV did not respond"
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeLocked()
                _connectionError.value = friendlyError(e)
                false
            }
        }

    fun queueTouch(x: Long, y: Long, phase: TouchPhase) {
        commandQueue.trySend(
            CommandRequest(
                block = { it.touchEvent(x, y, phase) },
                requireUnlocked = true,
            ),
        )
    }

    private fun isCommandAllowed(
        request: CommandRequest,
        consumeLockedRate: Boolean,
    ): Boolean {
        val lockScreenAction = request.lockScreenAction
        if (lockScreenAction != null) {
            if (!LockScreenRemotePolicy.canExecute(lockScreenAction, true, lockScreenControlsEnabled)) {
                return false
            }
            if (consumeLockedRate && !lockedRateLimiter.tryAcquire(lockScreenAction)) return false
        }
        if (!keyguard.isKeyguardLocked) return true
        if (request.requireUnlocked) return false
        return lockScreenAction != null
    }

    private fun expiryFor(maxAgeMs: Long?): Long = maxAgeMs
        ?.coerceAtLeast(0L)
        ?.let { SystemClock.elapsedRealtime() + it }
        ?: Long.MAX_VALUE

    private fun CommandRequest.isExpired(): Boolean =
        expiresAtElapsedMs != Long.MAX_VALUE && SystemClock.elapsedRealtime() > expiresAtElapsedMs

    private fun CommandRequest.isStillAuthorizedForConnection(): Boolean =
        hasOwner() &&
            authorizationStillValid?.invoke() != false &&
            !isExpired() &&
            isCommandAllowed(this, consumeLockedRate = false)

    suspend fun disconnect(force: Boolean = false) = sessionMutex.withLock {
        if (!force && hasOwner()) return@withLock
        closeLocked(graceful = true)
    }

    private suspend fun disconnectIfUnused() {
        disconnect(force = false)
    }

    private fun hasOwner(): Boolean = synchronized(this) {
        uiOwner || panelOwner || quickRemoteOwner || lockScreenRemoteOwner
    }

    private suspend fun connectLastPairedLocked(
        stillAllowed: () -> Boolean = { true },
    ): Boolean {
        if (!stillAllowed()) return false
        var device = settingsRepository.lastDevice()
        if (!stillAllowed()) return false
        var stored = device?.let { credentialsRepository.load(it.name) }
        if (!stillAllowed()) return false

        if (device == null || stored == null) {
            val name = credentialsRepository.pairedDeviceNames().sorted().firstOrNull()
                ?: return failLocked("Pair an Apple TV in the app first")
            if (!stillAllowed()) return false
            stored = credentialsRepository.load(name)
                ?: return failLocked("Pairing credentials are unavailable")
            if (!stillAllowed()) return false
            device = discovery.resolveByName(name)
                ?: return failLocked("Apple TV is not reachable on this Wi-Fi network")
            if (!stillAllowed()) return false
        }

        val selectedDevice = device ?: return failLocked("No paired Apple TV is available")
        val storedCredentials = stored ?: return failLocked("Pairing credentials are unavailable")
        val parsed = runCatching { HapCredentials.parse(storedCredentials) }.getOrElse {
            return failLocked("Pairing credentials are invalid")
        }
        return connectLocked(selectedDevice, parsed, stillAllowed)
    }

    private suspend fun connectLocked(
        initialDevice: DiscoveredAtv,
        newCredentials: HapCredentials,
        stillAllowed: () -> Boolean = { true },
    ): Boolean {
        if (!stillAllowed()) return false
        if (
            client != null &&
            _connectionState.value == ConnectionState.Connected &&
            _activeDevice.value?.name == initialDevice.name
        ) {
            return true
        }

        closeLocked()
        _connectionState.value = ConnectionState.Connecting
        _connectionError.value = null

        var target = initialDevice
        var lastError: Exception = IOException("Connection failed")
        repeat(RECONNECT_ATTEMPTS) { attempt ->
            if (!stillAllowed()) {
                closeLocked()
                return false
            }
            if (attempt > 0) {
                delay(RECONNECT_DELAY_MS)
                if (!stillAllowed()) {
                    closeLocked()
                    return false
                }
                target = discovery.resolveByName(initialDevice.name) ?: initialDevice
                if (!stillAllowed()) {
                    closeLocked()
                    return false
                }
            }

            var candidate: CompanionClient? = null
            try {
                val transport = SocketTransport.connect(target.host, target.port, CONNECT_TIMEOUT_MS)
                val newClient = CompanionClient(CompanionConnection(transport), newCredentials)
                candidate = newClient
                if (!stillAllowed()) {
                    runCatching { newClient.close() }
                    closeLocked()
                    return false
                }
                withTimeout(SESSION_TIMEOUT_MS) {
                    connectWhileAuthorized(newClient, stillAllowed)
                }
                if (!stillAllowed()) {
                    runCatching { newClient.close() }
                    closeLocked()
                    return false
                }
                settingsRepository.setLastDevice(target)
                if (!stillAllowed()) {
                    runCatching { newClient.close() }
                    closeLocked()
                    return false
                }
                client = newClient
                credentials = newCredentials
                _activeDevice.value = target
                _connectionState.value = ConnectionState.Connected
                _connectionError.value = null
                observeKeyboard(newClient)
                return true
            } catch (_: ConnectionAuthorizationRevoked) {
                runCatching { candidate?.close() }
                closeLocked()
                return false
            } catch (e: TimeoutCancellationException) {
                runCatching { candidate?.close() }
                lastError = e
            } catch (e: CancellationException) {
                runCatching { candidate?.close() }
                _connectionState.value = ConnectionState.Disconnected
                throw e
            } catch (e: Exception) {
                runCatching { candidate?.close() }
                lastError = e
            }
        }

        return failLocked(friendlyError(lastError))
    }

    private suspend fun connectWhileAuthorized(
        newClient: CompanionClient,
        stillAllowed: () -> Boolean,
    ) = coroutineScope {
        val connection = async { newClient.connect() }
        while (!connection.isCompleted) {
            if (!stillAllowed()) {
                connection.cancel()
                throw ConnectionAuthorizationRevoked()
            }
            delay(CONNECTION_AUTH_POLL_MS)
        }
        connection.await()
    }

    private fun observeKeyboard(newClient: CompanionClient) {
        keyboardJob?.cancel()
        keyboardJob = scope.launch {
            newClient.keyboardFocus.collect { _keyboardFocus.value = it }
        }
    }

    private suspend fun closeLocked(graceful: Boolean = false) {
        keyboardJob?.cancel()
        keyboardJob = null
        _keyboardFocus.value = KeyboardFocusState.Unknown
        val current = client
        client = null
        credentials = null
        if (current != null) {
            if (graceful) {
                try {
                    withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { current.disconnect() }
                } catch (e: CancellationException) {
                    runCatching { current.close() }
                    throw e
                } catch (_: Exception) {
                    // A best-effort protocol close failed; force-close below.
                }
            }
            runCatching { current.close() }
        }
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun failLocked(message: String): Boolean {
        runCatching { client?.close() }
        client = null
        credentials = null
        _connectionState.value = ConnectionState.Disconnected
        _connectionError.value = message
        return false
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is ConnectException -> "Apple TV is unreachable"
        is SocketTimeoutException -> "Connection timed out"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private const val RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 500L
        private const val CONNECT_TIMEOUT_MS = 2_500
        private const val SESSION_TIMEOUT_MS = 15_000L
        private const val COMMAND_TIMEOUT_MS = 10_000L
        private const val DISCONNECT_TIMEOUT_MS = 2_000L
        private const val COMMAND_QUEUE_CAPACITY = 256
        private const val CONNECTION_AUTH_POLL_MS = 50L

        @Volatile
        private var instance: RemoteSessionManager? = null

        fun get(context: Context): RemoteSessionManager =
            instance ?: synchronized(this) {
                instance ?: RemoteSessionManager(context).also { instance = it }
            }
    }
}

private class ConnectionAuthorizationRevoked : Exception()
