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

    @Test
    fun `connected media presentation survives the bounded departure grace`() {
        assertTrue(
            shouldUseMediaPresentation(
                homeAuthorized = true,
                connectionState = ConnectionState.Disconnected,
                retainedDuringNetworkTransition = true,
            ),
        )
    }

    @Test
    fun `retained snapshot cannot expose media controls without home authorization`() {
        assertFalse(
            shouldUseMediaPresentation(
                homeAuthorized = false,
                connectionState = ConnectionState.Connected,
                retainedDuringNetworkTransition = true,
            ),
        )
    }

    @Test
    fun `stale connected emission cannot release retained media after a handover`() {
        assertFalse(
            shouldReleaseRetainedMedia(
                departureSuspected = false,
                requiresReconnect = true,
                sawConnectionBreak = false,
                retainedObservedAtElapsedMs = 1_000L,
                currentConnectionState = ConnectionState.Connected,
                currentAuthoritative = true,
                currentObservedAtElapsedMs = 1_001L,
            ),
        )
        assertFalse(
            shouldReleaseRetainedMedia(
                departureSuspected = false,
                requiresReconnect = true,
                sawConnectionBreak = true,
                retainedObservedAtElapsedMs = 1_000L,
                currentConnectionState = ConnectionState.Connected,
                currentAuthoritative = true,
                currentObservedAtElapsedMs = 1_000L,
            ),
        )
    }

    @Test
    fun `fresh authoritative state releases retained media after the required edge`() {
        assertTrue(
            shouldReleaseRetainedMedia(
                departureSuspected = false,
                requiresReconnect = true,
                sawConnectionBreak = true,
                retainedObservedAtElapsedMs = 1_000L,
                currentConnectionState = ConnectionState.Connected,
                currentAuthoritative = true,
                currentObservedAtElapsedMs = 1_001L,
            ),
        )
        assertTrue(
            shouldReleaseRetainedMedia(
                departureSuspected = false,
                requiresReconnect = false,
                sawConnectionBreak = false,
                retainedObservedAtElapsedMs = 1_000L,
                currentConnectionState = ConnectionState.Connected,
                currentAuthoritative = true,
                currentObservedAtElapsedMs = 1_001L,
            ),
        )
    }

    @Test
    fun `second handover requires a new connection edge before stale media can release`() {
        val gate = RetainedMediaReconnectGate()
        gate.observe(ConnectionState.Disconnected)
        assertTrue(gate.sawConnectionBreak)

        gate.rearm()
        assertFalse(gate.sawConnectionBreak)
        assertFalse(
            shouldReleaseRetainedMedia(
                departureSuspected = false,
                requiresReconnect = true,
                sawConnectionBreak = gate.sawConnectionBreak,
                retainedObservedAtElapsedMs = 1_000L,
                currentConnectionState = ConnectionState.Connected,
                currentAuthoritative = true,
                currentObservedAtElapsedMs = 1_001L,
            ),
        )

        gate.observe(ConnectionState.Connecting)
        assertTrue(gate.sawConnectionBreak)
    }
}
