package dev.companionremote.app.quick

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.companionremote.app.ConnectionState
import dev.companionremote.app.MainActivity
import dev.companionremote.app.R
import dev.companionremote.app.RemoteSessionManager
import dev.companionremote.app.data.AppSkin
import dev.companionremote.app.theme.skinColorScheme
import kotlinx.coroutines.launch

/** Activity fallback used by notification taps and OEMs that reject the QS dialog. */
class RemotePanelActivity : ComponentActivity() {

    private lateinit var session: RemoteSessionManager
    private var persistentControlsEnabled = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        persistentControlsEnabled = granted && RemoteControlService.notificationsEnabled(this)
        if (persistentControlsEnabled) {
            RemoteControlService.start(this)
        } else {
            RemoteControlService.stop(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = RemoteSessionManager.get(this)
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            finish()
            return
        }

        window.setGravity(Gravity.BOTTOM)
        window.setDimAmount(0.42f)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setFinishOnTouchOutside(true)

        setContent {
            MaterialTheme(colorScheme = skinColorScheme(AppSkin.Midnight, dark = true)) {
                RemotePanel(
                    session = session,
                    onAction = ::sendAction,
                    onOpenFull = {
                        val token = session.issueOpenFullRemoteToken()
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_OPEN_LAST_REMOTE_TOKEN, token)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        )
                        finish()
                    },
                    onClose = {
                        RemoteControlService.stop(this)
                        finish()
                    },
                )
            }
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            persistentControlsEnabled = RemoteControlService.notificationsEnabled(this)
            if (persistentControlsEnabled) {
                RemoteControlService.start(this)
            } else {
                RemoteControlService.stop(this)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        session.setPanelOwner(true)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }

    override fun onStop() {
        session.setPanelOwner(false)
        super.onStop()
    }

    private fun sendAction(action: QuickRemoteAction) {
        if (persistentControlsEnabled) {
            RemoteControlService.send(this, action)
        } else {
            lifecycleScope.launch { action.execute(session, requireUnlocked = true) }
        }
    }
}

@Composable
private fun RemotePanel(
    session: RemoteSessionManager,
    onAction: (QuickRemoteAction) -> Unit,
    onOpenFull: () -> Unit,
    onClose: () -> Unit,
) {
    val state by session.connectionState.collectAsState()
    val device by session.activeDevice.collectAsState()
    val error by session.connectionError.collectAsState()

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 8.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Tv, contentDescription = null, Modifier.size(24.dp))
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            device?.name ?: "Apple TV",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when (state) {
                                ConnectionState.Connecting -> "Connecting…"
                                ConnectionState.Connected -> "Connected"
                                ConnectionState.Disconnected -> error ?: "Ready to connect"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (state) {
                                ConnectionState.Connected -> Color(0xFF9ECE6A)
                                ConnectionState.Connecting -> Color(0xFFE0AF68)
                                ConnectionState.Disconnected -> MaterialTheme.colorScheme.error
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onOpenFull) {
                        Icon(Icons.Rounded.OpenInFull, contentDescription = "Open full remote")
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close shade remote")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Dpad(onAction)
                Spacer(Modifier.height(10.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PanelKey(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { onAction(QuickRemoteAction.Back) }
                    PanelKey(
                        Icons.Rounded.Home,
                        "Home",
                        onLongClick = { onAction(QuickRemoteAction.HomeHold) },
                    ) { onAction(QuickRemoteAction.Home) }
                    PanelKey(R.drawable.ic_play_pause, "Play/Pause") { onAction(QuickRemoteAction.PlayPause) }
                    PanelKey(Icons.Rounded.Remove, "Volume down") { onAction(QuickRemoteAction.VolumeDown) }
                    PanelKey(Icons.Rounded.Add, "Volume up") { onAction(QuickRemoteAction.VolumeUp) }
                }
            }
        }
    }
}

@Composable
private fun Dpad(onAction: (QuickRemoteAction) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PanelKey(Icons.Rounded.KeyboardArrowUp, "Up", large = true) { onAction(QuickRemoteAction.Up) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelKey(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Left", large = true) {
                onAction(QuickRemoteAction.Left)
            }
            Surface(
                modifier = Modifier.size(70.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                onClick = { onAction(QuickRemoteAction.Select) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            }
            PanelKey(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Right", large = true) {
                onAction(QuickRemoteAction.Right)
            }
        }
        PanelKey(Icons.Rounded.KeyboardArrowDown, "Down", large = true) { onAction(QuickRemoteAction.Down) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelKey(
    icon: ImageVector,
    label: String,
    large: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(if (large) 68.dp else 50.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, Modifier.size(if (large) 34.dp else 25.dp))
        }
    }
}

@Composable
private fun PanelKey(drawable: Int, label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(50.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(drawable), contentDescription = label, Modifier.size(25.dp))
        }
    }
}
