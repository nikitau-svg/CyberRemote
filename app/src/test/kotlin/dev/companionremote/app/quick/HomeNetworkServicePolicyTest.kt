package dev.companionremote.app.quick

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `continuity callback storm produces at most one pending retry`() {
        val attempts = BoundedContinuityRecoveryAttempts()

        assertEquals(
            ContinuityRecoveryRequest.Start,
            attempts.request(recoveryActive = false),
        )
        repeat(20) {
            assertEquals(
                ContinuityRecoveryRequest.RetryPending,
                attempts.request(recoveryActive = true),
            )
        }
        assertTrue(attempts.onFinished(success = false))

        // The pending retry is already the second and final attempt.
        assertEquals(
            ContinuityRecoveryRequest.Ignore,
            attempts.request(recoveryActive = true),
        )
        assertFalse(attempts.onFinished(success = false))
        assertEquals(
            ContinuityRecoveryRequest.Ignore,
            attempts.request(recoveryActive = false),
        )
    }

    @Test
    fun `successful continuity recovery discards a pending retry and reset starts a new window`() {
        val attempts = BoundedContinuityRecoveryAttempts()

        assertEquals(
            ContinuityRecoveryRequest.Start,
            attempts.request(recoveryActive = false),
        )
        assertEquals(
            ContinuityRecoveryRequest.RetryPending,
            attempts.request(recoveryActive = true),
        )
        assertFalse(attempts.onFinished(success = true))
        assertEquals(
            ContinuityRecoveryRequest.Ignore,
            attempts.request(recoveryActive = false),
        )

        attempts.reset()
        assertEquals(
            ContinuityRecoveryRequest.Start,
            attempts.request(recoveryActive = false),
        )
    }
}
