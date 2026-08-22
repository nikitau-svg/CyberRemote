package dev.companionremote.app

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.quicksettings.TileService
import dev.companionremote.app.data.CredentialsRepository
import dev.companionremote.app.data.HomeNetworkRepository
import dev.companionremote.app.data.SettingsRepository
import dev.companionremote.app.discovery.AtvDiscovery
import dev.companionremote.app.discovery.DiscoveredAtv
import dev.companionremote.app.diagnostics.Diagnostics
import dev.companionremote.app.diagnostics.Diagnostics.DiagnosticToken
import dev.companionremote.app.nowplaying.NowPlayingSnapshot
import dev.companionremote.app.nowplaying.NowPlayingSource
import dev.companionremote.app.nowplaying.ArtworkPayload
import dev.companionremote.app.nowplaying.PlaybackStatus
import dev.companionremote.app.nowplaying.PositionAnchor
import dev.companionremote.app.nowplaying.SnapshotFreshness
import dev.companionremote.app.quick.LockScreenRemotePolicy
import dev.companionremote.app.quick.LockedRemoteRateLimiter
import dev.companionremote.app.quick.QuickRemoteAction
import dev.companionremote.app.quick.RemoteTileService
import dev.companionremote.protocol.client.CompanionClient
import dev.companionremote.protocol.client.CompanionPlaybackState
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
    private val homeNetworkRepository = HomeNetworkRepository(appContext)
    private val discovery = AtvDiscovery(appContext)
    private val keyguard = appContext.getSystemService(KeyguardManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val lockedRateLimiter = LockedRemoteRateLimiter { SystemClock.elapsedRealtime() }

    private var client: CompanionClient? = null
    private var credentials: HapCredentials? = null
    private var keyboardJob: Job? = null
    private var nowPlayingJob: Job? = null
    private var connectionTerminationJob: Job? = null
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

    private val _nowPlaying = MutableStateFlow(NowPlayingSnapshot.Disconnected)
    val nowPlaying: StateFlow<NowPlayingSnapshot> = _nowPlaying

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
            val authorization = homeNetworkRepository.automaticAuthorization(credentials.atvId)
                ?: return@withLock failLocked(HOME_NETWORK_REQUIRED)
            connectLocked(device, credentials, authorization) {
                !keyguard.isKeyguardLocked && authorization.isStillValid()
            }
        }

    /**
     * Explicit device-list selection may establish/move the home binding, but
     * only after pair-verify cryptographically authenticates the stored HAP ID.
     * Automatic entry points never call this method.
     */
    suspend fun connectUserSelected(
        device: DiscoveredAtv,
        credentials: HapCredentials,
    ): Boolean = sessionMutex.withLock {
        val connected = connectLocked(device, credentials) { !keyguard.isKeyguardLocked }
        if (connected) homeNetworkRepository.bind(device, credentials.atvId)
        connected
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
        val active = _activeDevice.value
        val storedName = active?.name
        val creds = credentials ?: storedName?.let { credentialsRepository.load(it) }?.let { stored ->
            runCatching { HapCredentials.parse(stored) }.getOrNull()
        }
        if (active == null || creds == null) {
            val connected = connectLastPairedLocked { !keyguard.isKeyguardLocked }
            if (keyguard.isKeyguardLocked) {
                closeLocked()
                return@withLock false
            }
            return@withLock connected
        }
        val authorization = homeNetworkRepository.automaticAuthorization(creds.atvId)
            ?: return@withLock failLocked(HOME_NETWORK_REQUIRED)
        val connected = connectLocked(active, creds, authorization) {
            !keyguard.isKeyguardLocked && authorization.isStillValid()
        }
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

    /**
     * Best-effort request for a fresh Companion Now Playing push.
     *
     * Unlike a remote command this never enters [commandQueue], reconnects a
     * missing session or changes playback. The session lock keeps the active
     * client from being closed while its one fire-and-forget event is sent.
     */
    suspend fun refreshNowPlaying(): Boolean = sessionMutex.withLock {
        val current = client
        val active = current != null && _connectionState.value == ConnectionState.Connected
        Diagnostics.record(
            appContext,
            "now_playing_refresh",
            "attempt",
            "active_client" to active,
        )
        if (!active || current == null) {
            Diagnostics.record(
                appContext,
                "now_playing_refresh",
                "result",
                "success" to false,
                "outcome" to DiagnosticToken("inactive_client"),
            )
            return@withLock false
        }

        val outcome = try {
            withTimeout(NOW_PLAYING_REFRESH_TIMEOUT_MS) {
                current.refreshNowPlaying()
            }
            NowPlayingRefreshOutcome.Sent
        } catch (_: TimeoutCancellationException) {
            NowPlayingRefreshOutcome.TimedOut
        } catch (e: CancellationException) {
            Diagnostics.record(
                appContext,
                "now_playing_refresh",
                "result",
                "success" to false,
                "outcome" to DiagnosticToken("cancelled"),
            )
            throw e
        } catch (_: Exception) {
            NowPlayingRefreshOutcome.Failed
        }
        Diagnostics.record(
            appContext,
            "now_playing_refresh",
            "result",
            "success" to (outcome == NowPlayingRefreshOutcome.Sent),
            "outcome" to DiagnosticToken(outcome.diagnosticToken),
        )
        outcome == NowPlayingRefreshOutcome.Sent
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
        val networkAuthorization = homeNetworkRepository.automaticAuthorization()
            ?: return failLocked(HOME_NETWORK_REQUIRED)
        if (!stillAllowed() || !networkAuthorization.isStillValid()) return false
        val selectedDevice = homeNetworkRepository.device(networkAuthorization.binding)
        val storedCredentials = credentialsRepository.load(selectedDevice.name)
            ?: return failLocked("Pairing credentials are unavailable")
        val parsed = runCatching { HapCredentials.parse(storedCredentials) }.getOrElse {
            return failLocked("Pairing credentials are invalid")
        }
        val deviceAuthorization = homeNetworkRepository.automaticAuthorization(parsed.atvId)
            ?: return failLocked(HOME_NETWORK_REQUIRED)
        return connectLocked(selectedDevice, parsed, deviceAuthorization) {
            stillAllowed() && deviceAuthorization.isStillValid()
        }
    }

    private suspend fun connectLocked(
        initialDevice: DiscoveredAtv,
        newCredentials: HapCredentials,
        authorization: dev.companionremote.app.data.HomeNetworkAuthorization? = null,
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
                target = discovery.resolveByName(
                    name = initialDevice.name,
                    network = authorization?.network,
                    authorizationStillValid = stillAllowed,
                ) ?: initialDevice
                if (!stillAllowed()) {
                    closeLocked()
                    return false
                }
            }

            var candidate: CompanionClient? = null
            try {
                val transport = SocketTransport.connect(
                    host = target.host,
                    port = target.port,
                    timeoutMs = CONNECT_TIMEOUT_MS,
                    socketFactory = authorization?.network?.socketFactory
                        ?: javax.net.SocketFactory.getDefault(),
                )
                val newConnection = CompanionConnection(transport)
                val newClient = CompanionClient(newConnection, newCredentials)
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
                // Endpoint updates are accepted only for the same keyed HAP
                // identity on the currently bound LAN. Failure to persist a
                // cache refresh must not invalidate an authenticated session.
                runCatching {
                    homeNetworkRepository.updateEndpointIfAuthorized(target, newCredentials.atvId)
                }
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
                observeNowPlaying(newClient)
                observeTermination(newClient, newConnection)
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

    private fun observeNowPlaying(newClient: CompanionClient) {
        nowPlayingJob?.cancel()
        nowPlayingJob = scope.launch {
            newClient.nowPlaying.collect { update ->
                if (update == null) return@collect
                val observedAtElapsedMs = update.capturedAtNanos / 1_000_000L
                _nowPlaying.value = NowPlayingSnapshot(
                    status = when (update.playbackState) {
                        CompanionPlaybackState.Playing -> PlaybackStatus.Playing
                        CompanionPlaybackState.Paused -> PlaybackStatus.Paused
                        CompanionPlaybackState.Unknown -> PlaybackStatus.Unknown
                    },
                    freshness = SnapshotFreshness.Live,
                    source = NowPlayingSource.Companion,
                    title = update.title,
                    artist = update.artist,
                    album = update.album,
                    seriesName = update.seriesName,
                    episodeNumber = update.episodeNumber,
                    durationMs = update.durationMs,
                    position = update.positionMs?.let { positionMs ->
                        PositionAnchor(
                            positionMs = positionMs,
                            capturedAtElapsedMs = observedAtElapsedMs,
                            playbackRate = update.playbackRate ?: 0.0,
                        )
                    },
                    contentId = update.contentId,
                    artworkId = update.artworkId,
                    artwork = update.artworkId?.let { artworkId ->
                        ArtworkPayload(
                            id = artworkId,
                            urlTemplate = update.artworkUrlTemplate,
                            data = update.artworkData,
                        )
                    },
                    observedAtElapsedMs = observedAtElapsedMs,
                )
                Diagnostics.record(
                    appContext,
                    "now_playing",
                    "companion_update",
                    "state" to update.playbackState,
                    "has_title" to (update.title != null),
                    "has_duration" to (update.durationMs != null),
                    "has_position" to (update.positionMs != null),
                    "has_artwork" to (update.artworkData != null || update.artworkUrlTemplate != null),
                )
            }
        }
    }

    /** Clear stale native playback state as soon as the Companion socket dies. */
    private fun observeTermination(
        newClient: CompanionClient,
        connection: CompanionConnection,
    ) {
        connectionTerminationJob?.cancel()
        connectionTerminationJob = scope.launch {
            connection.termination.await()
            sessionMutex.withLock {
                if (client !== newClient) return@withLock
                client = null
                credentials = null
                keyboardJob?.cancel()
                keyboardJob = null
                nowPlayingJob?.cancel()
                nowPlayingJob = null
                connectionTerminationJob = null
                _keyboardFocus.value = KeyboardFocusState.Unknown
                _nowPlaying.value = NowPlayingSnapshot.Disconnected
                _connectionState.value = ConnectionState.Disconnected
                _connectionError.value = "Apple TV connection closed"
                Diagnostics.record(appContext, "remote_session", "connection_terminated")
            }
        }
    }

    private suspend fun closeLocked(graceful: Boolean = false) {
        connectionTerminationJob?.cancel()
        connectionTerminationJob = null
        keyboardJob?.cancel()
        keyboardJob = null
        nowPlayingJob?.cancel()
        nowPlayingJob = null
        _keyboardFocus.value = KeyboardFocusState.Unknown
        _nowPlaying.value = NowPlayingSnapshot.Disconnected
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
        connectionTerminationJob?.cancel()
        connectionTerminationJob = null
        keyboardJob?.cancel()
        keyboardJob = null
        nowPlayingJob?.cancel()
        nowPlayingJob = null
        _keyboardFocus.value = KeyboardFocusState.Unknown
        _nowPlaying.value = NowPlayingSnapshot.Disconnected
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
        private const val NOW_PLAYING_REFRESH_TIMEOUT_MS = 2_000L
        private const val DISCONNECT_TIMEOUT_MS = 2_000L
        private const val COMMAND_QUEUE_CAPACITY = 256
        private const val CONNECTION_AUTH_POLL_MS = 50L
        private const val HOME_NETWORK_REQUIRED =
            "Connect to the Wi-Fi network where this Apple TV was paired"

        @Volatile
        private var instance: RemoteSessionManager? = null

        fun get(context: Context): RemoteSessionManager =
            instance ?: synchronized(this) {
                instance ?: RemoteSessionManager(context).also { instance = it }
            }
    }
}

private enum class NowPlayingRefreshOutcome(val diagnosticToken: String) {
    Sent("sent"),
    TimedOut("timed_out"),
    Failed("failed"),
}

private class ConnectionAuthorizationRevoked : Exception()
