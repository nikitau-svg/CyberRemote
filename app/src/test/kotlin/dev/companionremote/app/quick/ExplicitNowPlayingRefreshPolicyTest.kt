package dev.companionremote.app.quick

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExplicitNowPlayingRefreshPolicyTest {
    @Test
    fun `first eligible request is scheduled and duplicates observe cooldown`() {
        val policy = ExplicitNowPlayingRefreshPolicy(cooldownMs = 10_000L)

        assertDecision(policy, 1_000L, ExplicitNowPlayingRefreshDecision.Scheduled)
        assertDecision(policy, 10_999L, ExplicitNowPlayingRefreshDecision.Cooldown)
        assertDecision(policy, 11_000L, ExplicitNowPlayingRefreshDecision.Scheduled)
    }

    @Test
    fun `ineligible attempts do not consume cooldown`() {
        val policy = ExplicitNowPlayingRefreshPolicy(cooldownMs = 10_000L)

        assertEquals(
            ExplicitNowPlayingRefreshDecision.NotHome,
            policy.evaluate(
                nowMs = 1_000L,
                homeAuthorized = false,
                connected = true,
                boundedRefreshPending = false,
            ),
        )
        assertEquals(
            ExplicitNowPlayingRefreshDecision.NotConnected,
            policy.evaluate(
                nowMs = 2_000L,
                homeAuthorized = true,
                connected = false,
                boundedRefreshPending = false,
            ),
        )
        assertEquals(
            ExplicitNowPlayingRefreshDecision.BoundedPending,
            policy.evaluate(
                nowMs = 3_000L,
                homeAuthorized = true,
                connected = true,
                boundedRefreshPending = true,
            ),
        )
        assertDecision(policy, 3_001L, ExplicitNowPlayingRefreshDecision.Scheduled)
    }

    private fun assertDecision(
        policy: ExplicitNowPlayingRefreshPolicy,
        nowMs: Long,
        expected: ExplicitNowPlayingRefreshDecision,
    ) {
        assertEquals(
            expected,
            policy.evaluate(
                nowMs = nowMs,
                homeAuthorized = true,
                connected = true,
                boundedRefreshPending = false,
            ),
        )
    }
}
