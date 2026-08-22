package dev.companionremote.app.quick

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeGesturesPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import dev.companionremote.app.ConnectionState
import dev.companionremote.app.R
import dev.companionremote.app.RemoteSessionManager
import dev.companionremote.app.data.SettingsRepository
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Apple-inspired, opaque remote surface used from Quick Settings and keyguard. */
class FullRemoteActivity : ComponentActivity() {

    private lateinit var session: RemoteSessionManager
    private lateinit var settings: SettingsRepository
    private lateinit var keyguard: KeyguardManager
    private val lockScreenControls = MutableStateFlow<Boolean?>(null)
    private val keyguardLockedState = MutableStateFlow(false)
    private var authenticationInProgress = false
    private var unlockRequestGeneration = 0L
    private var launchCapabilityRequired = false
    private var launchCapability: String? = null
    private var notificationPermissionRequestInProgress = false
    private var keyguardMonitorJob: Job? = null
    @Volatile private var acceptingActivityCommands = false
    @Volatile private var activityEpoch = 0L

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionRequestInProgress = false
        if (granted && acceptingActivityCommands && !keyguard.isKeyguardLocked) {
            connectUnlocked()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchCapabilityRequired = intent.getBooleanExtra(EXTRA_REQUIRE_LAUNCH_CAPABILITY, false)
        launchCapability = intent.getStringExtra(EXTRA_LAUNCH_CAPABILITY)
        if (launchCapabilityRequired && !RemoteControlLeaseRegistry.isActive(launchCapability)) {
            finish()
            return
        }
        session = RemoteSessionManager.get(this)
        settings = SettingsRepository(this)
        keyguard = getSystemService(KeyguardManager::class.java)
        keyguardLockedState.value = keyguard.isKeyguardLocked

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK
        @Suppress("DEPRECATION")
        run { window.decorView.systemUiVisibility = 0 }

        lifecycleScope.launch {
            settings.lockScreenControls.collect { enabled ->
                lockScreenControls.value = enabled
                if (!enabled && keyguard.isKeyguardLocked) requestUnlock(null)
            }
        }

        setContent {
            MaterialTheme(colorScheme = FullRemoteColors) {
                val state by session.connectionState.collectAsState()
                val device by session.activeDevice.collectAsState()
                val error by session.connectionError.collectAsState()
                val lockControls by lockScreenControls.collectAsState()
                val keyguardLocked by keyguardLockedState.collectAsState()
                FullRemoteScreen(
                    state = state,
                    deviceName = if (keyguardLocked) "Apple TV" else device?.name ?: "Apple TV",
                    error = error,
                    keyguardLocked = keyguardLocked,
                    lockScreenControlsEnabled = lockControls == true,
                    onClose = ::finish,
                    onAction = ::dispatch,
                    onPower = {
                        dispatch(
                            if (state == ConnectionState.Connected) {
                                QuickRemoteAction.Sleep
                            } else {
                                QuickRemoteAction.Wake
                            },
                        )
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        keyguardLockedState.value = keyguard.isKeyguardLocked
        if (launchCapabilityRequired && !RemoteControlLeaseRegistry.isActive(launchCapability)) {
            finish()
            return
        }
        activityEpoch += 1L
        acceptingActivityCommands = true
        session.setLockScreenRemoteOwner(true)
        keyguardMonitorJob?.cancel()
        keyguardMonitorJob = lifecycleScope.launch {
            while (isActive) {
                keyguardLockedState.value = keyguard.isKeyguardLocked
                delay(KEYGUARD_POLL_MS)
            }
        }
        if (keyguard.isKeyguardLocked) {
            if (lockScreenControls.value == false) requestUnlock(null)
        } else {
            connectUnlocked()
        }
    }

    override fun onResume() {
        super.onResume()
        keyguardLockedState.value = keyguard.isKeyguardLocked
        if (launchCapabilityRequired && !RemoteControlLeaseRegistry.isActive(launchCapability)) {
            invalidateActivityLease()
            finish()
        }
    }

    override fun onStop() {
        keyguardMonitorJob?.cancel()
        keyguardMonitorJob = null
        invalidateActivityLease()
        session.setLockScreenRemoteOwner(false)
        super.onStop()
    }

    override fun onDestroy() {
        invalidateActivityLease()
        super.onDestroy()
    }

    private fun connectUnlocked() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!notificationPermissionRequestInProgress) {
                notificationPermissionRequestInProgress = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            return
        }
        val expectedCapability = launchCapability.takeIf { launchCapabilityRequired }
        runCatching { RemoteControlService.start(this, expectedCapability) }
    }

    private fun dispatch(action: QuickRemoteAction) {
        val lease = currentActivityLease()
        val locked = keyguard.isKeyguardLocked
        val enabled = lockScreenControls.value == true
        if (!LockScreenRemotePolicy.canExecute(action, locked, enabled)) {
            requestUnlock(action)
            return
        }
        if (locked && session.connectionState.value != ConnectionState.Connected) {
            requestUnlock(action)
            return
        }

        lifecycleScope.launch {
            action.execute(
                session = session,
                requireUnlocked = !locked,
                allowReconnect = !locked,
                maxAgeMs = if (locked) LOCKED_COMMAND_MAX_AGE_MS else UNLOCKED_COMMAND_MAX_AGE_MS,
                authorizationStillValid = { isActivityLeaseValid(lease) },
            )
        }
    }

    private fun requestUnlock(action: QuickRemoteAction?) {
        if (!keyguard.isKeyguardLocked) {
            action?.let(::dispatch)
            return
        }
        if (authenticationInProgress) return
        val lease = currentActivityLease()
        if (!isActivityLeaseValid(lease)) return
        authenticationInProgress = true
        val requestId = ++unlockRequestGeneration
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    if (requestId != unlockRequestGeneration) return
                    keyguardLockedState.value = keyguard.isKeyguardLocked
                    authenticationInProgress = false
                    unlockRequestGeneration += 1L
                    if (
                        isFinishing || isDestroyed || keyguard.isKeyguardLocked ||
                        !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
                        !isActivityLeaseValid(lease)
                    ) {
                        return
                    }
                    connectUnlocked()
                    lifecycleScope.launch {
                        action?.execute(
                            session = session,
                            requireUnlocked = true,
                            allowReconnect = true,
                            maxAgeMs = UNLOCKED_COMMAND_MAX_AGE_MS,
                            authorizationStillValid = { isActivityLeaseValid(lease) },
                        )
                    }
                }

                override fun onDismissCancelled() = finishUnlockAttempt(requestId)
                override fun onDismissError() = finishUnlockAttempt(requestId)
            },
        )
    }

    private fun finishUnlockAttempt(requestId: Long) {
        if (requestId != unlockRequestGeneration) return
        authenticationInProgress = false
        unlockRequestGeneration += 1L
        if (lockScreenControls.value != true) finish()
    }

    private fun currentActivityLease(): ActivityLease = ActivityLease(
        epoch = activityEpoch,
        capabilityRequired = launchCapabilityRequired,
        capability = launchCapability,
    )

    private fun isActivityLeaseValid(lease: ActivityLease): Boolean =
        acceptingActivityCommands &&
            activityEpoch == lease.epoch &&
            launchCapabilityRequired == lease.capabilityRequired &&
            launchCapability == lease.capability &&
            (!lease.capabilityRequired || RemoteControlLeaseRegistry.isActive(lease.capability))

    private fun invalidateActivityLease() {
        acceptingActivityCommands = false
        activityEpoch += 1L
        unlockRequestGeneration += 1L
        authenticationInProgress = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        invalidateActivityLease()
        val capabilityRequired = intent.getBooleanExtra(EXTRA_REQUIRE_LAUNCH_CAPABILITY, false)
        val capability = intent.getStringExtra(EXTRA_LAUNCH_CAPABILITY)
        if (capabilityRequired && !RemoteControlLeaseRegistry.isActive(capability)) {
            finish()
            return
        }
        setIntent(intent)
        launchCapabilityRequired = capabilityRequired
        launchCapability = capability
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            acceptingActivityCommands = true
            session.setLockScreenRemoteOwner(true)
            if (!keyguard.isKeyguardLocked) connectUnlocked()
        }
    }

    companion object {
        internal const val EXTRA_REQUIRE_LAUNCH_CAPABILITY = "require_launch_capability"
        internal const val EXTRA_LAUNCH_CAPABILITY = "launch_capability"
        private const val LOCKED_COMMAND_MAX_AGE_MS = 1_000L
        private const val UNLOCKED_COMMAND_MAX_AGE_MS = 3_000L
        private const val KEYGUARD_POLL_MS = 250L
    }
}

