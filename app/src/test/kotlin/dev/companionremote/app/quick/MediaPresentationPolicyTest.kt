package dev.companionremote.app.quick

import dev.companionremote.app.ConnectionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaPresentationPolicyTest {
    @Test
    fun `connected home network exposes media controls before metadata arrives`() {
        assertTrue(
            shouldUseMediaPresentation(
                homeAuthorized = true,
                connectionState = ConnectionState.Connected,
            ),
        )
    }

    @Test
    fun `disconnected service switches from watcher to media after home edge reconnects`() {
        val reconnectPolicy = HomeNetworkServicePolicy()

        assertFalse(
            shouldUseMediaPresentation(
                homeAuthorized = false,
                connectionState = ConnectionState.Disconnected,
            ),
        )
        assertEquals(
            HomeNetworkServiceAction.Disconnect,
            reconnectPolicy.evaluate(
                leaseActive = true,
                reconnectArmed = true,
                authorized = false,
                connectedOrConnecting = false,
                connectionAttemptActive = false,
            ),
        )

        assertEquals(
            HomeNetworkServiceAction.Reconnect,
            reconnectPolicy.evaluate(
                leaseActive = true,
                reconnectArmed = true,
                authorized = true,
                connectedOrConnecting = false,
                connectionAttemptActive = false,
            ),
        )
        assertFalse(
            shouldUseMediaPresentation(
                homeAuthorized = true,
                connectionState = ConnectionState.Disconnected,
            ),
        )

        assertTrue(
            shouldUseMediaPresentation(
                homeAuthorized = true,
                connectionState = ConnectionState.Connected,
            ),
        )
    }

    @Test
    fun `watcher remains while home connection is not established`() {
        assertFalse(
            shouldUseMediaPresentation(
                homeAuthorized = true,
                connectionState = ConnectionState.Connecting,
            ),
        )
        assertFalse(
            shouldUseMediaPresentation(
                homeAuthorized = true,
                connectionState = ConnectionState.Disconnected,
            ),
        )
    }

    @Test
    fun `media controls are removed outside the authorized home network`() {
        assertFalse(
            shouldUseMediaPresentation(
                homeAuthorized = false,
                connectionState = ConnectionState.Connected,
            ),
        )
    }
}
