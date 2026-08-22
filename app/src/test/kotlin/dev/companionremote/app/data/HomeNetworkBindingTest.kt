package dev.companionremote.app.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeNetworkBindingTest {
    private val binding = HomeNetworkBinding(
        deviceName = "Living Room 电视",
        deviceModel = "AppleTV14,1",
        endpointHost = "192.0.2.42",
        endpointPort = 49152,
        deviceFingerprint = "h1:device",
        networkFingerprint = "h1:home",
    )

    @Test
    fun `binding codec round trips unicode and endpoint`() {
        assertEquals(binding, HomeNetworkBindingCodec.decode(HomeNetworkBindingCodec.encode(binding)))
    }

    @Test
    fun `binding codec rejects malformed and trailing data`() {
        assertNull(HomeNetworkBindingCodec.decode("not base64!"))
        assertNull(HomeNetworkBindingCodec.decode(HomeNetworkBindingCodec.encode(binding) + "AA"))
    }

    @Test
    fun `automatic access requires both home network and expected TV identity`() {
        assertTrue(
            HomeNetworkPolicy.allowsAutomaticAccess(
                binding,
                currentNetworkFingerprint = "h1:home",
                expectedDeviceFingerprint = "h1:device",
            ),
        )
        assertFalse(
            HomeNetworkPolicy.allowsAutomaticAccess(
                binding,
                currentNetworkFingerprint = "h1:other-network",
                expectedDeviceFingerprint = "h1:device",
            ),
        )
        assertFalse(
            HomeNetworkPolicy.allowsAutomaticAccess(
                binding,
                currentNetworkFingerprint = "h1:home",
                expectedDeviceFingerprint = "h1:other-tv",
            ),
        )
        assertFalse(
            HomeNetworkPolicy.allowsAutomaticAccess(
                binding,
                currentNetworkFingerprint = null,
                expectedDeviceFingerprint = "h1:device",
            ),
        )
    }
}
