package dev.companionremote.app.quick

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.service.quicksettings.TileService
import dev.companionremote.app.BuildConfig
import dev.companionremote.app.ConnectionState
import dev.companionremote.app.R
import dev.companionremote.app.RemoteSessionManager
import dev.companionremote.app.data.SettingsRepository
import dev.companionremote.app.data.HomeNetworkAuthorization
import dev.companionremote.app.data.HomeNetworkRepository
import dev.companionremote.app.discovery.LocalNetworkIdentity
import dev.companionremote.app.diagnostics.Diagnostics
import dev.companionremote.app.diagnostics.Diagnostics.DiagnosticToken
import dev.companionremote.app.diagnostics.Diagnostics.RuntimePlayback
import dev.companionremote.app.nowplaying.ArtworkPayload
import dev.companionremote.app.nowplaying.NowPlayingSnapshot
import dev.companionremote.app.nowplaying.PlaybackCommand
import dev.companionremote.app.nowplaying.PlaybackStatus
import dev.companionremote.app.nowplaying.commandFor
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Holds the encrypted Apple TV connection and exposes Android-native media controls. */
class RemoteControlService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandQueue = Channel<QueuedAction>(capacity = COMMAND_QUEUE_CAPACITY)
    private val ingressRateLimiter = LockedRemoteRateLimiter { SystemClock.elapsedRealtime() }
    private var connectJob: Job? = null
    private var artworkJob: Job? = null
    private var networkRevalidationJob: Job? = null
    private var reconnectRetryJob: Job? = null
    private var departureGraceJob: Job? = null
    private var continuityRecoveryJob: Job? = null
    private var returnedMediaReleaseJob: Job? = null
    private var awayStopJob: Job? = null
    private var nowPlayingRefreshJob: Job? = null
    private var mediaSessionRetryJob: Job? = null
    private var networkRevalidationGeneration = 0L
    private lateinit var session: RemoteSessionManager
    private lateinit var settings: SettingsRepository
    private lateinit var homeNetworks: HomeNetworkRepository
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var keyguard: KeyguardManager
    private val lifecyclePreferences by lazy {
        getSharedPreferences(LIFECYCLE_PREFERENCES, Context.MODE_PRIVATE)
    }
    private var mediaSession: MediaSession? = null
    private var mediaSessionCreationFailed = false
    private var mediaSessionCreationFailures = 0
    private var foregroundStarted = false
    @Volatile private var acceptingCommands = false
    private var lockScreenControlsEnabled = false
    private var loadedArtworkId: String? = null
    private var artworkBitmap: Bitmap? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var homeAuthorization: HomeNetworkAuthorization? = null
    @Volatile private var homeDepartureSuspected = false
    @Volatile private var continuityRecoveryArmed = false
    private val continuityRecoveryGeneration = AtomicLong(0L)
    private var continuityOriginAuthorization: HomeNetworkAuthorization? = null
    private val continuityRecoveryAttempts = BoundedContinuityRecoveryAttempts()
    private var retainedMediaSnapshot: NotificationSnapshot? = null
    private var retainedMediaRequiresReconnect = false
    private val retainedMediaReconnectGate = RetainedMediaReconnectGate()
    private var networkReconnectArmed = false
    private val homeNetworkPolicy = HomeNetworkServicePolicy()
    private val hybridHomeNetworkPolicy = HybridHomeNetworkPolicy()
    private val reconnectBackoff = BoundedReconnectBackoff()
    private val networkCallbackGate = NetworkCallbackRevalidationGate()
    private val explicitNowPlayingRefreshPolicy = ExplicitNowPlayingRefreshPolicy()
    private var lastObservedConnectionState = ConnectionState.Disconnected
    @Volatile private var capabilityToken = UUID.randomUUID().toString()
    private var lastSnapshot = NotificationSnapshot(
        state = ConnectionState.Disconnected,
        deviceName = null,
        error = null,
        lockScreenControlsEnabled = false,
        nowPlaying = NowPlayingSnapshot.Disconnected,
    )

    override fun onCreate() {
        super.onCreate()
        session = RemoteSessionManager.get(this)
        settings = SettingsRepository(this)
        homeNetworks = HomeNetworkRepository(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        try {
            RemoteNotification.createChannel(this)
        } catch (error: RuntimeException) {
            Diagnostics.exception(this, "notification", "create_channel", error)
            throw error
        }
        Diagnostics.updateRuntimeState(
            serviceRunning = true,
            foregroundStarted = false,
            mediaSessionActive = false,
            playback = RuntimePlayback.Unknown,
            keyguardLocked = keyguard.isKeyguardLocked,
            lastStopReason = DiagnosticToken("none"),
        )
        Diagnostics.record(this, "remote_service", "created", "sdk" to Build.VERSION.SDK_INT)

        scope.launch {
            for (queued in commandQueue) executeAction(queued)
        }
        scope.launch {
            combine(
                session.connectionState,
                session.activeDevice,
                session.connectionError,
                settings.lockScreenControls,
                session.nowPlaying,
            ) { state, device, error, lockControls, nowPlaying ->
                NotificationSnapshot(state, device?.name, error, lockControls, nowPlaying)
            }.collect { snapshot ->
                lastSnapshot = snapshot
                retainedMediaSnapshot?.let { retained ->
                    retainedMediaReconnectGate.observe(snapshot.state)
                    if (
                        shouldReleaseRetainedMedia(
                            departureSuspected = homeDepartureSuspected,
                            requiresReconnect = retainedMediaRequiresReconnect,
                            sawConnectionBreak = retainedMediaReconnectGate.sawConnectionBreak,
                            retainedObservedAtElapsedMs = retained.nowPlaying.observedAtElapsedMs,
                            currentConnectionState = snapshot.state,
                            currentAuthoritative = snapshot.nowPlaying.isAuthoritative,
                            currentObservedAtElapsedMs = snapshot.nowPlaying.observedAtElapsedMs,
                        )
                    ) {
                        clearRetainedMedia("fresh_live_snapshot", refresh = false)
                    }
                }
                lockScreenControlsEnabled = snapshot.lockScreenControlsEnabled
                Diagnostics.updateRuntimeState(
                    lockScreenControls = snapshot.lockScreenControlsEnabled,
                    keyguardLocked = keyguard.isKeyguardLocked,
                    connection = DiagnosticToken(snapshot.state.name),
                )
                Diagnostics.record(
                    this@RemoteControlService,
                    "remote_service",
                    "snapshot",
                    "connection" to snapshot.state,
                    "lock_controls" to snapshot.lockScreenControlsEnabled,
                    "has_error" to (snapshot.error != null),
                    "now_playing" to snapshot.nowPlaying.status,
                    "now_playing_source" to snapshot.nowPlaying.source,
                )
                val presentationSnapshot = mediaPresentationSnapshot(snapshot)
                reconcileMediaPresentation(presentationSnapshot)
                if (!homeDepartureSuspected) {
                    updateArtwork(
                        presentationSnapshot.nowPlaying.artwork.takeIf {
                            shouldPresentMedia(presentationSnapshot)
                        },
                    )
                }
                updateMediaSession(presentationSnapshot)
                refreshNotification()
                handleConnectionLifecycle(snapshot.state)
                TileService.requestListeningState(
                    this@RemoteControlService,
                    ComponentName(this@RemoteControlService, RemoteTileService::class.java),
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val commandKind = when (intent?.action) {
            ACTION_START -> "start"
            ACTION_COMMAND -> "command"
            ACTION_STOP -> "stop"
            null -> "null_restart"
            else -> "unknown"
        }
        Diagnostics.record(
            this,
            "remote_service",
            "start_command",
            "kind" to DiagnosticToken(commandKind),
            "locked" to keyguard.isKeyguardLocked,
            "foreground" to foregroundStarted,
        )
        if (intent?.action == ACTION_STOP) {
            val requestedCapability = intent.getStringExtra(EXTRA_CAPABILITY)
            if (!isControlLeaseActive(requestedCapability.orEmpty())) {
                Diagnostics.record(
                    this,
                    "remote_service",
                    "stop_rejected",
                    "reason" to DiagnosticToken("stale_lease"),
                )
                if (!foregroundStarted) stopSelf()
                return START_NOT_STICKY
            }
            stopRemote("action_stop")
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_COMMAND) {
            if (!acceptingCommands || intent.getStringExtra(EXTRA_CAPABILITY) != capabilityToken) {
                Diagnostics.record(
                    this,
                    "remote_service",
                    "command_rejected",
                    "reason" to DiagnosticToken("inactive_or_stale"),
                )
                if (!foregroundStarted) stopRemote("invalid_command")
                return START_NOT_STICKY
            }
            QuickRemoteAction.fromWireValue(intent.getStringExtra(EXTRA_COMMAND))
                ?.let(::enqueueAction)
            ensureForeground()
            return START_STICKY
        }

        val expectedCapability = intent?.getStringExtra(EXTRA_EXPECTED_CAPABILITY)
        if (
            expectedCapability != null &&
            (
                expectedCapability != capabilityToken ||
                    !RemoteControlLeaseRegistry.isActive(expectedCapability)
            )
        ) {
            Diagnostics.record(
                this,
                "remote_service",
                "start_rejected",
                "reason" to DiagnosticToken("stale_expected_lease"),
            )
            if (!foregroundStarted) stopRemote("stale_expected_lease")
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START && !acceptingCommands) {
            // A deliberate fresh user start begins a new bounded lifecycle.
            clearPersistedAwayDeadline()
        }
        val controlLeaseReady = activateControlLease()
        ensureForeground()
        if (!controlLeaseReady) {
            // Running without a LAN observer could leave controls connected
            // after the phone leaves home. Abort the lease fail-closed.
            stopRemote("network_callback_unavailable")
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                // Opening controls while already locked must not silently create
                // a fresh network session. A lease armed while unlocked may later
                // reconnect passively while locked when the home LAN returns.
                val canArmWhileUnlocked = !keyguard.isKeyguardLocked
                if (canArmWhileUnlocked) {
                    networkReconnectArmed = true
                }
                // A fresh locked start must still enter the bounded away
                // lifecycle when there is no home LAN; it just may not
                // create a new TV connection while locked.
                scheduleHomeNetworkRevalidation(
                    "explicit_start",
                    immediate = true,
                    rearm = canArmWhileUnlocked,
                )
                requestExplicitNowPlayingRefresh()
            }
            null -> {
                // START_STICKY is used only for an already-running foreground
                // control lease. Process death invalidates every old PendingIntent;
                // this fresh lease gets a fresh capability and notification.
                networkReconnectArmed = true
                persistedAwayRemainingMs()?.let { remainingMs ->
                    if (remainingMs <= 0L) {
                        stopRemote("away_timeout_restored")
                        return START_NOT_STICKY
                    }
                    val restoredDeadline = SystemClock.elapsedRealtime() +
                        remainingMs.coerceAtMost(HOME_NETWORK_WATCHER_TIMEOUT_MS)
                    hybridHomeNetworkPolicy.restoreAwayWatching(restoredDeadline)
                    scheduleAwayStop(restoredDeadline)
                    Diagnostics.record(
                        this,
                        "home_network",
                        "away_watcher_restored",
                        "remaining_ms" to remainingMs,
                    )
                }
                scheduleHomeNetworkRevalidation(
                    "sticky_restart",
                    immediate = true,
                    rearm = true,
                )
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Diagnostics.record(this, "remote_service", "destroyed", "foreground" to foregroundStarted)
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        acceptingCommands = false
        revokeCapabilities()
        commandQueue.close()
        connectJob?.cancel()
        connectJob = null
        networkRevalidationJob?.cancel()
        networkRevalidationJob = null
        reconnectRetryJob?.cancel()
        reconnectRetryJob = null
        departureGraceJob?.cancel()
        departureGraceJob = null
        cancelContinuityRecovery(clearArm = true)
        returnedMediaReleaseJob?.cancel()
        returnedMediaReleaseJob = null
        awayStopJob?.cancel()
        awayStopJob = null
        nowPlayingRefreshJob?.cancel()
        nowPlayingRefreshJob = null
        mediaSessionRetryJob?.cancel()
        mediaSessionRetryJob = null
        reconnectBackoff.reset()
        unregisterNetworkCallback()
        homeAuthorization = null
        homeDepartureSuspected = false
        clearRetainedMedia("service_destroyed", refresh = false)
        networkReconnectArmed = false
        homeNetworkPolicy.reset()
        hybridHomeNetworkPolicy.reset()
        networkCallbackGate.reset()
        artworkJob?.cancel()
        artworkJob = null
        artworkBitmap = null
        loadedArtworkId = null
        mediaSessionCreationFailed = false
        mediaSessionCreationFailures = 0
        releaseMediaSession()
        Diagnostics.updateRuntimeState(
            serviceRunning = false,
            foregroundStarted = false,
            mediaSessionActive = false,
            playback = RuntimePlayback.Released,
        )
        session.setQuickRemoteOwner(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Diagnostics.record(this, "remote_service", "task_removed")
        super.onTaskRemoved(rootIntent)
    }

    private fun createMediaSession(leaseToken: String): MediaSession =
        MediaSession(this, "CyberRemote").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    Diagnostics.record(this@RemoteControlService, "media_session", "callback_play")
                    if (isControlLeaseActive(leaseToken)) enqueueAction(QuickRemoteAction.Play)
                }

                override fun onPause() {
                    Diagnostics.record(this@RemoteControlService, "media_session", "callback_pause")
                    if (isControlLeaseActive(leaseToken)) enqueueAction(QuickRemoteAction.Pause)
                }

                override fun onStop() {
                    Diagnostics.record(this@RemoteControlService, "media_session", "callback_stop")
                    if (isControlLeaseActive(leaseToken)) stopRemote("media_callback")
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (!isControlLeaseActive(leaseToken)) return
                    when (action) {
                        MEDIA_SKIP_BACK_15 -> enqueueAction(QuickRemoteAction.SkipBack15)
                        MEDIA_SKIP_FORWARD_15 -> enqueueAction(QuickRemoteAction.SkipForward15)
                        MEDIA_VOLUME_DOWN -> enqueueAction(QuickRemoteAction.VolumeDown)
                        MEDIA_VOLUME_UP -> enqueueAction(QuickRemoteAction.VolumeUp)
                    }
                }
            })
            setSessionActivity(
                RemoteNotification.remoteActivityIntent(
                    this@RemoteControlService,
                    903,
                    leaseToken,
                ),
            )
            isActive = true
            Diagnostics.updateRuntimeState(mediaSessionActive = true)
            Diagnostics.record(this@RemoteControlService, "media_session", "active")
        }

    private fun isControlLeaseActive(leaseToken: String): Boolean =
        acceptingCommands &&
            leaseToken == capabilityToken &&
            RemoteControlLeaseRegistry.isActive(leaseToken)

    private fun releaseMediaSession() {
        val existed = mediaSession != null
        mediaSession?.let { current ->
            current.setCallback(null)
            current.isActive = false
            current.release()
        }
        mediaSession = null
        Diagnostics.updateRuntimeState(mediaSessionActive = false, playback = RuntimePlayback.Released)
        Diagnostics.record(this, "media_session", "released", "existed" to existed)
    }

    private fun enqueueAction(action: QuickRemoteAction) {
        if (!acceptingCommands) {
            Diagnostics.record(
                this,
                "remote_service",
                "command_rejected",
                "reason" to DiagnosticToken("not_accepting"),
                "action" to action,
            )
            return
        }
        if (homeDepartureSuspected) {
            Diagnostics.record(
                this,
                "remote_service",
                "command_rejected",
                "reason" to DiagnosticToken("network_departure_grace"),
                "action" to action,
            )
            return
        }
        val locked = keyguard.isKeyguardLocked
        if (locked) {
            if (!LockScreenRemotePolicy.canExecute(action, true, lockScreenControlsEnabled)) {
                Diagnostics.record(
                    this,
                    "remote_service",
                    "command_rejected",
                    "reason" to DiagnosticToken("lock_policy"),
                    "action" to action,
                )
                return
            }
            if (!ingressRateLimiter.tryAcquire(action)) {
                Diagnostics.record(
                    this,
                    "remote_service",
                    "command_rejected",
                    "reason" to DiagnosticToken("rate_limit"),
                    "action" to action,
                )
                return
            }
        }
        commandQueue.trySend(
            QueuedAction(
                action = action,
                issuedAtElapsedMs = SystemClock.elapsedRealtime(),
                wasLockedAtIngress = locked,
                capabilityToken = capabilityToken,
            ),
        )
        Diagnostics.record(
            this,
            "remote_service",
            "command_queued",
            "action" to action,
            "locked" to locked,
        )
    }

    private suspend fun executeAction(queued: QueuedAction) {
        if (queued.capabilityToken != capabilityToken) {
            Diagnostics.record(this, "remote_service", "command_dropped", "reason" to DiagnosticToken("lease_changed"))
            return
        }
        val maxAge = if (queued.wasLockedAtIngress) {
            LOCKED_COMMAND_MAX_AGE_MS
        } else {
            UNLOCKED_COMMAND_MAX_AGE_MS
        }
        val remainingAge = maxAge - (SystemClock.elapsedRealtime() - queued.issuedAtElapsedMs)
        if (remainingAge <= 0L) {
            Diagnostics.record(this, "remote_service", "command_dropped", "reason" to DiagnosticToken("expired"))
            return
        }

        val action = queued.action
        if (queued.wasLockedAtIngress) {
            if (!LockScreenRemotePolicy.canExecute(action, true, lockScreenControlsEnabled)) {
                Diagnostics.record(this, "remote_service", "command_dropped", "reason" to DiagnosticToken("lock_policy_changed"), "action" to action)
                return
            }
        } else if (keyguard.isKeyguardLocked) {
            Diagnostics.record(this, "remote_service", "command_dropped", "reason" to DiagnosticToken("locked_after_ingress"), "action" to action)
            return
        }

        val succeeded = action.execute(
            session = session,
            requireUnlocked = !queued.wasLockedAtIngress,
            allowReconnect = !queued.wasLockedAtIngress,
            maxAgeMs = remainingAge,
            authorizationStillValid = {
                isServiceNetworkAuthorized(queued.capabilityToken)
            },
        )
        Diagnostics.record(
            this,
            "remote_service",
            "command_result",
            "action" to action,
            "success" to succeeded,
            "locked" to queued.wasLockedAtIngress,
        )
    }

    private fun isServiceNetworkAuthorized(leaseToken: String): Boolean =
        !homeDepartureSuspected &&
            isControlLeaseActive(leaseToken) &&
            homeAuthorization?.isStillValid() == true

    private fun registerNetworkCallback(): Boolean {
        if (networkCallback != null) return true
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    handleLanCallback(
                        networkCallbackGate.onAvailable(network.networkHandle),
                        "available",
                    )
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                scope.launch {
                    handleLanCallback(
                        networkCallbackGate.onCapabilitiesChanged(
                            network.networkHandle,
                            networkCapabilities.toLanCapsKey(),
                        ),
                        "capabilities",
                    )
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                scope.launch {
                    handleLanCallback(
                        networkCallbackGate.onLinkPropertiesChanged(
                            network.networkHandle,
                            linkProperties.toLanLinkKey(),
                        ),
                        "link_properties",
                    )
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    handleLanCallback(
                        networkCallbackGate.onLost(network.networkHandle),
                        "lost",
                    )
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
            Diagnostics.record(this, "home_network", "callback_registered")
            return true
        } catch (error: RuntimeException) {
            Diagnostics.exception(this, "home_network", "callback_register", error)
            // Fail closed if Android refuses network observation.
            networkReconnectArmed = false
            homeAuthorization = null
            homeDepartureSuspected = false
            cancelContinuityRecovery(clearArm = true)
            clearRetainedMedia("callback_registration_failed", refresh = false)
            connectJob?.cancel()
            connectJob = null
            reconcileMediaPresentation(lastSnapshot)
            updateArtwork(null)
            refreshNotification()
            scope.launch { session.disconnect(force = true) }
            return false
        }
    }

    private fun handleLanCallback(action: LanCallbackAction, trigger: String) {
        when (action) {
            LanCallbackAction.Ignore -> Unit
            LanCallbackAction.Revalidate -> scheduleHomeNetworkRevalidation(trigger)
            LanCallbackAction.AuthorizedLoss -> suspectHomeNetworkDeparture(trigger)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            .onFailure { error ->
                Diagnostics.exception(this, "home_network", "callback_unregister", error)
            }
        Diagnostics.record(this, "home_network", "callback_unregistered")
    }

    /** Collapse callback bursts without resetting the finite TV reconnect budget. */
    private fun scheduleHomeNetworkRevalidation(
        trigger: String,
        immediate: Boolean = false,
        rearm: Boolean = false,
    ) {
        if (rearm) {
            reconnectRetryJob?.cancel()
            reconnectRetryJob = null
            reconnectBackoff.reset()
            homeNetworkPolicy.reset()
        }
        val generation = ++networkRevalidationGeneration
        networkRevalidationJob?.cancel()
        networkRevalidationJob = scope.launch {
            if (!immediate) delay(NETWORK_CALLBACK_DEBOUNCE_MS)
            reconcileHomeNetwork(trigger, generation)
        }
    }

    private suspend fun reconcileHomeNetwork(trigger: String, generation: Long) {
        val leaseToken = capabilityToken
        if (!isControlLeaseActive(leaseToken)) return
        val previousAuthorization = homeAuthorization
        val authorization = homeNetworks.automaticAuthorization()
            ?.takeIf { it.isStillValid() }
        if (generation != networkRevalidationGeneration || !isControlLeaseActive(leaseToken)) {
            Diagnostics.record(
                this,
                "home_network",
                "revalidation_stale",
                "trigger" to DiagnosticToken(trigger),
            )
            return
        }
        if (authorization == null && departureGraceJob?.isActive == true) {
            Diagnostics.record(
                this,
                "home_network",
                "departure_unconfirmed",
                "trigger" to DiagnosticToken(trigger),
            )
            startContinuityRecovery(trigger)
            return
        }
        if (authorization == null && previousAuthorization != null) {
            Diagnostics.record(
                this,
                "home_network",
                "revalidated",
                "trigger" to DiagnosticToken(trigger),
                "authorized" to false,
                "action" to HomeNetworkServiceAction.Disconnect,
            )
            // Preserve the last strict authorization as an in-process continuity
            // capability. It no longer authorizes commands because departure is
            // marked synchronously and isStillValid() also fails on the changed
            // LinkProperties. Clearing it here would release media and would lose
            // the only proof that this is a handover rather than a cold start.
            networkRevalidationJob = null
            suspectHomeNetworkDeparture("revalidation", previousAuthorization)
            return
        }
        var retainMediaAcrossReturn = false
        var handoverOccurred = false
        if (authorization != null) {
            retainMediaAcrossReturn = retainedMediaSnapshot != null &&
                (homeDepartureSuspected || returnedMediaReleaseJob?.isActive == true)
            cancelContinuityRecovery(clearArm = true)
            if (!retainMediaAcrossReturn) {
                clearRetainedMedia("strict_authorized", refresh = false)
            }
            clearPersistedAwayDeadline()
            val networkCommit = networkCallbackGate.commitAuthorization(
                authorization.network.networkHandle,
            )
            if (networkCommit == LanAuthorizationCommit.Handover) {
                // Successor callbacks can arrive before the old handle's
                // onLost. Close the command gate immediately, even when no
                // departure grace had been entered yet.
                homeDepartureSuspected = true
            }
            // Commit the successor capability before any suspendable socket
            // teardown. If another Network callback arrives while disconnect
            // is running, its grace window must inherit this successor rather
            // than the obsolete origin. Commands remain blocked by
            // homeDepartureSuspected until the handover is fully validated.
            homeAuthorization = authorization
            val transition = hybridHomeNetworkPolicy.observe(
                authorized = true,
                nowMs = SystemClock.elapsedRealtime(),
            )
            cancelAwayTimers("home_authorized")
            if (transition.edgeAction == HybridHomeNetworkEdgeAction.ReturnedHome) {
                Diagnostics.record(this, "home_network", "returned_home")
                reconnectRetryJob?.cancel()
                reconnectRetryJob = null
                reconnectBackoff.reset()
                // The socket can die during the bounded handover grace. Treat a
                // confirmed return as a real authorization edge so a stale
                // `previous=true` cannot suppress the one required reconnect.
                homeNetworkPolicy.reset()
            }
            if (networkCommit == LanAuthorizationCommit.Handover) {
                handoverOccurred = true
                if (
                    retainedMediaSnapshot == null &&
                    mediaSession != null &&
                    shouldUseMediaPresentation(
                        homeAuthorized = true,
                        connectionState = lastSnapshot.state,
                    )
                ) {
                    // A strict successor can precede old onLost, so there may
                    // be no earlier departure callback to capture the card.
                    // Retain it before force-disconnecting the old socket.
                    retainedMediaSnapshot = lastSnapshot
                }
                retainMediaAcrossReturn = retainedMediaSnapshot != null
                retainedMediaRequiresReconnect = retainedMediaSnapshot != null
                // Each new Network replacement needs its own observed
                // disconnect/reconnect edge. A break remembered from an older
                // handover must not let a delayed Connected emission release
                // the retained card during this newer transition.
                retainedMediaReconnectGate.rearm()
                connectJob?.cancel()
                connectJob = null
                reconnectRetryJob?.cancel()
                reconnectRetryJob = null
                nowPlayingRefreshJob?.cancel()
                nowPlayingRefreshJob = null
                reconnectBackoff.reset()
                homeNetworkPolicy.reset()
                session.disconnect(force = true)
                if (
                    generation != networkRevalidationGeneration ||
                    !isControlLeaseActive(leaseToken) ||
                    !authorization.isStillValid()
                ) {
                    Diagnostics.record(
                        this,
                        "home_network",
                        "handover_stale_after_disconnect",
                    )
                    networkRevalidationJob = null
                    suspectHomeNetworkDeparture("handover_stale", authorization)
                    return
                }
                Diagnostics.record(this, "home_network", "handover")
            }
        }
        if (authorization == null) homeAuthorization = null
        // Keep commands fail-closed for the whole suspendable handover. The
        // new strict capability must be installed before the transition gate
        // is reopened; the old Network can still look valid until its delayed
        // onLost callback arrives.
        if (authorization != null) homeDepartureSuspected = false
        if (authorization != null && retainMediaAcrossReturn) {
            settleRetainedMediaAfterAuthorization(forceHold = handoverOccurred)
        }
        val presentationSnapshot = mediaPresentationSnapshot(lastSnapshot)
        reconcileMediaPresentation(presentationSnapshot)
        updateArtwork(
            presentationSnapshot.nowPlaying.artwork.takeIf {
                shouldPresentMedia(presentationSnapshot)
            },
        )
        updateMediaSession(presentationSnapshot)
        refreshNotification()
        if (
            authorization != null &&
            previousAuthorization == null &&
            session.connectionState.value == ConnectionState.Connected
        ) {
            // The foreground service can attach to a Companion session that
            // was already connected by the activity. Its Connected edge was
            // observed before LAN authorization existed, so refresh once now.
            scheduleNowPlayingRefresh()
        }
        val action = homeNetworkPolicy.evaluate(
            leaseActive = isControlLeaseActive(leaseToken),
            reconnectArmed = networkReconnectArmed,
            authorized = authorization != null,
            connectedOrConnecting = session.connectionState.value != ConnectionState.Disconnected,
            connectionAttemptActive = connectJob?.isActive == true,
        )
        Diagnostics.record(
            this,
            "home_network",
            "revalidated",
            "trigger" to DiagnosticToken(trigger),
            "authorized" to (authorization != null),
            "action" to action,
        )
        when (action) {
            HomeNetworkServiceAction.None -> Unit
            HomeNetworkServiceAction.Disconnect -> {
                // Avoid cancelling the coroutine that is performing this departure.
                networkRevalidationJob = null
                suspectHomeNetworkDeparture("revalidation", previousAuthorization)
            }
            HomeNetworkServiceAction.Reconnect -> {
                if (authorization != null) attemptAuthorizedConnect("network_edge")
            }
        }
    }

    private fun suspectHomeNetworkDeparture(
        reason: String,
        originAuthorization: HomeNetworkAuthorization? = homeAuthorization,
    ) {
        if (awayStopJob?.isActive == true) return
        if (departureGraceJob?.isActive == true) {
            // A successor Network can become visible before Android reports the
            // old handle lost. Coalesce those callbacks into one latest-candidate
            // retry instead of dropping the useful edge or polling indefinitely.
            startContinuityRecovery(reason)
            return
        }
        val origin = originAuthorization ?: homeAuthorization
        if (origin != null) homeAuthorization = origin
        continuityOriginAuthorization = origin
        continuityRecoveryArmed = origin != null
        continuityRecoveryAttempts.reset()
        returnedMediaReleaseJob?.cancel()
        returnedMediaReleaseJob = null
        val transition = hybridHomeNetworkPolicy.observe(
            authorized = false,
            nowMs = SystemClock.elapsedRealtime(),
        )
        val deadline = transition.nextEvaluationAtMs ?: return
        networkRevalidationGeneration += 1L
        networkRevalidationJob?.cancel()
        networkRevalidationJob = null
        homeDepartureSuspected = true
        if (
            retainedMediaSnapshot == null &&
            mediaSession != null &&
            shouldUseMediaPresentation(
                homeAuthorized = origin != null,
                connectionState = lastSnapshot.state,
            )
        ) {
            retainedMediaSnapshot = lastSnapshot
            retainedMediaRequiresReconnect = false
            retainedMediaReconnectGate.rearm()
            retainedMediaReconnectGate.observe(lastSnapshot.state)
        }
        connectJob?.cancel()
        connectJob = null
        reconnectRetryJob?.cancel()
        reconnectRetryJob = null
        nowPlayingRefreshJob?.cancel()
        nowPlayingRefreshJob = null
        refreshNotification()
        Diagnostics.record(
            this,
            "home_network",
            "departure_suspected",
            "reason" to DiagnosticToken(reason),
            "grace_ms" to HOME_NETWORK_DEPARTURE_GRACE_MS,
            "media_retained" to (retainedMediaSnapshot != null),
        )
        departureGraceJob = scope.launch {
            delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            continuityRecoveryJob?.takeIf { it.isActive }?.let { recovery ->
                withTimeoutOrNull(CONTINUITY_RECOVERY_DEADLINE_EXTENSION_MS) {
                    recovery.join()
                }
            }
            departureGraceJob = null
            cancelContinuityRecovery(clearArm = true)
            val authorization = homeNetworks.automaticAuthorization()
                ?.takeIf { it.isStillValid() }
            if (authorization != null) {
                Diagnostics.record(this@RemoteControlService, "home_network", "departure_cancelled")
                scheduleHomeNetworkRevalidation("departure_grace_recovered", immediate = true)
            } else {
                val deadlineTransition = hybridHomeNetworkPolicy.onDeadline(
                    SystemClock.elapsedRealtime(),
                )
                if (deadlineTransition.edgeAction == HybridHomeNetworkEdgeAction.DepartureConfirmed) {
                    confirmHomeNetworkDeparture(reason, deadlineTransition.nextEvaluationAtMs)
                }
            }
        }
        startContinuityRecovery(reason)
    }

    private suspend fun confirmHomeNetworkDeparture(reason: String, stopDeadlineMs: Long?) {
        val departureGeneration = networkRevalidationGeneration
        departureGraceJob?.cancel()
        departureGraceJob = null
        networkRevalidationJob?.cancel()
        networkRevalidationJob = null
        homeDepartureSuspected = false
        cancelContinuityRecovery(clearArm = true)
        clearRetainedMedia("departure_confirmed", refresh = false)
        homeAuthorization = null
        connectJob?.cancel()
        connectJob = null
        reconnectRetryJob?.cancel()
        reconnectRetryJob = null
        nowPlayingRefreshJob?.cancel()
        nowPlayingRefreshJob = null
        reconnectBackoff.reset()
        networkCallbackGate.clearAuthorization()
        homeNetworkPolicy.evaluate(
            leaseActive = isControlLeaseActive(capabilityToken),
            reconnectArmed = networkReconnectArmed,
            authorized = false,
            connectedOrConnecting = session.connectionState.value != ConnectionState.Disconnected,
            connectionAttemptActive = false,
        )
        Diagnostics.record(
            this,
            "home_network",
            "departed",
            "reason" to DiagnosticToken(reason),
        )
        // Install the bounded watcher before the suspending disconnect. A
        // concurrent home return can then cancel it instead of letting this
        // old confirmation install a stale timer after recovery.
        persistAwayDeadline(stopDeadlineMs)
        scheduleAwayStop(stopDeadlineMs)
        // Release the MediaSession and publish the quiet watcher before the
        // graceful protocol disconnect, which can suspend for up to its timeout.
        // This prevents stale controls from remaining actionable after the
        // departure has already been confirmed.
        reconcileMediaPresentation(lastSnapshot)
        updateArtwork(null)
        updateMediaSession(lastSnapshot)
        refreshNotification()
        session.disconnect(force = true)
        if (
            departureGeneration != networkRevalidationGeneration ||
            homeAuthorization != null
        ) {
            Diagnostics.record(this, "home_network", "departure_interrupted")
            homeNetworkPolicy.reset()
            scheduleHomeNetworkRevalidation(
                "departure_disconnect_recovered",
                immediate = true,
            )
            return
        }
    }

    /**
     * A continuity hint is intentionally weaker than authorization. This path
     * is armed only by a strict authorization that existed immediately before
     * the callback edge, and promotion happens only after HAP pair-verify.
     */
    private fun startContinuityRecovery(trigger: String) {
        if (
            !continuityRecoveryArmed ||
            !homeDepartureSuspected ||
            continuityOriginAuthorization == null
        ) {
            return
        }
        when (
            continuityRecoveryAttempts.request(
                recoveryActive = continuityRecoveryJob?.isActive == true,
            )
        ) {
            ContinuityRecoveryRequest.Start -> launchContinuityRecovery(trigger)
            ContinuityRecoveryRequest.RetryPending -> Diagnostics.record(
                this,
                "home_network",
                "continuity_retry_pending",
                "trigger" to DiagnosticToken(trigger),
            )
            ContinuityRecoveryRequest.Ignore -> Unit
        }
    }

    private fun launchContinuityRecovery(trigger: String) {
        val originAuthorization = continuityOriginAuthorization ?: return
        val leaseToken = capabilityToken
        val generation = continuityRecoveryGeneration.incrementAndGet()
        Diagnostics.record(
            this,
            "home_network",
            "continuity_recovery_started",
            "trigger" to DiagnosticToken(trigger),
        )
        continuityRecoveryJob = scope.launch {
            var recoveredSuccessfully = false
            val stillAllowed = {
                continuityRecoveryGeneration.get() == generation &&
                    continuityRecoveryArmed &&
                    homeDepartureSuspected &&
                    continuityOriginAuthorization === originAuthorization &&
                    isControlLeaseActive(leaseToken)
            }
            try {
                val recovered = session.recoverHomeNetworkContinuity(
                    origin = originAuthorization,
                    stillAllowed = stillAllowed,
                )
                    ?: return@launch
                if (!stillAllowed() || !recovered.isStillValid()) return@launch
                Diagnostics.record(
                    this@RemoteControlService,
                    "home_network",
                    "continuity_recovered",
                )
                recoveredSuccessfully = true
                scheduleHomeNetworkRevalidation(
                    "continuity_verified",
                    immediate = true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Keystore/DataStore/NSD failures must fail closed without an
                // uncaught root-coroutine exception taking down the process.
                Diagnostics.exception(
                    this@RemoteControlService,
                    "home_network",
                    "continuity_recovery",
                    error,
                )
            } finally {
                if (continuityRecoveryGeneration.get() == generation) {
                    continuityRecoveryJob = null
                    if (
                        continuityRecoveryAttempts.onFinished(recoveredSuccessfully) &&
                        continuityRecoveryArmed &&
                        homeDepartureSuspected &&
                        continuityOriginAuthorization === originAuthorization &&
                        isControlLeaseActive(leaseToken)
                    ) {
                        launchContinuityRecovery("pending_callback")
                    }
                }
            }
        }
    }

    private fun cancelContinuityRecovery(clearArm: Boolean) {
        continuityRecoveryGeneration.incrementAndGet()
        continuityRecoveryJob?.cancel()
        continuityRecoveryJob = null
        if (clearArm) {
            continuityRecoveryArmed = false
            continuityOriginAuthorization = null
            continuityRecoveryAttempts.reset()
        }
    }

    private fun settleRetainedMediaAfterAuthorization(forceHold: Boolean) {
        if (retainedMediaSnapshot == null) return
        retainedMediaRequiresReconnect = retainedMediaRequiresReconnect || forceHold
        // Stable duplicate callbacks must not move the deadline. A new handover,
        // however, starts a fresh reconnect budget: Samsung can replace the
        // Network handle more than once before Now Playing becomes authoritative.
        // Re-arm the fail-safe so an older transition cannot release the card in
        // the middle of the newer one.
        if (returnedMediaReleaseJob?.isActive == true) {
            if (!forceHold) return
            returnedMediaReleaseJob?.cancel()
        }
        returnedMediaReleaseJob = scope.launch {
            delay(RETURNED_MEDIA_RETENTION_MS)
            returnedMediaReleaseJob = null
            if (!homeDepartureSuspected) {
                clearRetainedMedia("return_timeout", refresh = true)
            }
        }
    }

    private fun clearRetainedMedia(reason: String, refresh: Boolean) {
        if (retainedMediaSnapshot == null) return
        retainedMediaSnapshot = null
        retainedMediaRequiresReconnect = false
        retainedMediaReconnectGate.rearm()
        returnedMediaReleaseJob?.cancel()
        returnedMediaReleaseJob = null
        Diagnostics.record(
            this,
            "home_network",
            "retained_media_released",
            "reason" to DiagnosticToken(reason),
        )
        if (!refresh) return
        val snapshot = lastSnapshot
        reconcileMediaPresentation(snapshot)
        updateArtwork(snapshot.nowPlaying.artwork.takeIf { shouldPresentMedia(snapshot) })
        updateMediaSession(snapshot)
        refreshNotification()
    }

    private fun scheduleAwayStop(stopDeadlineMs: Long?) {
        if (awayStopJob?.isActive == true) return
        val deadline = stopDeadlineMs ?: return
        Diagnostics.record(
            this,
            "home_network",
            "away_stop_scheduled",
            "delay_ms" to (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
        )
        awayStopJob = scope.launch {
            delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            awayStopJob = null
            val authorization = homeNetworks.automaticAuthorization()
                ?.takeIf { it.isStillValid() }
            if (authorization != null) {
                Diagnostics.record(this@RemoteControlService, "home_network", "away_stop_cancelled")
                scheduleHomeNetworkRevalidation("away_timeout_recovered", immediate = true)
            } else {
                val deadlineTransition = hybridHomeNetworkPolicy.onDeadline(
                    SystemClock.elapsedRealtime(),
                )
                if (deadlineTransition.edgeAction == HybridHomeNetworkEdgeAction.StopService) {
                    stopRemote("away_timeout")
                }
            }
        }
    }

    private fun cancelAwayTimers(reason: String) {
        val departureCancelled = departureGraceJob?.isActive == true
        val stopCancelled = awayStopJob?.isActive == true
        departureGraceJob?.cancel()
        departureGraceJob = null
        cancelContinuityRecovery(clearArm = true)
        awayStopJob?.cancel()
        awayStopJob = null
        if (departureCancelled || stopCancelled) {
            Diagnostics.record(
                this,
                "home_network",
                "away_timers_cancelled",
                "reason" to DiagnosticToken(reason),
            )
        }
    }

    private fun persistAwayDeadline(stopDeadlineElapsedMs: Long?) {
        val deadline = stopDeadlineElapsedMs ?: return
        val remainingMs = (deadline - SystemClock.elapsedRealtime())
            .coerceIn(0L, HOME_NETWORK_WATCHER_TIMEOUT_MS)
        lifecyclePreferences.edit()
            .putLong(PREF_AWAY_DEADLINE_EPOCH_MS, System.currentTimeMillis() + remainingMs)
            .apply()
    }

    private fun persistedAwayRemainingMs(): Long? {
        val deadlineEpochMs = lifecyclePreferences.getLong(PREF_AWAY_DEADLINE_EPOCH_MS, 0L)
            .takeIf { it > 0L }
            ?: return null
        return deadlineEpochMs - System.currentTimeMillis()
    }

    private fun clearPersistedAwayDeadline() {
        if (!lifecyclePreferences.contains(PREF_AWAY_DEADLINE_EPOCH_MS)) return
        lifecyclePreferences.edit().remove(PREF_AWAY_DEADLINE_EPOCH_MS).apply()
    }

    private fun handleConnectionLifecycle(state: ConnectionState) {
        val previous = lastObservedConnectionState
        lastObservedConnectionState = state
        when (state) {
            ConnectionState.Connected -> {
                reconnectRetryJob?.cancel()
                reconnectRetryJob = null
                reconnectBackoff.reset()
                if (previous != ConnectionState.Connected) scheduleNowPlayingRefresh()
            }
            ConnectionState.Connecting -> {
                // A real attempt is already in progress; do not let an older
                // delayed retry race it.
                reconnectRetryJob?.cancel()
                reconnectRetryJob = null
            }
            ConnectionState.Disconnected -> {
                if (previous != ConnectionState.Disconnected) scheduleBoundedReconnect()
            }
        }
    }

    private fun scheduleBoundedReconnect() {
        if (!networkReconnectArmed || !acceptingCommands) return
        val authorization = homeAuthorization?.takeIf { it.isStillValid() } ?: return
        if (reconnectRetryJob?.isActive == true) return
        val delayMs = reconnectBackoff.nextDelayMs() ?: run {
            Diagnostics.record(this, "home_network", "retry_budget_exhausted")
            return
        }
        val leaseToken = capabilityToken
        val networkHandle = authorization.network.networkHandle
        Diagnostics.record(this, "home_network", "retry_scheduled", "delay_ms" to delayMs)
        reconnectRetryJob = scope.launch {
            delay(delayMs)
            reconnectRetryJob = null
            if (!isServiceNetworkAuthorized(leaseToken)) return@launch
            if (homeAuthorization?.network?.networkHandle != networkHandle) return@launch
            attemptAuthorizedConnect("retry")
        }
    }

    private fun attemptAuthorizedConnect(cause: String) {
        if (!networkReconnectArmed || !acceptingCommands || connectJob?.isActive == true) return
        val authorization = homeAuthorization?.takeIf { it.isStillValid() } ?: return
        val leaseToken = capabilityToken
        val networkHandle = authorization.network.networkHandle
        Diagnostics.record(
            this,
            "home_network",
            "connect_attempt",
            "cause" to DiagnosticToken(cause),
        )
        connectJob = scope.launch {
            session.connectLastPaired(
                requireUnlocked = false,
                authorizationStillValid = {
                    networkReconnectArmed &&
                        isControlLeaseActive(leaseToken) &&
                        homeAuthorization?.network?.networkHandle == networkHandle &&
                        authorization.isStillValid()
                },
            )
        }
    }

    /** Connect already sends the t=0 request; these are the two bounded follow-ups. */
    private fun scheduleNowPlayingRefresh() {
        nowPlayingRefreshJob?.cancel()
        val leaseToken = capabilityToken
        val networkHandle = homeAuthorization?.network?.networkHandle ?: return
        nowPlayingRefreshJob = scope.launch {
            var previousDelayMs = 0L
            for (targetDelayMs in NOW_PLAYING_REFRESH_DELAYS_MS) {
                delay(targetDelayMs - previousDelayMs)
                previousDelayMs = targetDelayMs
                if (
                    !isControlLeaseActive(leaseToken) ||
                    homeAuthorization?.network?.networkHandle != networkHandle ||
                    session.connectionState.value != ConnectionState.Connected
                ) {
                    return@launch
                }
                if (!session.nowPlaying.value.needsBoundedRefresh()) return@launch
                session.refreshNowPlaying()
            }
            nowPlayingRefreshJob = null
        }
    }

    /** One user-triggered refresh for an already-connected session; never a polling loop. */
    private fun requestExplicitNowPlayingRefresh() {
        val authorization = homeAuthorization
        val decision = explicitNowPlayingRefreshPolicy.evaluate(
            nowMs = SystemClock.elapsedRealtime(),
            homeAuthorized = authorization?.isStillValid() == true &&
                isControlLeaseActive(capabilityToken),
            connected = session.connectionState.value == ConnectionState.Connected,
            boundedRefreshPending = nowPlayingRefreshJob?.isActive == true,
        )
        Diagnostics.record(
            this,
            "now_playing_refresh",
            "user_request",
            "outcome" to DiagnosticToken(decision.name),
        )
        if (decision != ExplicitNowPlayingRefreshDecision.Scheduled || authorization == null) return

        val leaseToken = capabilityToken
        val networkHandle = authorization.network.networkHandle
        nowPlayingRefreshJob = scope.launch {
            if (
                isControlLeaseActive(leaseToken) &&
                homeAuthorization?.network?.networkHandle == networkHandle &&
                authorization.isStillValid() &&
                session.connectionState.value == ConnectionState.Connected
            ) {
                session.refreshNowPlaying()
            }
        }
    }

    private fun updateMediaSession(snapshot: NotificationSnapshot) {
        val currentSession = mediaSession ?: return
        val nowPlaying = snapshot.nowPlaying
        val fallbackStatus = connectionLabel(snapshot.state)
        val title = nowPlaying.title ?: "Apple TV Remote"
        val subtitle = nowPlaying.subtitle() ?: fallbackStatus
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
        nowPlaying.artist?.let {
            metadata.putString(MediaMetadata.METADATA_KEY_ARTIST, it)
        }
        nowPlaying.album?.let {
            metadata.putString(MediaMetadata.METADATA_KEY_ALBUM, it)
        }
        nowPlaying.durationMs?.takeIf { it > 0L }?.let {
            metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, it)
        }
        artworkBitmap?.let {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, it)
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
            metadata.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
        }
        currentSession.setMetadata(
            metadata.build(),
        )
        val androidPlaybackState = when (nowPlaying.status) {
            PlaybackStatus.Playing -> PlaybackState.STATE_PLAYING
            PlaybackStatus.Buffering -> PlaybackState.STATE_BUFFERING
            PlaybackStatus.Paused -> PlaybackState.STATE_PAUSED
            PlaybackStatus.Idle -> PlaybackState.STATE_STOPPED
            PlaybackStatus.Stopped -> PlaybackState.STATE_STOPPED
            PlaybackStatus.Unknown -> PlaybackState.STATE_PAUSED
        }
        val position = nowPlaying.positionAt(SystemClock.elapsedRealtime())
            ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
        val speed = if (nowPlaying.status == PlaybackStatus.Playing) 1f else 0f
        currentSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE,
                )
                // Android 13+ gives the first two custom actions the compact
                // back/forward slots. Keep relative seek ahead of volume so
                // both 15-second controls remain visible on the lock screen.
                .addCustomAction(
                    MEDIA_SKIP_BACK_15,
                    "Back 15 seconds",
                    R.drawable.ic_skip_back_15,
                )
                .addCustomAction(
                    MEDIA_SKIP_FORWARD_15,
                    "Forward 15 seconds",
                    R.drawable.ic_skip_forward_15,
                )
                .addCustomAction(MEDIA_VOLUME_DOWN, "Volume down", R.drawable.ic_volume_down)
                .addCustomAction(MEDIA_VOLUME_UP, "Volume up", R.drawable.ic_volume_up)
                .setState(
                    androidPlaybackState,
                    position,
                    speed,
                )
                .build(),
        )
        val playback = when (nowPlaying.status) {
            PlaybackStatus.Playing -> RuntimePlayback.Playing
            PlaybackStatus.Paused -> RuntimePlayback.Paused
            else -> RuntimePlayback.Unknown
        }
        Diagnostics.updateRuntimeState(
            mediaSessionActive = currentSession.isActive,
            playback = playback,
            connection = DiagnosticToken(snapshot.state.name),
            lockScreenControls = snapshot.lockScreenControlsEnabled,
            keyguardLocked = keyguard.isKeyguardLocked,
        )
        Diagnostics.record(
            this,
            "media_session",
            "state_updated",
            "playback" to playback,
            "connection" to snapshot.state,
            "source" to nowPlaying.source,
            "has_metadata" to (nowPlaying.title != null),
            "has_artwork" to (artworkBitmap != null),
        )
    }

    private fun shouldPresentMedia(snapshot: NotificationSnapshot): Boolean =
        shouldUseMediaPresentation(
            homeAuthorized = homeAuthorization != null,
            connectionState = snapshot.state,
            retainedDuringNetworkTransition = retainedMediaSnapshot != null,
        )

    private fun mediaPresentationSnapshot(snapshot: NotificationSnapshot): NotificationSnapshot {
        val retained = retainedMediaSnapshot ?: return snapshot
        return snapshot.copy(
            deviceName = snapshot.deviceName ?: retained.deviceName,
            nowPlaying = retained.nowPlaying,
        )
    }

    private fun reconcileMediaPresentation(snapshot: NotificationSnapshot) {
        if (!shouldPresentMedia(snapshot)) {
            mediaSessionRetryJob?.cancel()
            mediaSessionRetryJob = null
            mediaSessionCreationFailed = false
            mediaSessionCreationFailures = 0
            if (mediaSession != null) releaseMediaSession()
            return
        }
        if (mediaSession != null || mediaSessionCreationFailed) return
        Diagnostics.record(this, "media_session", "creating")
        mediaSession = try {
            createMediaSession(capabilityToken).also {
                mediaSessionCreationFailed = false
                mediaSessionCreationFailures = 0
            }
        } catch (error: RuntimeException) {
            Diagnostics.exception(this, "media_session", "create", error)
            mediaSessionCreationFailed = true
            mediaSessionCreationFailures += 1
            scheduleMediaSessionCreationRetry()
            null
        }
    }

    private fun scheduleMediaSessionCreationRetry() {
        if (
            mediaSessionCreationFailures > MAX_MEDIA_SESSION_CREATE_RETRIES ||
            mediaSessionRetryJob?.isActive == true
        ) {
            return
        }
        mediaSessionRetryJob = scope.launch {
            delay(MEDIA_SESSION_CREATE_RETRY_MS)
            mediaSessionRetryJob = null
            val presentationSnapshot = mediaPresentationSnapshot(lastSnapshot)
            if (!shouldPresentMedia(presentationSnapshot)) return@launch
            mediaSessionCreationFailed = false
            reconcileMediaPresentation(presentationSnapshot)
            updateMediaSession(presentationSnapshot)
            refreshNotification()
        }
    }

    /** Decode/fetch artwork once per media item; state pushes never poll the TV. */
    private fun updateArtwork(payload: ArtworkPayload?) {
        if (payload?.id == loadedArtworkId) return
        artworkJob?.cancel()
        artworkJob = null
        loadedArtworkId = payload?.id
        artworkBitmap = null
        if (payload == null) return

        artworkJob = scope.launch(Dispatchers.IO) {
            val bitmap = payload.data?.let(::decodeArtwork)
                ?: payload.urlTemplate?.let(::downloadArtwork)
            withContext(Dispatchers.Main.immediate) {
                if (lastSnapshot.nowPlaying.artwork?.id != payload.id) return@withContext
                artworkBitmap = bitmap
                Diagnostics.record(
                    this@RemoteControlService,
                    "now_playing",
                    "artwork_loaded",
                    "success" to (bitmap != null),
                    "embedded" to (payload.data != null),
                )
                updateMediaSession(mediaPresentationSnapshot(lastSnapshot))
                refreshNotification()
            }
        }
    }

    private fun downloadArtwork(template: String): Bitmap? {
        val rendered = template
            .replace("{w}", ARTWORK_EDGE_PX.toString())
            .replace("{h}", ARTWORK_EDGE_PX.toString())
            .replace("{f}", "jpg")
            .replace("{c}", "bb")
        val url = runCatching { URL(rendered) }.getOrNull() ?: return null
        // Keep the app's cleartext policy intact. Embedded/local MRP artwork is
        // handled without HTTP; remote templates must be HTTPS.
        if (url.protocol != "https") return null
        val connection = (url.openConnection() as? HttpURLConnection) ?: return null
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = ARTWORK_TIMEOUT_MS
            connection.readTimeout = ARTWORK_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "CyberRemote/${BuildConfig.VERSION_NAME}")
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_ARTWORK_BYTES) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(
                    contentLength.takeIf { it in 1..MAX_ARTWORK_BYTES }
                        ?.toInt()
                        ?: DEFAULT_ARTWORK_BUFFER_BYTES,
                )
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_ARTWORK_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            decodeArtwork(bytes)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty() || bytes.size > MAX_ARTWORK_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > ARTWORK_EDGE_PX * 2 ||
            bounds.outHeight / sampleSize > ARTWORK_EDGE_PX * 2
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun ensureForeground() {
        if (foregroundStarted) {
            Diagnostics.record(this, "notification", "foreground_already_started")
            return
        }
        Diagnostics.record(this, "notification", "foreground_start_attempt")
        try {
            startForeground(
                NOTIFICATION_ID,
                buildForegroundNotification(),
            )
            foregroundStarted = true
            Diagnostics.updateRuntimeState(foregroundStarted = true)
            Diagnostics.record(this, "notification", "foreground_started", "id" to NOTIFICATION_ID)
        } catch (error: RuntimeException) {
            Diagnostics.exception(this, "notification", "start_foreground", error)
            throw error
        }
    }

    private fun refreshNotification() {
        if (!foregroundStarted) return
        try {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildForegroundNotification(),
            )
            Diagnostics.record(this, "notification", "refreshed", "id" to NOTIFICATION_ID)
        } catch (error: RuntimeException) {
            Diagnostics.exception(this, "notification", "refresh", error)
            throw error
        }
    }

    private fun buildForegroundNotification(): Notification {
        val presentationSnapshot = mediaPresentationSnapshot(lastSnapshot)
        val currentSession = mediaSession
        return if (currentSession != null && shouldPresentMedia(presentationSnapshot)) {
            RemoteNotification.buildMedia(
                this,
                presentationSnapshot,
                currentSession.sessionToken,
                artworkBitmap,
                capabilityToken,
            )
        } else {
            RemoteNotification.buildService(
                this,
                lastSnapshot.state,
                waitingForHomeNetwork = homeAuthorization == null,
                capabilityToken = capabilityToken,
            )
        }
    }

    private fun stopRemote(reason: String) {
        Diagnostics.updateRuntimeState(lastStopReason = DiagnosticToken(reason))
        Diagnostics.record(this, "remote_service", "stopping", "reason" to DiagnosticToken(reason))
        acceptingCommands = false
        connectJob?.cancel()
        connectJob = null
        networkRevalidationJob?.cancel()
        networkRevalidationJob = null
        reconnectRetryJob?.cancel()
        reconnectRetryJob = null
        departureGraceJob?.cancel()
        departureGraceJob = null
        cancelContinuityRecovery(clearArm = true)
        returnedMediaReleaseJob?.cancel()
        returnedMediaReleaseJob = null
        awayStopJob?.cancel()
        awayStopJob = null
        nowPlayingRefreshJob?.cancel()
        nowPlayingRefreshJob = null
        mediaSessionRetryJob?.cancel()
        mediaSessionRetryJob = null
        reconnectBackoff.reset()
        unregisterNetworkCallback()
        homeAuthorization = null
        homeDepartureSuspected = false
        clearRetainedMedia("service_stopped", refresh = false)
        networkReconnectArmed = false
        homeNetworkPolicy.reset()
        hybridHomeNetworkPolicy.reset()
        networkCallbackGate.reset()
        clearPersistedAwayDeadline()
        artworkJob?.cancel()
        artworkJob = null
        artworkBitmap = null
        loadedArtworkId = null
        mediaSessionCreationFailed = false
        mediaSessionCreationFailures = 0
        releaseMediaSession()
        revokeCapabilities()
        foregroundStarted = false
        Diagnostics.updateRuntimeState(foregroundStarted = false)
        session.setQuickRemoteOwner(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun activateControlLease(): Boolean {
        if (acceptingCommands) {
            Diagnostics.record(this, "remote_service", "lease_already_active")
            return networkCallback != null
        }
        acceptingCommands = true
        mediaSessionCreationFailed = false
        mediaSessionCreationFailures = 0
        RemoteControlLeaseRegistry.activate(capabilityToken)
        session.setQuickRemoteOwner(true)
        val observationReady = registerNetworkCallback()
        if (!observationReady) {
            Diagnostics.record(this, "remote_service", "lease_activation_failed")
            return false
        }
        Diagnostics.record(this, "remote_service", "lease_activated")
        reconcileMediaPresentation(lastSnapshot)
        updateMediaSession(lastSnapshot)
        return true
    }

    private fun revokeCapabilities() {
        RemoteControlLeaseRegistry.revoke(capabilityToken)
        capabilityToken = UUID.randomUUID().toString()
    }

    companion object {
        internal const val ACTION_START = "dev.companionremote.quick.START"
        internal const val ACTION_COMMAND = "dev.companionremote.quick.COMMAND"
        internal const val ACTION_STOP = "dev.companionremote.quick.STOP"
        internal const val EXTRA_COMMAND = "remote_command"
        internal const val EXTRA_CAPABILITY = "remote_capability"
        internal const val EXTRA_EXPECTED_CAPABILITY = "expected_remote_capability"
        internal const val NOTIFICATION_ID = 4207
        private const val COMMAND_QUEUE_CAPACITY = 32
        private const val LOCKED_COMMAND_MAX_AGE_MS = 1_000L
        private const val UNLOCKED_COMMAND_MAX_AGE_MS = 3_000L
        private const val MEDIA_SKIP_BACK_15 = "dev.companionremote.media.SKIP_BACK_15"
        private const val MEDIA_SKIP_FORWARD_15 = "dev.companionremote.media.SKIP_FORWARD_15"
        private const val MEDIA_VOLUME_DOWN = "dev.companionremote.media.VOLUME_DOWN"
        private const val MEDIA_VOLUME_UP = "dev.companionremote.media.VOLUME_UP"
        private const val ARTWORK_EDGE_PX = 512
        private const val ARTWORK_TIMEOUT_MS = 4_000
        private const val MAX_ARTWORK_BYTES = 3L * 1024 * 1024
        private const val DEFAULT_ARTWORK_BUFFER_BYTES = 32 * 1024
        private const val NETWORK_CALLBACK_DEBOUNCE_MS = 350L
        // A recovery that starts near the end of the Network handover grace
        // may need one cached PairVerify, scoped mDNS, then one resolved
        // PairVerify (about 14 seconds total). Let that already-started proof
        // finish; this is still finite and never schedules background polling.
        private const val CONTINUITY_RECOVERY_DEADLINE_EXTENSION_MS = 15_000L
        // connectLocked can spend roughly 66 seconds across its three bounded
        // socket/session attempts and scoped endpoint resolutions.
        private const val RETURNED_MEDIA_RETENTION_MS = 75_000L
        private const val MEDIA_SESSION_CREATE_RETRY_MS = 1_500L
        private const val MAX_MEDIA_SESSION_CREATE_RETRIES = 1
        private const val LIFECYCLE_PREFERENCES = "remote_service_lifecycle"
        private const val PREF_AWAY_DEADLINE_EPOCH_MS = "away_deadline_epoch_ms"
        private val NOW_PLAYING_REFRESH_DELAYS_MS = longArrayOf(1_500L, 5_000L)

        fun start(context: Context, expectedCapability: String? = null) {
            Diagnostics.record(
                context,
                "service_api",
                "start_requested",
                "expected_lease" to (expectedCapability != null),
            )
            val intent = Intent(context, RemoteControlService::class.java).setAction(ACTION_START)
            if (expectedCapability != null) {
                intent.putExtra(EXTRA_EXPECTED_CAPABILITY, expectedCapability)
            }
            try {
                context.startForegroundService(intent)
            } catch (error: RuntimeException) {
                Diagnostics.exception(context, "service_api", "start_foreground_service", error)
                throw error
            }
        }

        fun stop(context: Context) {
            Diagnostics.record(context, "service_api", "stop_requested")
            Diagnostics.updateRuntimeState(lastStopReason = DiagnosticToken("api_stop"))
            context.getSharedPreferences(LIFECYCLE_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_AWAY_DEADLINE_EPOCH_MS)
                .apply()
            context.stopService(Intent(context, RemoteControlService::class.java))
        }

        fun notificationsEnabled(context: Context): Boolean =
            RemoteNotification.notificationsEnabled(context)
    }
}

/** Process-local lease. A delegated notification PendingIntent cannot survive Stop/process death. */
internal object RemoteControlLeaseRegistry {
    @Volatile private var activeToken: String? = null

    @Synchronized
    fun activate(token: String) {
        activeToken = token
    }

    @Synchronized
    fun revoke(token: String) {
        if (activeToken == token) activeToken = null
    }

    fun isActive(token: String?): Boolean = token != null && activeToken == token
}

private data class QueuedAction(
    val action: QuickRemoteAction,
    val issuedAtElapsedMs: Long,
    val wasLockedAtIngress: Boolean,
    val capabilityToken: String,
)

private fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Connected -> "Connected"
    ConnectionState.Disconnected -> "Ready to connect"
}

