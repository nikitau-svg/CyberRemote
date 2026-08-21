package dev.companionremote.app.quick

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.companionremote.app.ConnectionState
import dev.companionremote.app.RemoteSessionManager
import dev.companionremote.app.data.CredentialsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RemoteTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = scope.launch {
            val paired = CredentialsRepository(this@RemoteTileService).pairedDeviceNames().isNotEmpty()
            val connected = RemoteSessionManager.get(this@RemoteTileService)
                .connectionState.value == ConnectionState.Connected
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
        val launch = Runnable {
            val intent = Intent(this, RemotePanelActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            unlockAndRun(launch)
        } else {
            launch.run()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
