package dev.companionremote.app.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppEntryPermissionGateTest {

    @Test
    fun `permission policy follows Android sdk requirements`() {
        val denied = AppRuntimePermissionState(
            localNetworkGranted = false,
            notificationsGranted = false,
            microphoneGranted = false,
        )

        assertEquals(
            listOf(AppRuntimePermission.Microphone),
            missingAppRuntimePermissions(sdkInt = 32, state = denied),
        )
        assertEquals(
            listOf(AppRuntimePermission.Notifications, AppRuntimePermission.Microphone),
            missingAppRuntimePermissions(sdkInt = 33, state = denied),
        )
        assertEquals(
            listOf(
                AppRuntimePermission.LocalNetwork,
                AppRuntimePermission.Notifications,
                AppRuntimePermission.Microphone,
            ),
            missingAppRuntimePermissions(sdkInt = 37, state = denied),
        )
    }

    @Test
    fun `policy omits permissions that are already granted`() {
        val state = AppRuntimePermissionState(
            localNetworkGranted = true,
            notificationsGranted = false,
            microphoneGranted = true,
        )

        assertEquals(
            listOf(AppRuntimePermission.Notifications),
            missingAppRuntimePermissions(sdkInt = 37, state = state),
        )
    }

    @Test
    fun `entry requests every missing runtime permission in one transaction`() {
        val gate = AppEntryPermissionGate()
        val missing = listOf(
            AppRuntimePermission.LocalNetwork,
            AppRuntimePermission.Notifications,
            AppRuntimePermission.Microphone,
        )

        gate.beginEntry()

        assertEquals(
            AppEntryPermissionAction.Request(missing),
            gate.requestPermissionsThenScan(userInitiated = false, missing = missing),
        )
        assertEquals(
            AppEntryPermissionAction.RunScan(userInitiated = false),
            gate.permissionResult(localNetworkGranted = true),
        )
    }

    @Test
    fun `entry scans immediately when every runtime permission is granted`() {
        val gate = AppEntryPermissionGate()

        gate.beginEntry()

        assertEquals(
            AppEntryPermissionAction.RunScan(userInitiated = false),
            gate.requestPermissionsThenScan(userInitiated = false, missing = emptyList()),
        )
    }

    @Test
    fun `notification or microphone denial does not block discovery`() {
        val gate = AppEntryPermissionGate()
        val optionalMissing = listOf(
            AppRuntimePermission.Notifications,
            AppRuntimePermission.Microphone,
        )

        gate.beginEntry()
        gate.requestPermissionsThenScan(userInitiated = false, missing = optionalMissing)

        assertEquals(
            AppEntryPermissionAction.RunScan(userInitiated = false),
            gate.permissionResult(localNetworkGranted = true),
        )
    }

    @Test
    fun `local network denial skips discovery and manual rescan retries only local access`() {
        val gate = AppEntryPermissionGate()
        val missing = listOf(
            AppRuntimePermission.LocalNetwork,
            AppRuntimePermission.Notifications,
            AppRuntimePermission.Microphone,
        )

        gate.beginEntry()
        gate.requestPermissionsThenScan(userInitiated = false, missing = missing)
        assertEquals(
            AppEntryPermissionAction.SkipScan,
            gate.permissionResult(localNetworkGranted = false),
        )
        assertEquals(
            AppEntryPermissionAction.Request(listOf(AppRuntimePermission.LocalNetwork)),
            gate.requestPermissionsThenScan(userInitiated = true, missing = missing),
        )
    }

    @Test
    fun `duplicate entry while dialog is active does not launch a second request`() {
        val gate = AppEntryPermissionGate()
        val missing = listOf(AppRuntimePermission.LocalNetwork)

        gate.beginEntry()
        gate.requestPermissionsThenScan(userInitiated = false, missing = missing)

        assertEquals(
            AppEntryPermissionAction.None,
            gate.requestPermissionsThenScan(userInitiated = true, missing = missing),
        )
        assertEquals(
            AppEntryPermissionAction.RunScan(userInitiated = true),
            gate.permissionResult(localNetworkGranted = true),
        )
    }
}
