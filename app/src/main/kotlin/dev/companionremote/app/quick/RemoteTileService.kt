package dev.companionremote.app.quick

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
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
        if (!allowWhileLocked) {
            unlockAndRun { runCatching { startActivity(intent) } }
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        701,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: RuntimeException) {
            // Retry the direct launch first so an OEM quirk does not silently
            // defeat the user's explicit lock-screen-controls preference.
            if (runCatching { startActivity(intent) }.isFailure) {
                unlockAndRun { runCatching { startActivity(intent) } }
            }
        }
    }

    override fun onDestroy() {
        listeningJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
