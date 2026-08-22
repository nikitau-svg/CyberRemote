package dev.companionremote.app.discovery

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Android 17 local-network runtime permission, kept literal for compileSdk 35. */
internal const val ACCESS_LOCAL_NETWORK_PERMISSION =
    "android.permission.ACCESS_LOCAL_NETWORK"

internal const val LOCAL_NETWORK_PERMISSION_SDK = 37

/**
 * Stock Android implicitly grants legacy-target apps, but some Android 17 OEM
 * builds enforce the permission anyway. Checking on every API 37 device keeps
 * both behaviors safe: an implicit grant passes, while an OEM denial prompts.
 */
internal fun needsLocalNetworkPermission(
    sdkInt: Int,
    permissionGranted: Boolean,
): Boolean = sdkInt >= LOCAL_NETWORK_PERMISSION_SDK && !permissionGranted

internal fun Context.hasLocalNetworkPermission(): Boolean =
    !needsLocalNetworkPermission(
        sdkInt = Build.VERSION.SDK_INT,
        permissionGranted = checkSelfPermission(ACCESS_LOCAL_NETWORK_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED,
    )

internal sealed interface LocalNetworkScanAction {
    data class RunScan(val userInitiated: Boolean) : LocalNetworkScanAction
    data object RequestPermission : LocalNetworkScanAction
    data object None : LocalNetworkScanAction
}

/** Keeps a permission result paired with exactly one pending scan request. */
internal class LocalNetworkPermissionGate {
    private var pendingUserInitiated: Boolean? = null

    fun requestScan(
        userInitiated: Boolean,
        sdkInt: Int,
        permissionGranted: Boolean,
    ): LocalNetworkScanAction {
        if (!needsLocalNetworkPermission(sdkInt, permissionGranted)) {
            pendingUserInitiated = null
            return LocalNetworkScanAction.RunScan(userInitiated)
        }
        pendingUserInitiated = userInitiated
        return LocalNetworkScanAction.RequestPermission
    }

    fun permissionResult(granted: Boolean): LocalNetworkScanAction {
        val userInitiated = pendingUserInitiated ?: return LocalNetworkScanAction.None
        pendingUserInitiated = null
        return if (granted) {
            LocalNetworkScanAction.RunScan(userInitiated)
        } else {
            LocalNetworkScanAction.None
        }
    }
}
