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
import android.os.IBinder
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import dev.companionremote.app.ConnectionState
import dev.companionremote.app.R
import dev.companionremote.app.RemoteSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Holds the encrypted Apple TV connection while shade controls are enabled. */
class RemoteControlService : Service() {

    // Service callbacks and notification state are confined to the main thread;
    // RemoteSessionManager performs all socket work on its IO actor.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandQueue = Channel<QuickRemoteAction>(capacity = 64)
    private lateinit var session: RemoteSessionManager
    private lateinit var keyguard: KeyguardManager
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        session = RemoteSessionManager.get(this)
        keyguard = getSystemService(KeyguardManager::class.java)
        session.setQuickRemoteOwner(true)
        RemoteNotification.createChannel(this)

        scope.launch {
            for (action in commandQueue) {
                // Custom notification controls must never work from lockscreen.
                if (!keyguard.isKeyguardLocked) {
                    action.execute(session, requireUnlocked = true)
                }
            }
        }
        scope.launch {
            combine(
                session.connectionState,
                session.activeDevice,
                session.connectionError,
            ) { state, device, error -> Triple(state, device?.name, error) }
                .collect { (state, deviceName, error) ->
                    if (foregroundStarted) {
                        getSystemService(NotificationManager::class.java).notify(
                            NOTIFICATION_ID,
                            RemoteNotification.build(this@RemoteControlService, state, deviceName, error),
                        )
                    }
                    TileService.requestListeningState(
                        this@RemoteControlService,
                        ComponentName(this@RemoteControlService, RemoteTileService::class.java),
                    )
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRemote()
            return START_NOT_STICKY
        }

        if (!foregroundStarted) {
            startForeground(
                NOTIFICATION_ID,
                RemoteNotification.build(
                    this,
                    session.connectionState.value,
                    session.activeDevice.value?.name,
                    session.connectionError.value,
                ),
            )
            foregroundStarted = true
        }

        when (intent?.action) {
            ACTION_START, null -> scope.launch { session.connectLastPaired() }
            ACTION_COMMAND -> QuickRemoteAction.fromWireValue(intent.getStringExtra(EXTRA_COMMAND))
                ?.let(commandQueue::trySend)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        commandQueue.close()
        session.setQuickRemoteOwner(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun stopRemote() {
        foregroundStarted = false
        session.setQuickRemoteOwner(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        internal const val ACTION_START = "dev.companionremote.quick.START"
        internal const val ACTION_COMMAND = "dev.companionremote.quick.COMMAND"
        internal const val ACTION_STOP = "dev.companionremote.quick.STOP"
        internal const val EXTRA_COMMAND = "remote_command"
        internal const val NOTIFICATION_ID = 4207

        fun start(context: Context) {
            val intent = Intent(context, RemoteControlService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun send(context: Context, action: QuickRemoteAction) {
            val intent = Intent(context, RemoteControlService::class.java)
                .setAction(ACTION_COMMAND)
                .putExtra(EXTRA_COMMAND, action.wireValue)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteControlService::class.java))
        }

        fun notificationsEnabled(context: Context): Boolean =
            RemoteNotification.notificationsEnabled(context)
    }
}

private object RemoteNotification {
    private const val CHANNEL_ID = "apple_tv_remote_controls"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Apple TV remote",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent controls for your paired Apple TV"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
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
        state: ConnectionState,
        deviceName: String?,
        error: String?,
    ): Notification {
        val status = when (state) {
            ConnectionState.Connecting -> "Connecting…"
            ConnectionState.Connected -> "Connected"
            ConnectionState.Disconnected -> error ?: "Tap a control to reconnect"
        }
        val title = deviceName ?: "Apple TV remote"
        val expanded = RemoteViews(context.packageName, R.layout.notification_remote_expanded).apply {
            setTextViewText(R.id.remote_notification_title, title)
            setTextViewText(R.id.remote_notification_status, status)
            bind(context, R.id.remote_up, QuickRemoteAction.Up)
            bind(context, R.id.remote_left, QuickRemoteAction.Left)
            bind(context, R.id.remote_ok, QuickRemoteAction.Select)
            bind(context, R.id.remote_right, QuickRemoteAction.Right)
            bind(context, R.id.remote_back, QuickRemoteAction.Back)
            bind(context, R.id.remote_down, QuickRemoteAction.Down)
            bind(context, R.id.remote_play, QuickRemoteAction.PlayPause)
            bind(context, R.id.remote_volume_down, QuickRemoteAction.VolumeDown)
            bind(context, R.id.remote_home, QuickRemoteAction.Home)
            bind(context, R.id.remote_volume_up, QuickRemoteAction.VolumeUp)
            setOnClickPendingIntent(R.id.remote_close, serviceIntent(context, RemoteControlService.ACTION_STOP, 900))
        }

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_remote)
            .setContentTitle(title)
            .setContentText(status)
            .setContentIntent(panelIntent(context, 901))
            .setDeleteIntent(serviceIntent(context, RemoteControlService.ACTION_STOP, 902))
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomBigContentView(expanded)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun RemoteViews.bind(context: Context, viewId: Int, action: QuickRemoteAction) {
        setOnClickPendingIntent(viewId, commandIntent(context, action, action.ordinal + 100))
    }

    private fun commandIntent(
        context: Context,
        action: QuickRemoteAction,
        requestCode: Int,
    ): PendingIntent = PendingIntent.getForegroundService(
        context,
        requestCode,
        Intent(context, RemoteControlService::class.java)
            .setAction(RemoteControlService.ACTION_COMMAND)
            .putExtra(RemoteControlService.EXTRA_COMMAND, action.wireValue),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, RemoteControlService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun panelIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, RemotePanelActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
