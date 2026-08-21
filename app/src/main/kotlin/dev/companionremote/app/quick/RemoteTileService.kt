package dev.companionremote.app.quick

import android.Manifest
import android.app.Dialog
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import dev.companionremote.app.ConnectionState
import dev.companionremote.app.MainActivity
import dev.companionremote.app.R
import dev.companionremote.app.RemoteSessionManager
import dev.companionremote.app.data.CredentialsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.min

class RemoteTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null
    private var dialogStateJob: Job? = null
    private var remoteDialog: Dialog? = null
    private lateinit var session: RemoteSessionManager
    private lateinit var keyguard: KeyguardManager

    override fun onCreate() {
        super.onCreate()
        session = RemoteSessionManager.get(this)
        keyguard = getSystemService(KeyguardManager::class.java)
    }

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = scope.launch {
            val paired = CredentialsRepository(this@RemoteTileService).pairedDeviceNames().isNotEmpty()
            val connected = session.connectionState.value == ConnectionState.Connected
            qsTile?.apply {
                label = "Apple TV"
                state = when {
                    !paired -> Tile.STATE_UNAVAILABLE
                    connected -> Tile.STATE_ACTIVE
                    else -> Tile.STATE_INACTIVE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    subtitle = when {
                        !paired -> "Pair in app"
                        connected -> "Connected"
                        else -> "Remote"
                    }
                }
                updateTile()
            }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val showRemote = Runnable {
            // Re-check after unlockAndRun: the user can cancel or the device can
            // return to keyguard before the callback executes.
            if (!keyguard.isKeyguardLocked) {
                if (needsNotificationPermissionOnboarding()) {
                    markNotificationPermissionOnboardingShown()
                    launchPanelFallback()
                } else {
                    showRemoteDialog()
                }
            }
        }
        if (keyguard.isKeyguardLocked) {
            unlockAndRun(showRemote)
        } else {
            showRemote.run()
        }
    }

    private fun showRemoteDialog() {
        if (remoteDialog?.isShowing == true) return

        val content = LayoutInflater.from(this).inflate(R.layout.quick_remote_dialog, null, false)
        val dialog = Dialog(this, R.style.Theme_CyberRemote_QuickDialog).apply {
            setContentView(content)
            setCanceledOnTouchOutside(true)
            window?.apply {
                setGravity(Gravity.BOTTOM)
                setDimAmount(0.42f)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }

        bindActions(content)
        content.findViewById<View>(R.id.dialog_open_full).setOnClickListener {
            openFullRemote(dialog)
        }
        content.findViewById<View>(R.id.dialog_remote_close).setOnClickListener {
            RemoteControlService.stop(this)
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            session.setPanelOwner(true)
            val horizontalMargin = dp(20)
            val width = min(resources.displayMetrics.widthPixels - horizontalMargin, dp(480))
            dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            observeDialogState(content)

            // Preserve the existing shade card when notification permission is
            // available. The transient dialog itself works without it.
            if (canPostNotifications()) {
                try {
                    RemoteControlService.start(this)
                } catch (_: RuntimeException) {
                    // The transient remote remains usable if an OEM rejects a
                    // foreground-service start outside its allowed window.
                }
            } else {
                RemoteControlService.stop(this)
            }
        }
        dialog.setOnDismissListener {
            dialogStateJob?.cancel()
            dialogStateJob = null
            session.setPanelOwner(false)
            if (remoteDialog === dialog) remoteDialog = null
        }

        remoteDialog = dialog
        try {
            // This is the platform-supported QS surface: SystemUI supplies the
            // window token, collapses the shade, and creates no Activity task.
            showDialog(dialog)
        } catch (_: RuntimeException) {
            runCatching { dialog.dismiss() }
            dialogStateJob?.cancel()
            dialogStateJob = null
            session.setPanelOwner(false)
            remoteDialog = null
            launchPanelFallback()
        }
    }

    private fun bindActions(content: View) {
        val actions = mapOf(
            R.id.dialog_remote_up to QuickRemoteAction.Up,
            R.id.dialog_remote_down to QuickRemoteAction.Down,
            R.id.dialog_remote_left to QuickRemoteAction.Left,
            R.id.dialog_remote_right to QuickRemoteAction.Right,
            R.id.dialog_remote_ok to QuickRemoteAction.Select,
            R.id.dialog_remote_back to QuickRemoteAction.Back,
            R.id.dialog_remote_play to QuickRemoteAction.PlayPause,
            R.id.dialog_remote_volume_down to QuickRemoteAction.VolumeDown,
            R.id.dialog_remote_home to QuickRemoteAction.Home,
            R.id.dialog_remote_volume_up to QuickRemoteAction.VolumeUp,
        )
        actions.forEach { (viewId, action) ->
            content.findViewById<View>(viewId).setOnClickListener { sendAction(action) }
        }
        content.findViewById<View>(R.id.dialog_remote_home).setOnLongClickListener {
            sendAction(QuickRemoteAction.HomeHold)
            true
        }
    }

    private fun sendAction(action: QuickRemoteAction) {
        if (keyguard.isKeyguardLocked) return
        scope.launch { action.execute(session, requireUnlocked = true) }
    }

    private fun observeDialogState(content: View) {
        dialogStateJob?.cancel()
        dialogStateJob = scope.launch {
            combine(
                session.connectionState,
                session.activeDevice,
                session.connectionError,
            ) { state, device, error -> Triple(state, device?.name, error) }
                .collect { (state, deviceName, error) ->
                    content.findViewById<TextView>(R.id.dialog_remote_title).text =
                        deviceName ?: "Apple TV"
                    content.findViewById<TextView>(R.id.dialog_remote_status).text = when (state) {
                        ConnectionState.Connecting -> "Connecting…"
                        ConnectionState.Connected -> "Connected"
                        ConnectionState.Disconnected -> error ?: "Ready to connect"
                    }
                }
        }
    }

    private fun openFullRemote(dialog: Dialog) {
        if (keyguard.isKeyguardLocked) return
        val token = session.issueOpenFullRemoteToken()
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_LAST_REMOTE_TOKEN, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    702,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            dialog.dismiss()
        } catch (_: RuntimeException) {
            // Keep the mini remote visible if the full Activity launch is
            // rejected by an OEM background-activity policy.
        }
    }

    private fun launchPanelFallback() {
        val intent = Intent(this, RemotePanelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                701,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun canPostNotifications(): Boolean =
        hasNotificationPermission() && RemoteControlService.notificationsEnabled(this)

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun needsNotificationPermissionOnboarding(): Boolean =
        !hasNotificationPermission() &&
            !getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_NOTIFICATION_ONBOARDING_SHOWN, false)

    private fun markNotificationPermissionOnboardingShown() {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATION_ONBOARDING_SHOWN, true)
            .apply()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        remoteDialog?.dismiss()
        remoteDialog = null
        dialogStateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val PREFERENCES_NAME = "quick_remote_preferences"
        const val KEY_NOTIFICATION_ONBOARDING_SHOWN = "notification_onboarding_shown"
    }
}