private fun NowPlayingSnapshot.subtitle(): String? {
    val episode = buildList {
        seasonNumber?.let { add("S$it") }
        episodeNumber?.let { add("E$it") }
    }.joinToString(" ").ifBlank { null }
    val show = listOfNotNull(seriesName, episode).joinToString(" · ").ifBlank { null }
    return show
        ?: listOfNotNull(artist, album).distinct().joinToString(" · ").ifBlank { null }
        ?: appName
}

internal fun shouldUseMediaPresentation(
    homeAuthorized: Boolean,
    connectionState: ConnectionState,
    retainedDuringNetworkTransition: Boolean = false,
): Boolean =
    homeAuthorized &&
        (connectionState == ConnectionState.Connected || retainedDuringNetworkTransition)

internal fun shouldReleaseRetainedMedia(
    departureSuspected: Boolean,
    requiresReconnect: Boolean,
    sawConnectionBreak: Boolean,
    retainedObservedAtElapsedMs: Long,
    currentConnectionState: ConnectionState,
    currentAuthoritative: Boolean,
    currentObservedAtElapsedMs: Long,
): Boolean =
    !departureSuspected &&
        currentConnectionState == ConnectionState.Connected &&
        currentAuthoritative &&
        currentObservedAtElapsedMs > retainedObservedAtElapsedMs &&
        (!requiresReconnect || sawConnectionBreak)