private data class ActivityLease(
    val epoch: Long,
    val capabilityRequired: Boolean,
    val capability: String?,
)

private val FullRemoteColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF1C1C1E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFA1A1A6),
)

@Composable
private fun FullRemoteScreen(
    state: ConnectionState,
    deviceName: String,
    error: String?,
    keyguardLocked: Boolean,
    lockScreenControlsEnabled: Boolean,
    onClose: () -> Unit,
    onAction: (QuickRemoteAction) -> Unit,
    onPower: () -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .safeGesturesPadding(),
    ) {
        val compact = maxHeight < 620.dp
        val outerSpacing = if (compact) 8.dp else 20.dp
        val controlSpacing = if (compact) 8.dp else 16.dp
        val roundKeySize = if (compact) 56.dp else 72.dp
        val volumeHeight = if (compact) 46.dp else 56.dp
        val availableWidth = maxWidth
        val availableHeight = maxHeight

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = if (compact) 6.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 58.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopCircleButton(Icons.Rounded.Close, "Close", onClose)
                Column(
                    Modifier.weight(1f).padding(horizontal = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        deviceName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(connectionColor(state), CircleShape),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            connectionLabel(state, error, keyguardLocked),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                TopCircleButton(
                    Icons.Rounded.PowerSettingsNew,
                    if (keyguardLocked) "Power; unlock required" else "Power",
                    onPower,
                )
            }

            if (compact && availableWidth >= 560.dp && availableWidth > availableHeight) {
                val sideWidth = when {
                    availableWidth >= 800.dp -> 320.dp
                    availableWidth >= 650.dp -> 270.dp
                    else -> 220.dp
                }
                Spacer(Modifier.height(outerSpacing))
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TouchSurface(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        selectRequiresUnlock = keyguardLocked,
                        onAction = onAction,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(
                        Modifier.width(sideWidth).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RemoteKeyRow(keyguardLocked, roundKeySize, onAction)
                        Spacer(Modifier.height(controlSpacing))
                        VolumeBar(onAction, volumeHeight)
                        LockHint(keyguardLocked, lockScreenControlsEnabled, compact)
                    }
                }
            } else {
                Spacer(Modifier.height(outerSpacing))
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    TouchSurface(
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .heightIn(max = if (compact) 360.dp else 540.dp)
                            .fillMaxHeight(),
                        selectRequiresUnlock = keyguardLocked,
                        onAction = onAction,
                    )
                }
                Spacer(Modifier.height(if (compact) 10.dp else 22.dp))
                RemoteKeyRow(keyguardLocked, roundKeySize, onAction)
                Spacer(Modifier.height(controlSpacing))
                VolumeBar(onAction, volumeHeight)
                LockHint(keyguardLocked, lockScreenControlsEnabled, compact)
            }
        }
    }
}

