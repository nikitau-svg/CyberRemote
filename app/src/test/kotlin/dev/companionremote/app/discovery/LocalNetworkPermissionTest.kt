package dev.companionremote.app.discovery

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
}