/** Requires a connection edge from the current handover, not an older one. */
internal class RetainedMediaReconnectGate {
    var sawConnectionBreak: Boolean = false
        private set

    fun observe(state: ConnectionState) {
        if (state != ConnectionState.Connected) sawConnectionBreak = true
    }

    fun rearm() {
        sawConnectionBreak = false
    }
}

internal enum class ContinuityRecoveryRequest {
    Start,
    RetryPending,
    Ignore,
}

/** Coalesces callback storms into at most one retry for each departure grace window. */
internal class BoundedContinuityRecoveryAttempts(
    private val maxAttempts: Int = 2,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    private var attempts = 0
    private var retryPending = false

    fun request(recoveryActive: Boolean): ContinuityRecoveryRequest {
        if (attempts >= maxAttempts) return ContinuityRecoveryRequest.Ignore
        if (recoveryActive) {
            retryPending = true
            return ContinuityRecoveryRequest.RetryPending
        }
        attempts += 1
        return ContinuityRecoveryRequest.Start
    }

    /** Returns true when the one coalesced retry should start immediately. */
    fun onFinished(success: Boolean): Boolean {
        if (success) {
            retryPending = false
            attempts = maxAttempts
            return false
        }
        if (!retryPending || attempts >= maxAttempts) {
            retryPending = false
            return false
        }
        retryPending = false
        attempts += 1
        return true
    }

    fun reset() {
        attempts = 0
        retryPending = false
    }
}