@Composable
private fun RemoteKeyRow(
    keyguardLocked: Boolean,
    keySize: Dp,
    onAction: (QuickRemoteAction) -> Unit,
) {
    Row(
        Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundKey(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            label = if (keyguardLocked) "Back; unlock required" else "Back",
            size = keySize,
            onClick = { onAction(QuickRemoteAction.Back) },
        )
        RoundKey(
            icon = Icons.Rounded.Tv,
            label = if (keyguardLocked) "TV / Home; unlock required" else "TV / Home",
            size = keySize,
            onClick = { onAction(QuickRemoteAction.Home) },
        )
        RoundKey(
            drawable = R.drawable.ic_play_pause,
            label = "Play or pause",
            size = keySize,
            onClick = { onAction(QuickRemoteAction.PlayPause) },
        )
    }
}

@Composable
private fun LockHint(
    keyguardLocked: Boolean,
    lockScreenControlsEnabled: Boolean,
    compact: Boolean,
) {
    if (keyguardLocked) {
        Row(
            Modifier
                .heightIn(min = if (compact) 24.dp else 34.dp)
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp), tint = Color(0xFFA1A1A6))
            Spacer(Modifier.width(6.dp))
            Text(
                if (lockScreenControlsEnabled) {
                    "Back, OK, TV and power require unlock"
                } else {
                    "Unlock to use the remote"
                },
                color = Color(0xFFA1A1A6),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else if (!compact) {
        Spacer(Modifier.height(34.dp))
    }
}

@Composable
private fun TouchSurface(
    modifier: Modifier,
    selectRequiresUnlock: Boolean,
    onAction: (QuickRemoteAction) -> Unit,
) {
    val shape = RoundedCornerShape(42.dp)
    val hint = Color.White.copy(alpha = 0.20f)
    BoxWithConstraints(
        modifier
            .background(Color(0xFF1C1C1E), shape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .pointerInput(onAction) {
                detectTapGestures(onTap = { onAction(QuickRemoteAction.Select) })
            }
            .pointerInput(onAction) {
                var total = Offset.Zero
                detectDragGestures(
                    onDragStart = { total = Offset.Zero },
                    onDragCancel = { total = Offset.Zero },
                    onDragEnd = {
                        if (total != Offset.Zero) {
                            val action = if (abs(total.x) > abs(total.y)) {
                                if (total.x > 0) QuickRemoteAction.Right else QuickRemoteAction.Left
                            } else {
                                if (total.y > 0) QuickRemoteAction.Down else QuickRemoteAction.Up
                            }
                            onAction(action)
                        }
                        total = Offset.Zero
                    },
                ) { change, dragAmount ->
                    change.consume()
                    total += dragAmount
                }
            }
            .semantics {
                contentDescription = "Apple TV touch surface"
                onClick(if (selectRequiresUnlock) "Select; unlock required" else "Select") {
                    onAction(QuickRemoteAction.Select)
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction("Navigate up") { onAction(QuickRemoteAction.Up); true },
                    CustomAccessibilityAction("Navigate down") { onAction(QuickRemoteAction.Down); true },
                    CustomAccessibilityAction("Navigate left") { onAction(QuickRemoteAction.Left); true },
                    CustomAccessibilityAction("Navigate right") { onAction(QuickRemoteAction.Right); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (maxHeight >= 128.dp && maxWidth >= 128.dp) {
            Icon(
                Icons.Rounded.KeyboardArrowUp,
                null,
                Modifier.align(Alignment.TopCenter).padding(top = 14.dp).size(28.dp),
                tint = hint,
            )
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                null,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).size(28.dp),
                tint = hint,
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                null,
                Modifier.align(Alignment.CenterStart).padding(start = 14.dp).size(28.dp),
                tint = hint,
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                null,
                Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).size(28.dp),
                tint = hint,
            )
        }
        if (maxHeight >= 180.dp && maxWidth >= 240.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Swipe to navigate", color = Color(0xFFA1A1A6), fontSize = 15.sp)
                Spacer(Modifier.height(5.dp))
                Text("Tap to select", color = Color(0xFFA1A1A6), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TopCircleButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(46.dp).semantics { role = Role.Button },
        shape = CircleShape,
        color = Color(0xFF1C1C1E),
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(22.dp), tint = Color.White)
        }
    }
}

@Composable
private fun RoundKey(
    label: String,
    icon: ImageVector? = null,
    drawable: Int? = null,
    size: Dp = 72.dp,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(size).semantics { role = Role.Button },
        shape = CircleShape,
        color = Color(0xFF1C1C1E),
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, label, Modifier.size(29.dp), tint = Color.White)
            } else if (drawable != null) {
                Icon(painterResource(drawable), label, Modifier.size(29.dp), tint = Color.White)
            }
        }
    }
}

