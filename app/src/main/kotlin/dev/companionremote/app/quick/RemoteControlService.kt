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
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Holds the encrypted Apple TV connection and exposes Android-native media controls. */
class RemoteControlService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandQueue = Channel<QueuedAction>(capacity = COMMAND_QUEUE_CAPACITY)
    private val ingressRateLimiter = LockedRemoteRateLimiter { SystemClock.elapsedRealtime() }
    private var connectJob: Job? = null
    private var artworkJob: Job? = null
    private lateinit var session: RemoteSessionManager
    private lateinit var settings: SettingsRepository
    private lateinit var keyguard: KeyguardManager
    private var mediaSession: MediaSession? = null
    private var foregroundStarted = false
    @Volatile private var acceptingCommands = false
    private var lockScreenControlsEnabled = false
    private var loadedArtworkId: String? = null
    private var artworkBitmap: Bitmap? = null
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
                updateArtwork(snapshot.nowPlaying.artwork)
                updateMediaSession(snapshot)
                refreshNotification()
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
            return START_NOT_STICKY
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

        activateControlLease()
        ensureForeground()
        when (intent?.action) {
            ACTION_START, null -> {
                // Opening controls while already locked must not silently create
                // a fresh network session. Existing connected sessions may stay.
                if (!keyguard.isKeyguardLocked) {
                    val leaseToken = capabilityToken
                    connectJob?.cancel()
                    connectJob = scope.launch {
                        session.connectLastPaired(
                            requireUnlocked = true,
                            authorizationStillValid = { isControlLeaseActive(leaseToken) },
                        )
                    }
                }
            }
        }
        return START_NOT_STICKY
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
        artworkJob?.cancel()
        artworkJob = null
        artworkBitmap = null
        loadedArtworkId = null
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
                acceptingCommands && queued.capabilityToken == capabilityToken
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
                updateMediaSession(lastSnapshot)
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
        val currentSession = mediaSession ?: run {
            Diagnostics.record(
                this,
                "notification",
                "foreground_skipped",
                "reason" to DiagnosticToken("no_media_session"),
            )
            return
        }
        Diagnostics.record(this, "notification", "foreground_start_attempt")
        try {
            startForeground(
                NOTIFICATION_ID,
                RemoteNotification.build(
                    this,
                    lastSnapshot,
                    currentSession.sessionToken,
                    artworkBitmap,
                    capabilityToken,
                ),
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
        val currentSession = mediaSession ?: return
        try {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                RemoteNotification.build(
                    this,
                    lastSnapshot,
                    currentSession.sessionToken,
                    artworkBitmap,
                    capabilityToken,
                ),
            )
            Diagnostics.record(this, "notification", "refreshed", "id" to NOTIFICATION_ID)
        } catch (error: RuntimeException) {
            Diagnostics.exception(this, "notification", "refresh", error)
            throw error
        }
    }

    private fun stopRemote(reason: String) {
        Diagnostics.updateRuntimeState(lastStopReason = DiagnosticToken(reason))
        Diagnostics.record(this, "remote_service", "stopping", "reason" to DiagnosticToken(reason))
        acceptingCommands = false
        connectJob?.cancel()
        connectJob = null
        artworkJob?.cancel()
        artworkJob = null
        artworkBitmap = null
        loadedArtworkId = null
        releaseMediaSession()
        revokeCapabilities()
        foregroundStarted = false
        Diagnostics.updateRuntimeState(foregroundStarted = false)
        session.setQuickRemoteOwner(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun activateControlLease() {
        if (acceptingCommands) {
            Diagnostics.record(this, "remote_service", "lease_already_active")
            return
        }
        acceptingCommands = true
        RemoteControlLeaseRegistry.activate(capabilityToken)
        session.setQuickRemoteOwner(true)
        Diagnostics.record(this, "media_session", "creating")
        mediaSession = try {
            createMediaSession(capabilityToken)
        } catch (error: RuntimeException) {
            Diagnostics.exception(this, "media_session", "create", error)
            acceptingCommands = false
            revokeCapabilities()
            session.setQuickRemoteOwner(false)
            throw error
        }
        Diagnostics.record(this, "remote_service", "lease_activated")
        updateMediaSession(lastSnapshot)
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
        private const val MEDIA_VOLUME_DOWN = "dev.companionremote.media.VOLUME_DOWN"
        private const val MEDIA_VOLUME_UP = "dev.companionremote.media.VOLUME_UP"
        private const val ARTWORK_EDGE_PX = 512
        private const val ARTWORK_TIMEOUT_MS = 4_000
        private const val MAX_ARTWORK_BYTES = 3L * 1024 * 1024
        private const val DEFAULT_ARTWORK_BUFFER_BYTES = 32 * 1024

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

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
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
        Diagnostics.record(context, "notification", "channel_ensured")
    }

    fun notificationsEnabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun build(
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
            PlaybackCommand.Toggle -> Triple("Play/Pause", QuickRemoteAction.PlayPause, 111)
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
                    context, R.drawable.ic_volume_down, "Volume down",
                    QuickRemoteAction.VolumeDown, 110, !controlsUnlocked, capabilityToken,
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
                    context, R.drawable.ic_volume_up, "Volume up",
                    QuickRemoteAction.VolumeUp, 112, !controlsUnlocked, capabilityToken,
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