private fun NowPlayingSnapshot.needsBoundedRefresh(): Boolean =
    !isAuthoritative ||
        status == PlaybackStatus.Unknown ||
        (
            title == null &&
                artist == null &&
                seriesName == null &&
                contentId == null &&
                artwork == null &&
                durationMs == null
            )

private fun NetworkCapabilities.toLanCapsKey(): LanCapsKey = LanCapsKey(
    notVpn = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
    wifi = hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
    ethernet = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
)

private fun LinkProperties.toLanLinkKey(): LanLinkKey = LanLinkKey(
    prefixes = linkAddresses.mapNotNullTo(mutableSetOf()) { link ->
        val address = link.address
        if (!address.isUsableLanAddress()) return@mapNotNullTo null
        LocalNetworkIdentity.addressToken(
            LocalNetworkIdentity.maskPrefix(address.address, link.prefixLength),
            link.prefixLength,
        )
    },
    defaultGateways = routes.mapNotNullTo(mutableSetOf()) { route ->
        if (!route.isDefaultRoute) return@mapNotNullTo null
        route.gateway
            ?.takeIf { it.isUsableLanAddress() }
            ?.let { LocalNetworkIdentity.addressToken(it.address, null) }
    },
    dnsServers = dnsServers.mapNotNullTo(mutableSetOf()) { address ->
        address
            .takeIf { it.isUsableLanAddress() }
            ?.let { LocalNetworkIdentity.addressToken(it.address, null) }
    },
)