@Composable
private fun VolumeBar(onAction: (QuickRemoteAction) -> Unit, height: Dp = 56.dp) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .height(height)
            .background(Color(0xFF1C1C1E), shape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VolumeHalf(
            icon = Icons.Rounded.VolumeDown,
            label = "Volume down",
            onClick = { onAction(QuickRemoteAction.VolumeDown) },
        )
        Box(Modifier.width(1.dp).fillMaxHeight(0.42f).background(Color.White.copy(alpha = 0.14f)))
        VolumeHalf(
            icon = Icons.Rounded.VolumeUp,
            label = "Volume up",
            onClick = { onAction(QuickRemoteAction.VolumeUp) },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.VolumeHalf(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .semantics { role = Role.Button },
        color = Color.Transparent,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(25.dp), tint = Color.White)
        }
    }
}

private fun connectionColor(state: ConnectionState): Color = when (state) {
    ConnectionState.Connected -> Color(0xFF30D158)
    ConnectionState.Connecting -> Color(0xFFFFD60A)
    ConnectionState.Disconnected -> Color(0xFFFF453A)
}

private fun connectionLabel(
    state: ConnectionState,
    error: String?,
    keyguardLocked: Boolean,
): String = when (state) {
    ConnectionState.Connected -> "Connected"
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Disconnected -> if (keyguardLocked) "Unlock once to connect" else error ?: "Disconnected"
}
