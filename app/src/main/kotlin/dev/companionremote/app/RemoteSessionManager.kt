package dev.companionremote.app

import android.content.Context
import dev.companionremote.app.data.CredentialsRepository
import dev.companionremote.app.data.SettingsRepository
import dev.companionremote.app.discovery.AtvDiscovery
import dev.companionremote.app.discovery.DiscoveredAtv
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
import kotlinx.coroutines.channels.Channel
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()

    private var client: CompanionClient? = null
    private var credentials: HapCredentials? = null
    private var keyboardJob: Job? = null
    private var uiOwner = false
    private var panelOwner = false
    private var quickRemoteOwner = false
    private var openFullRemoteToken: String? = null

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
    )

    private val commandQueue = Channel<CommandRequest>(capacity = COMMAND_QUEUE_CAPACITY)

    init {
        scope.launch {
            for (request in commandQueue) {
                try {
                    val success = executeImmediate(request.block)
                    request.result?.complete(success)
                } catch (e: CancellationException) {
                    request.result?.cancel(e)
                    throw e
                } catch (e: Exception) {
                    _connectionState.value = ConnectionState.Disconnected
                    _connectionError.value = friendlyError(e)
                    request.result?.complete(false)
                }
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
        sessionMutex.withLock { connectLocked(device, credentials) }

    suspend fun connectLastPaired(): Boolean = sessionMutex.withLock {
        if (client != null && _connectionState.value == ConnectionState.Connected) return@withLock true
        connectLastPairedLocked()
    }

    suspend fun reconnect(): Boolean = sessionMutex.withLock {
        val device = _activeDevice.value ?: settingsRepository.lastDevice()
            ?: return@withLock connectLastPairedLocked()
        val creds = credentials ?: credentialsRepository.load(device.name)?.let { stored ->
            runCatching { HapCredentials.parse(stored) }.getOrNull()
        } ?: return@withLock failLocked("Pairing credentials are invalid")
        connectLocked(device, creds)
    }

    fun launchCommand(block: suspend (CompanionClient) -> Unit) {
        commandQueue.trySend(CommandRequest(block))
    }

    suspend fun execute(block: suspend (CompanionClient) -> Unit): Boolean {
        val result = CompletableDeferred<Boolean>()
        commandQueue.send(CommandRequest(block, result))
        return result.await()
    }

    private suspend fun executeImmediate(block: suspend (CompanionClient) -> Unit): Boolean =
        sessionMutex.withLock {
            try {
                // Discard stale queued input after every visible/foreground
                // owner has gone away; it must not resurrect a hidden socket.
                if (!hasOwner()) return@withLock false
                if (client == null && !connectLastPairedLocked()) return@withLock false
                val current = client ?: return@withLock false
                withTimeout(COMMAND_TIMEOUT_MS) { block(current) }
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
            CommandRequest(block = { it.touchEvent(x, y, phase) }),
        )
    }

    suspend fun disconnect(force: Boolean = false) = sessionMutex.withLock {
        if (!force && hasOwner()) return@withLock
        closeLocked(graceful = true)
    }

    private suspend fun disconnectIfUnused() {
        disconnect(force = false)
    }

    private fun hasOwner(): Boolean = synchronized(this) { uiOwner || panelOwner || quickRemoteOwner }

    private suspend fun connectLastPairedLocked(): Boolean {
        var device = settingsRepository.lastDevice()
        var stored = device?.let { credentialsRepository.load(it.name) }

        if (device == null || stored == null) {
            val name = credentialsRepository.pairedDeviceNames().sorted().firstOrNull()
                ?: return failLocked("Pair an Apple TV in the app first")
            stored = credentialsRepository.load(name)
                ?: return failLocked("Pairing credentials are unavailable")
            device = discovery.resolveByName(name)
                ?: return failLocked("Apple TV is not reachable on this Wi-Fi network")
        }

        val selectedDevice = device ?: return failLocked("No paired Apple TV is available")
        val storedCredentials = stored ?: return failLocked("Pairing credentials are unavailable")
        val parsed = runCatching { HapCredentials.parse(storedCredentials) }.getOrElse {
            return failLocked("Pairing credentials are invalid")
        }
        return connectLocked(selectedDevice, parsed)
    }

    private suspend fun connectLocked(
        initialDevice: DiscoveredAtv,
        newCredentials: HapCredentials,
    ): Boolean {
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
            if (attempt > 0) {
                delay(RECONNECT_DELAY_MS)
                target = discovery.resolveByName(initialDevice.name) ?: initialDevice
            }

            var candidate: CompanionClient? = null
            try {
                val transport = SocketTransport.connect(target.host, target.port, CONNECT_TIMEOUT_MS)
                val newClient = CompanionClient(CompanionConnection(transport), newCredentials)
                candidate = newClient
                withTimeout(SESSION_TIMEOUT_MS) { newClient.connect() }
                client = newClient
                credentials = newCredentials
                _activeDevice.value = target
                _connectionState.value = ConnectionState.Connected
                _connectionError.value = null
                settingsRepository.setLastDevice(target)
                observeKeyboard(newClient)
                return true
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

        @Volatile
        private var instance: RemoteSessionManager? = null

        fun get(context: Context): RemoteSessionManager =
            instance ?: synchronized(this) {
                instance ?: RemoteSessionManager(context).also { instance = it }
            }
    }
}
