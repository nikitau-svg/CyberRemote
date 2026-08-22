package dev.companionremote.app.permissions

internal enum class AppRuntimePermission {
    LocalNetwork,
    Notifications,
    Microphone,
}

internal data class AppRuntimePermissionState(
    val localNetworkGranted: Boolean,
    val notificationsGranted: Boolean,
    val microphoneGranted: Boolean,
)

internal fun missingAppRuntimePermissions(
    sdkInt: Int,
    state: AppRuntimePermissionState,
): List<AppRuntimePermission> = buildList {
    if (sdkInt >= 37 && !state.localNetworkGranted) add(AppRuntimePermission.LocalNetwork)
    if (sdkInt >= 33 && !state.notificationsGranted) add(AppRuntimePermission.Notifications)
    if (!state.microphoneGranted) add(AppRuntimePermission.Microphone)
}

internal sealed interface AppEntryPermissionAction {
    data class Request(val permissions: List<AppRuntimePermission>) : AppEntryPermissionAction
    data class RunScan(val userInitiated: Boolean) : AppEntryPermissionAction
    data object SkipScan : AppEntryPermissionAction
    data object None : AppEntryPermissionAction
}

/**
 * Owns the single permission transaction shown when the main app is entered.
 * A manual rescan may retry denied local-network access, but does not nag for
 * an optional permission that was already denied during this activity entry.
 */
internal class AppEntryPermissionGate {
    private val attempted = mutableSetOf<AppRuntimePermission>()
    private var requestInFlight = false
    private var pendingUserInitiatedScan: Boolean? = null

    fun beginEntry() {
        if (!requestInFlight) attempted.clear()
    }

    fun requestPermissionsThenScan(
        userInitiated: Boolean,
        missing: List<AppRuntimePermission>,
    ): AppEntryPermissionAction {
        pendingUserInitiatedScan = userInitiated
        if (requestInFlight) return AppEntryPermissionAction.None

        val requestable = missing.filter { permission ->
            permission !in attempted ||
                (userInitiated && permission == AppRuntimePermission.LocalNetwork)
        }
        if (requestable.isNotEmpty()) {
            attempted += requestable
            requestInFlight = true
            return AppEntryPermissionAction.Request(requestable)
        }

        pendingUserInitiatedScan = null
        return if (AppRuntimePermission.LocalNetwork in missing) {
            AppEntryPermissionAction.SkipScan
        } else {
            AppEntryPermissionAction.RunScan(userInitiated)
        }
    }

    fun permissionResult(localNetworkGranted: Boolean): AppEntryPermissionAction {
        requestInFlight = false
        val userInitiated = pendingUserInitiatedScan ?: return AppEntryPermissionAction.None
        pendingUserInitiatedScan = null
        return if (localNetworkGranted) {
            AppEntryPermissionAction.RunScan(userInitiated)
        } else {
            AppEntryPermissionAction.SkipScan
        }
    }
}
