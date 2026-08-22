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
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.service.quicksettings.TileService
import dev.companionremote.app.ConnectionState
import dev.companionremote.app.R
import dev.companionremote.app.RemoteSessionManager
import dev.companionremote.app.data.SettingsRepository
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Holds the encrypted Apple TV connection and exposes Android-native media controls. */
class RemoteControlService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandQueue = Channel<QueuedAction>(capacity = COMMAND_QUEUE_CAPACITY)
    private val ingressRateLimiter = LockedRemoteRateLimiter { SystemClock.elapsedRealtime() }
    private var connectJob: kotlinx.coroutines.Job? = null
    private lateinit var session: RemoteSessionManager
    private lateinit var settings: SettingsRepository
    private lateinit var keyguard: KeyguardManager
    private var mediaSession: MediaSession? = null
    private var foregroundStarted = false
    @Volatile private var acceptingCommands = false
    private var lockScreenControlsEnabled = false
    private var optimisticPlaying = false
    @Volatile private var capabilityToken = UUID.randomUUID().toString()
    private var lastSnapshot = NotificationSnapshot(
        state = ConnectionState.Disconnected,
        deviceName = null,
        error = null,
        lockScreenControlsEnabled = false,
    )

    override fun onCreate() {
        super.onCreate()
        session = RemoteSessionManager.get(this)
        settings = SettingsRepository(this)
        keyguard = getSystemService(KeyguardManager::class.java)
        RemoteNotification.createChannel(this)

        scope.launch {
            for (queued in commandQueue) executeAction(queued)
        }
        scope.launch {
            combine(
                session.connectionState,
                session.activeDevice,
                session.connectionError,
                settings.lockScreenControls,
            ) { state, device, error, lockControls ->
                NotificationSnapshot(state, device?.name, error, lockControls)
            }.collect { snapshot ->
                lastSnapshot = snapshot
                lockScreenControlsEnabled = snapshot.lockScreenControlsEnabled
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
        if (intent?.action == ACTION_STOP) {
            val requestedCapability = intent.getStringExtra(EXTRA_CAPABILITY)
            if (!isControlLeaseActive(requestedCapability.orEmpty())) {
                if (!foregroundStarted) stopSelf()
                return START_NOT_STICKY
            }
            stopRemote()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_COMMAND) {
            if (!acceptingCommands || intent.getStringExtra(EXTRA_CAPABILITY) != capabilityToken) {
                if (!foregroundStarted) stopRemote()
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
            if (!foregroundStarted) stopRemote()
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
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        acceptingCommands = false
        revokeCapabilities()
        commandQueue.close()
        connectJob?.cancel()
        connectJob = null
        releaseMediaSession()
        session.setQuickRemoteOwner(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun createMediaSession(leaseToken: String): MediaSession =
        MediaSession(this, "CyberRemote").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (isControlLeaseActive(leaseToken)) enqueuePlayPause()
                }

                override fun onPause() {
                    if (isControlLeaseActive(leaseToken)) enqueuePlayPause()
                }

                override fun onStop() {
                    if (isControlLeaseActive(leaseToken)) stopRemote()
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
        }

    private fun isControlLeaseActive(leaseToken: String): Boolean =
        acceptingCommands &&
            leaseToken == capabilityToken &&
            RemoteControlLeaseRegistry.isActive(leaseToken)

    private fun releaseMediaSession() {
        mediaSession?.let { current ->
            current.setCallback(null)
            current.isActive = false
            current.release()
        }
        mediaSession = null
    }

    private fun enqueuePlayPause() {
        enqueueAction(QuickRemoteAction.PlayPause)
    }

    private fun enqueueAction(action: QuickRemoteAction) {
        if (!acceptingCommands) return
        val locked = keyguard.isKeyguardLocked
        if (locked) {
            if (!LockScreenRemotePolicy.canExecute(action, true, lockScreenControlsEnabled)) return
            if (!ingressRateLimiter.tryAcquire(action)) return
        }
        commandQueue.trySend(
            QueuedAction(
                action = action,
                issuedAtElapsedMs = SystemClock.elapsedRealtime(),
                wasLockedAtIngress = locked,
                capabilityToken = capabilityToken,
            ),
        )
    }

    private suspend fun executeAction(queued: QueuedAction) {
        if (queued.capabilityToken != capabilityToken) return
        val maxAge = if (queued.wasLockedAtIngress) {
            LOCKED_COMMAND_MAX_AGE_MS
        } else {
            UNLOCKED_COMMAND_MAX_AGE_MS
        }
        val remainingAge = maxAge - (SystemClock.elapsedRealtime() - queued.issuedAtElapsedMs)
        if (remainingAge <= 0L) return

        val action = queued.action
        if (queued.wasLockedAtIngress) {
            if (!LockScreenRemotePolicy.canExecute(action, true, lockScreenControlsEnabled)) return
        } else if (keyguard.isKeyguardLocked) {
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
        if (succeeded && action == QuickRemoteAction.PlayPause) {
            // Companion exposes no Now Playing state. This toggles only the local
            // button hint and is never presented as authoritative TV metadata.
            optimisticPlaying = !optimisticPlaying
            updateMediaSession(lastSnapshot)
            refreshNotification()
        }
    }

    private fun updateMediaSession(snapshot: NotificationSnapshot) {
        val currentSession = mediaSession ?: return
        val status = when (snapshot.state) {
            ConnectionState.Connecting -> "Connecting…"
            ConnectionState.Connected -> "Connected"
            ConnectionState.Disconnected -> "Ready to connect"
        }
        currentSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Apple TV Remote")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Remote controls")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, "Apple TV Remote")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, status)
                .build(),
        )
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
                    if (optimisticPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build(),
        )
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        val currentSession = mediaSession ?: return
        startForeground(
            NOTIFICATION_ID,
            RemoteNotification.build(
                this,
                lastSnapshot,
                currentSession.sessionToken,
                optimisticPlaying,
                capabilityToken,
            ),
        )
        foregroundStarted = true
    }

    private fun refreshNotification() {
        if (!foregroundStarted) return
        val currentSession = mediaSession ?: return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            RemoteNotification.build(
                this,
                lastSnapshot,
                currentSession.sessionToken,
                optimisticPlaying,
                capabilityToken,
            ),
        )
    }

    private fun stopRemote() {
        acceptingCommands = false
        connectJob?.cancel()
        connectJob = null
        releaseMediaSession()
        revokeCapabilities()
        foregroundStarted = false
        session.setQuickRemoteOwner(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun activateControlLease() {
        if (acceptingCommands) return
        acceptingCommands = true
        RemoteControlLeaseRegistry.activate(capabilityToken)
        session.setQuickRemoteOwner(true)
        mediaSession = createMediaSession(capabilityToken)
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

        fun start(context: Context, expectedCapability: String? = null) {
            val intent = Intent(context, RemoteControlService::class.java).setAction(ACTION_START)
            if (expectedCapability != null) {
                intent.putExtra(EXTRA_EXPECTED_CAPABILITY, expectedCapability)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
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

private data class NotificationSnapshot(
    val state: ConnectionState,
    val deviceName: String?,
    val error: String?,
    val lockScreenControlsEnabled: Boolean,
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
        optimisticPlaying: Boolean,
        capabilityToken: String,
    ): Notification {
        val status = when (snapshot.state) {
            ConnectionState.Connecting -> "Connecting…"
            ConnectionState.Connected -> "Connected · tap to open remote"
            ConnectionState.Disconnected -> "Tap to connect"
        }
        val controlsUnlocked = snapshot.lockScreenControlsEnabled
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_remote)
            .setContentTitle("Apple TV Remote")
            .setContentText(status)
            .setSubText("CyberRemote")
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
                    if (optimisticPlaying) "Pause" else "Play",
                    QuickRemoteAction.PlayPause, 111, !controlsUnlocked, capabilityToken,
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
