package dev.companionremote.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.companionremote.app.data.AppSkin
import dev.companionremote.app.data.ThemeMode
import dev.companionremote.app.diagnostics.Diagnostics
import dev.companionremote.app.diagnostics.Diagnostics.DiagnosticToken
import dev.companionremote.app.discovery.ACCESS_LOCAL_NETWORK_PERMISSION
import dev.companionremote.app.discovery.hasLocalNetworkPermission
import dev.companionremote.app.i18n.LocalAppStrings
import dev.companionremote.app.i18n.currentSystemLanguage
import dev.companionremote.app.i18n.resolveStrings
import dev.companionremote.app.permissions.AppEntryPermissionAction
import dev.companionremote.app.permissions.AppEntryPermissionGate
import dev.companionremote.app.permissions.AppRuntimePermission
import dev.companionremote.app.permissions.AppRuntimePermissionState
import dev.companionremote.app.permissions.hasMicrophoneRuntimePermission
import dev.companionremote.app.permissions.hasNotificationRuntimePermission
import dev.companionremote.app.permissions.missingAppRuntimePermissions
import dev.companionremote.app.theme.LocalGlass
import dev.companionremote.app.theme.skinBackground
import dev.companionremote.app.theme.skinColorScheme
import dev.companionremote.app.theme.skinGlass
import dev.companionremote.app.ui.DeviceListScreen
import dev.companionremote.app.ui.PairingScreen
import dev.companionremote.app.ui.RemoteScreen
import dev.companionremote.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()
    private val appEntryPermissionGate = AppEntryPermissionGate()
    private val appPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        results.forEach { (permission, granted) ->
            Diagnostics.record(
                this,
                "app_permissions",
                "result",
                "permission" to DiagnosticToken(permission.diagnosticPermissionName()),
                "granted" to granted,
            )
        }
        handleAppEntryPermissionAction(
            appEntryPermissionGate.permissionResult(hasLocalNetworkPermission()),
        )
        viewModel.onRuntimePermissionsChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.record(this, "main_activity", "created")
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val skin by viewModel.skin.collectAsState()
            CompanionTheme(themeMode, skin) {
                val screen by viewModel.screen.collectAsState()
                val language by viewModel.language.collectAsState()
                val strings = resolveStrings(language, currentSystemLanguage())
                CompositionLocalProvider(LocalAppStrings provides strings) {
                    Surface(color = Color.Transparent) {
                        when (val current = screen) {
                            is Screen.DeviceList -> DeviceListScreen(
                                viewModel = viewModel,
                                onRescan = { ensureEntryPermissionsThenScan(userInitiated = true) },
                            )
                            is Screen.Settings -> SettingsScreen(viewModel)
                            is Screen.Pairing -> PairingScreen(viewModel, current.device)
                            is Screen.Remote -> RemoteScreen(viewModel, current.device)
                        }
                    }
                }
            }
        }
        handleIntent(intent)
        appEntryPermissionGate.beginEntry()
        ensureEntryPermissionsThenScan(userInitiated = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        appEntryPermissionGate.beginEntry()
        ensureEntryPermissionsThenScan(userInitiated = false)
    }

    private fun handleIntent(intent: Intent?) {
        val token = intent?.getStringExtra(EXTRA_OPEN_LAST_REMOTE_TOKEN)
        if (RemoteSessionManager.get(this).consumeOpenFullRemoteToken(token)) {
            intent?.removeExtra(EXTRA_OPEN_LAST_REMOTE_TOKEN)
            viewModel.openLastRemote()
        }
    }

    private fun ensureEntryPermissionsThenScan(userInitiated: Boolean) {
        val missing = missingAppRuntimePermissions(
            sdkInt = Build.VERSION.SDK_INT,
            state = currentRuntimePermissionState(),
        )
        handleAppEntryPermissionAction(
            appEntryPermissionGate.requestPermissionsThenScan(userInitiated, missing),
        )
    }

    private fun handleAppEntryPermissionAction(action: AppEntryPermissionAction) {
        when (action) {
            is AppEntryPermissionAction.RunScan -> viewModel.startScan(action.userInitiated)
            is AppEntryPermissionAction.Request -> {
                Diagnostics.record(
                    this,
                    "app_permissions",
                    "requested",
                    "sdk" to Build.VERSION.SDK_INT,
                    "local_network" to (AppRuntimePermission.LocalNetwork in action.permissions),
                    "notifications" to (AppRuntimePermission.Notifications in action.permissions),
                    "microphone" to (AppRuntimePermission.Microphone in action.permissions),
                )
                appPermissions.launch(action.permissions.map { it.androidPermissionName() }.toTypedArray())
            }
            AppEntryPermissionAction.SkipScan -> Diagnostics.record(
                this,
                "discovery",
                "scan_skipped",
                "reason" to DiagnosticToken("permission"),
            )
            AppEntryPermissionAction.None -> Unit
        }
    }

    private fun currentRuntimePermissionState(): AppRuntimePermissionState =
        AppRuntimePermissionState(
            localNetworkGranted = hasLocalNetworkPermission(),
            notificationsGranted = hasNotificationRuntimePermission(),
            microphoneGranted = hasMicrophoneRuntimePermission(),
        )

    private fun AppRuntimePermission.androidPermissionName(): String = when (this) {
        AppRuntimePermission.LocalNetwork -> ACCESS_LOCAL_NETWORK_PERMISSION
        AppRuntimePermission.Notifications -> Manifest.permission.POST_NOTIFICATIONS
        AppRuntimePermission.Microphone -> Manifest.permission.RECORD_AUDIO
    }

    private fun String.diagnosticPermissionName(): String = when (this) {
        ACCESS_LOCAL_NETWORK_PERMISSION -> "local_network"
        Manifest.permission.POST_NOTIFICATIONS -> "notifications"
        Manifest.permission.RECORD_AUDIO -> "microphone"
        else -> "unknown"
    }

    override fun onStart() {
        super.onStart()
        Diagnostics.record(this, "main_activity", "started")
        viewModel.onForeground()
    }

    override fun onStop() {
        Diagnostics.record(this, "main_activity", "stopped")
        viewModel.onBackground()
        super.onStop()
    }

    companion object {
        const val EXTRA_OPEN_LAST_REMOTE_TOKEN = "open_last_remote_token"
    }
}

@Composable
fun CompanionTheme(themeMode: ThemeMode, skin: AppSkin, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    MaterialTheme(colorScheme = skinColorScheme(skin, dark)) {
        CompositionLocalProvider(LocalGlass provides skinGlass(dark)) {
            Box(Modifier.fillMaxSize().background(skinBackground(skin, dark))) {
                content()
            }
        }
    }
}
