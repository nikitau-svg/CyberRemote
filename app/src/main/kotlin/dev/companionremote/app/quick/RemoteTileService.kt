package dev.companionremote.app.quick

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import dev.companionremote.app.ConnectionState
import dev.companionremote.app.MainActivity
import dev.companionremote.app.RemoteSessionManager
import dev.companionremote.app.data.CredentialsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** A native Quick Settings entry point for the dedicated remote surface. */
class RemoteTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null
    private lateinit var session: RemoteSessionManager

    override fun onCreate() {
        super.onCreate()
        session = RemoteSessionManager.get(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = scope.launch {
            val paired = CredentialsRepository(this@RemoteTileService)
                .pairedDeviceNames()
                .isNotEmpty()
            val connected = session.connectionState.value == ConnectionState.Connected
            qsTile?.apply {
                label = "Apple TV"
                state = when {
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
        scope.launch {
            val paired = CredentialsRepository(this@RemoteTileService)
                .pairedDeviceNames()
                .isNotEmpty()
            if (paired) {
                launchActivity(
                    Intent(this@RemoteTileService, FullRemoteActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    allowWhileLocked = true,
                )
            } else {
                launchActivity(
                    Intent(this@RemoteTileService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    allowWhileLocked = false,
                )
            }
        }
    }

    private fun launchActivity(intent: Intent, allowWhileLocked: Boolean) {
        val creatorOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
                .toBundle()
        } else {
            null
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            if (allowWhileLocked) 701 else 702,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions,
        )
        if (!allowWhileLocked) {
            runAfterUnlock(pendingIntent)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: RuntimeException) {
            // Some OEM SystemUI builds reject startActivityAndCollapse even for
            // a user click. A PendingIntent keeps the fallback explicit and
            // avoids relying on a background startActivity call.
            if (!send(pendingIntent)) runAfterUnlock(pendingIntent)
        }
    }

    private fun runAfterUnlock(pendingIntent: PendingIntent) {
        try {
            unlockAndRun {
                if (!send(pendingIntent)) showLaunchError()
            }
        } catch (_: RuntimeException) {
            showLaunchError()
        }
    }

    private fun send(pendingIntent: PendingIntent): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val senderOptions = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
                .toBundle()
            pendingIntent.send(senderOptions)
        } else {
            pendingIntent.send()
        }
        true
    } catch (_: PendingIntent.CanceledException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun showLaunchError() {
        Toast.makeText(
            applicationContext,
            "Couldn't open the remote. Open CyberRemote from Apps.",
            Toast.LENGTH_SHORT,
        ).show()
    }

    override fun onDestroy() {
        listeningJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
