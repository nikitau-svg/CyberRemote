package dev.companionremote.app.quick

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HomeNetworkServicePolicyTest {
    @Test
    fun `reconnect backoff is bounded and resettable`() {
        val backoff = BoundedReconnectBackoff(longArrayOf(2L, 10L, 30L))
        assertEquals(2L, backoff.nextDelayMs())
        assertEquals(10L, backoff.nextDelayMs())
        assertEquals(30L, backoff.nextDelayMs())
        assertNull(backoff.nextDelayMs())
        backoff.reset()
        assertEquals(2L, backoff.nextDelayMs())
    }

    @Test
    fun `disconnects away and reconnects exactly once on return`() {
        val policy = HomeNetworkServicePolicy()
        assertEquals(
            HomeNetworkServiceAction.Disconnect,
            policy.evaluate(
                true, true, false,
                connectedOrConnecting = true,
                connectionAttemptActive = false,
            ),
        )
        assertEquals(
            HomeNetworkServiceAction.None,
            policy.evaluate(
                true, true, false,
                connectedOrConnecting = false,
                connectionAttemptActive = false,
            ),
        )
        assertEquals(
            HomeNetworkServiceAction.Reconnect,
            policy.evaluate(
                true, true, true,
                connectedOrConnecting = false,
                connectionAttemptActive = false,
            ),
        )
        assertEquals(
            HomeNetworkServiceAction.None,
            policy.evaluate(
                true, true, true,
                connectedOrConnecting = false,
                connectionAttemptActive = false,
            ),
        )
    }

    @Test
    fun `never reconnects without an armed service lease`() {
        val policy = HomeNetworkServicePolicy()
        assertEquals(
            HomeNetworkServiceAction.None,
            policy.evaluate(
                false, true, true,
                connectedOrConnecting = false,
                connectionAttemptActive = false,
            ),
        )
        assertEquals(
            HomeNetworkServiceAction.None,
            policy.evaluate(
                true, false, true,
                connectedOrConnecting = false,
                connectionAttemptActive = false,
            ),
        )
    }
}
