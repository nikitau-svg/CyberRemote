package dev.companionremote.app.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AtvDiscoveryTest {
    @Test
    fun `user initiated discovery keeps legacy compatibility`() {
        assertEquals(
            NsdDiscoveryMode.Legacy,
            nsdDiscoveryMode(networkRequested = false, sdkInt = 26),
        )
        assertEquals(
            NsdDiscoveryMode.Legacy,
            nsdDiscoveryMode(networkRequested = false, sdkInt = 35),
        )
    }

    @Test
    fun `automatic discovery is network scoped on Android 13 and newer`() {
        assertEquals(
            NsdDiscoveryMode.NetworkScoped,
            nsdDiscoveryMode(networkRequested = true, sdkInt = 33),
        )
        assertEquals(
            NsdDiscoveryMode.NetworkScoped,
            nsdDiscoveryMode(networkRequested = true, sdkInt = 35),
        )
    }

    @Test
    fun `automatic discovery fails closed when Android cannot scope NSD`() {
        assertEquals(
            NsdDiscoveryMode.UnsupportedScoped,
            nsdDiscoveryMode(networkRequested = true, sdkInt = 32),
        )
    }
}
