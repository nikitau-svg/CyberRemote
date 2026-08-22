package dev.companionremote.app.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalNetworkPermissionTest {
    @Test
    fun `permission is not required before Android 17`() {
        assertFalse(needsLocalNetworkPermission(sdkInt = 36, permissionGranted = false))
    }

    @Test
    fun `permission is required when Android 17 reports it denied`() {
        assertTrue(needsLocalNetworkPermission(sdkInt = 37, permissionGranted = false))
        assertFalse(needsLocalNetworkPermission(sdkInt = 37, permissionGranted = true))
    }

    @Test
    fun `legacy scan runs immediately without a permission prompt`() {
        val gate = LocalNetworkPermissionGate()

        assertEquals(
            LocalNetworkScanAction.RunScan(userInitiated = false),
            gate.requestScan(userInitiated = false, sdkInt = 36, permissionGranted = false),
        )
    }

    @Test
    fun `grant retries exactly the pending automatic scan`() {
        val gate = LocalNetworkPermissionGate()

        assertEquals(
            LocalNetworkScanAction.RequestPermission,
            gate.requestScan(userInitiated = false, sdkInt = 37, permissionGranted = false),
        )
        assertEquals(
            LocalNetworkScanAction.RunScan(userInitiated = false),
            gate.permissionResult(granted = true),
        )
        assertEquals(LocalNetworkScanAction.None, gate.permissionResult(granted = true))
    }

    @Test
    fun `denial does not scan and a manual retry can request again`() {
        val gate = LocalNetworkPermissionGate()

        assertEquals(
            LocalNetworkScanAction.RequestPermission,
            gate.requestScan(userInitiated = false, sdkInt = 37, permissionGranted = false),
        )
        assertEquals(LocalNetworkScanAction.None, gate.permissionResult(granted = false))
        assertEquals(
            LocalNetworkScanAction.RequestPermission,
            gate.requestScan(userInitiated = true, sdkInt = 37, permissionGranted = false),
        )
        assertEquals(
            LocalNetworkScanAction.RunScan(userInitiated = true),
            gate.permissionResult(granted = true),
        )
    }
}