private fun InetAddress.isUsableLanAddress(): Boolean =
    (this is Inet4Address || this is Inet6Address) &&
        !isAnyLocalAddress &&
        !isLoopbackAddress &&
        !isMulticastAddress &&
        !isLinkLocalAddress

private data class NotificationSnapshot(
    val state: ConnectionState,
    val deviceName: String?,
    val error: String?,
    val lockScreenControlsEnabled: Boolean,
    val nowPlaying: NowPlayingSnapshot,
)

private object RemoteNotification {
    // Channel visibility is immutable, so this intentionally differs from the
    // old SECRET custom-notification channel.
    private const val CHANNEL_ID = "apple_tv_native_media_controls"
    private const val SERVICE_CHANNEL_ID = "apple_tv_home_network_watcher"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Apple TV controls",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Native media and remote controls for your paired Apple TV"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Apple TV background connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Briefly watches for the saved home network after it disconnects"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
        Diagnostics.record(context, "notification", "channel_ensured")
    }

    fun notificationsEnabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun buildMedia(
        context: Context,
        snapshot: NotificationSnapshot,
        token: MediaSession.Token,
        artwork: Bitmap?,
        capabilityToken: String,
    ): Notification {
        val nowPlaying = snapshot.nowPlaying
        val title = nowPlaying.title ?: "Apple TV Remote"
        val status = nowPlaying.subtitle() ?: when (snapshot.state) {
            ConnectionState.Connecting -> "Connecting…"
            ConnectionState.Connected -> "Connected · tap to open remote"
            ConnectionState.Disconnected -> "Tap to connect"
        }
        val playbackAction = when (commandFor(nowPlaying)) {
            PlaybackCommand.Play -> Triple("Play", QuickRemoteAction.Play, 111)
            PlaybackCommand.Pause -> Triple("Pause", QuickRemoteAction.Pause, 111)
        }
        val controlsUnlocked = snapshot.lockScreenControlsEnabled
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_remote)
            .setContentTitle(title)
            .setContentText(status)
            .setSubText(nowPlaying.appName ?: "CyberRemote")
            .setContentIntent(remoteIntent(context, 901, capabilityToken))
            .setDeleteIntent(
                serviceIntent(
                    context, RemoteControlService.ACTION_STOP, 902, capabilityToken,
                ),
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .addAction(
                commandAction(
                    context, R.drawable.ic_skip_back_15, "Back 15 seconds",
                    QuickRemoteAction.SkipBack15, 110, !controlsUnlocked, capabilityToken,
                ),
            )
            .addAction(
                commandAction(
                    context, R.drawable.ic_play_pause,
                    playbackAction.first,
                    playbackAction.second,
                    playbackAction.third,
                    !controlsUnlocked,
                    capabilityToken,
                ),
            )
            .addAction(
                commandAction(
                    context, R.drawable.ic_skip_forward_15, "Forward 15 seconds",
                    QuickRemoteAction.SkipForward15, 112, !controlsUnlocked, capabilityToken,
                ),
            )
            .addAction(
                activityAction(
                    context, R.drawable.ic_qs_remote, "Remote", 113,
                    !controlsUnlocked, capabilityToken,
                ),
            )
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_remote_close,
                    "Stop controls",
                    serviceIntent(
                        context, RemoteControlService.ACTION_STOP, 114, capabilityToken,
                    ),
                ).build(),
            )
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(
                if (controlsUnlocked) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE,
            )
            .setPublicVersion(publicVersion(context, capabilityToken))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        artwork?.let { builder.setLargeIcon(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** Foreground-service disclosure only: no MediaStyle, token or remote buttons. */
    fun buildService(
        context: Context,
        state: ConnectionState,
        waitingForHomeNetwork: Boolean,
        capabilityToken: String,
    ): Notification {
        val status = when {
            waitingForHomeNetwork -> "Waiting for home network"
            state == ConnectionState.Connecting -> "Connecting to Apple TV"
            state == ConnectionState.Connected -> "Apple TV connected"
            else -> "Ready to connect"
        }
        val builder = Notification.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_remote)
            .setContentTitle("CyberRemote")
            .setContentText(status)
            .setContentIntent(remoteIntent(context, 991, capabilityToken))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    fun remoteActivityIntent(
        context: Context,
        requestCode: Int,
        capabilityToken: String,
    ): PendingIntent = remoteIntent(context, requestCode, capabilityToken)

    private fun commandAction(
        context: Context,
        icon: Int,
        title: String,
        action: QuickRemoteAction,
        requestCode: Int,
        requireAuthentication: Boolean,
        capabilityToken: String,
    ): Notification.Action = actionBuilder(
        icon,
        title,
        commandIntent(context, action, requestCode, capabilityToken),
        requireAuthentication,
    )

    private fun activityAction(
        context: Context,
        icon: Int,
        title: String,
        requestCode: Int,
        requireAuthentication: Boolean,
        capabilityToken: String,
    ): Notification.Action = actionBuilder(
        icon,
        title,
        remoteIntent(context, requestCode, capabilityToken),
        requireAuthentication,
    )

    private fun actionBuilder(
        icon: Int,
        title: String,
        pendingIntent: PendingIntent,
        requireAuthentication: Boolean,
    ): Notification.Action {
        val builder = Notification.Action.Builder(icon, title, pendingIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAuthenticationRequired(requireAuthentication)
        }
        return builder.build()
    }

    private fun commandIntent(
        context: Context,
        action: QuickRemoteAction,
        requestCode: Int,
        capabilityToken: String,
    ): PendingIntent = PendingIntent.getForegroundService(
        context,
        requestCode,
        Intent(context, RemoteControlService::class.java)
            .setAction(RemoteControlService.ACTION_COMMAND)
            .setData(
                Uri.Builder()
                    .scheme("cyberremote")
                    .authority("action")
                    .appendPath(capabilityToken)
                    .appendPath(action.wireValue)
                    .build(),
            )
            .putExtra(RemoteControlService.EXTRA_COMMAND, action.wireValue)
            .putExtra(RemoteControlService.EXTRA_CAPABILITY, capabilityToken),
        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceIntent(
        context: Context,
        action: String,
        requestCode: Int,
        capabilityToken: String,
    ): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, RemoteControlService::class.java)
                .setAction(action)
                .setData(
                    Uri.Builder()
                        .scheme("cyberremote")
                        .authority("service")
                        .appendPath(capabilityToken)
                        .appendPath(action)
                        .build(),
                )
                .putExtra(RemoteControlService.EXTRA_CAPABILITY, capabilityToken),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun remoteIntent(
        context: Context,
        requestCode: Int,
        capabilityToken: String,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, FullRemoteActivity::class.java)
                .setData(
                    Uri.Builder()
                        .scheme("cyberremote")
                        .authority("remote")
                        .appendPath(capabilityToken)
                        .build(),
                )
                .putExtra(FullRemoteActivity.EXTRA_REQUIRE_LAUNCH_CAPABILITY, true)
                .putExtra(FullRemoteActivity.EXTRA_LAUNCH_CAPABILITY, capabilityToken)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun publicVersion(context: Context, capabilityToken: String): Notification =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_remote)
            .setContentTitle("TV controls")
            .setContentText("Unlock to use the remote")
            .setContentIntent(remoteIntent(context, 990, capabilityToken))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .build()
}
