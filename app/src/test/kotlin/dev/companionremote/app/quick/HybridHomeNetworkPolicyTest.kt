package dev.companionremote.app.quick

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HybridHomeNetworkPolicyTest {
    @Test
    fun `departure has two second grace then five minute watcher timeout`() {
        val policy = HybridHomeNetworkPolicy()

        assertTransition(
            policy.observe(authorized = true, nowMs = 100L),
            HybridHomeNetworkPhase.Home,
        )
        assertTransition(
            policy.observe(authorized = false, nowMs = 1_000L),
            HybridHomeNetworkPhase.DepartureGrace,
            nextEvaluationAtMs = 3_000L,
        )
        assertTransition(
            policy.onDeadline(nowMs = 2_999L),
            HybridHomeNetworkPhase.DepartureGrace,
            nextEvaluationAtMs = 3_000L,
        )
        assertTransition(
            policy.onDeadline(nowMs = 3_000L),
            HybridHomeNetworkPhase.AwayWatching,
            HybridHomeNetworkEdgeAction.DepartureConfirmed,
            nextEvaluationAtMs = 303_000L,
        )
        assertTransition(
            policy.onDeadline(nowMs = 302_999L),
            HybridHomeNetworkPhase.AwayWatching,
            nextEvaluationAtMs = 303_000L,
        )
        assertTransition(
            policy.onDeadline(nowMs = 303_000L),
            HybridHomeNetworkPhase.Stopped,
            HybridHomeNetworkEdgeAction.StopService,
        )
    }

    @Test
    fun `return during grace emits one recovery edge and cancels departure`() {
        val policy = HybridHomeNetworkPolicy()

        policy.observe(authorized = true, nowMs = 0L)
        policy.observe(authorized = false, nowMs = 100L)
        assertTransition(
            policy.observe(authorized = true, nowMs = 1_500L),
            HybridHomeNetworkPhase.Home,
            HybridHomeNetworkEdgeAction.ReturnedHome,
        )

        assertTransition(
            policy.observe(authorized = true, nowMs = 1_501L),
            HybridHomeNetworkPhase.Home,
        )

        // A stale timer from the cancelled grace period cannot cause a departure.
        assertTransition(
            policy.onDeadline(nowMs = 2_100L),
            HybridHomeNetworkPhase.Home,
        )
    }

    @Test
    fun `return while watcher is alive cancels full stop exactly once`() {
        val policy = HybridHomeNetworkPolicy(
            departureGraceMs = 2_000L,
            watcherTimeoutMs = 300_000L,
        )

        policy.observe(authorized = false, nowMs = 0L)
        policy.onDeadline(nowMs = 2_000L)
        assertTransition(
            policy.observe(authorized = true, nowMs = 250_000L),
            HybridHomeNetworkPhase.Home,
            HybridHomeNetworkEdgeAction.ReturnedHome,
        )
        assertTransition(
            policy.observe(authorized = true, nowMs = 250_001L),
            HybridHomeNetworkPhase.Home,
        )

        // A stale watcher timeout cannot stop a service after a real return.
        assertTransition(
            policy.onDeadline(nowMs = 302_000L),
            HybridHomeNetworkPhase.Home,
        )
    }

    @Test
    fun `return from watcher permits a later departure with fresh deadlines`() {
        val policy = HybridHomeNetworkPolicy(
            departureGraceMs = 2_000L,
            watcherTimeoutMs = 300_000L,
        )

        policy.observe(authorized = false, nowMs = 0L)
        policy.onDeadline(nowMs = 2_000L)
        policy.observe(authorized = true, nowMs = 10_000L)

        assertTransition(
            policy.observe(authorized = false, nowMs = 20_000L),
            HybridHomeNetworkPhase.DepartureGrace,
            nextEvaluationAtMs = 22_000L,
        )
        assertTransition(
            policy.onDeadline(nowMs = 22_000L),
            HybridHomeNetworkPhase.AwayWatching,
            HybridHomeNetworkEdgeAction.DepartureConfirmed,
            nextEvaluationAtMs = 322_000L,
        )
        assertTransition(
            policy.onDeadline(nowMs = 322_000L),
            HybridHomeNetworkPhase.Stopped,
            HybridHomeNetworkEdgeAction.StopService,
        )
    }

    @Test
    fun `duplicate unauthorized callbacks neither extend deadlines nor repeat edges`() {
        val policy = HybridHomeNetworkPolicy()

        assertTransition(
            policy.observe(authorized = false, nowMs = 10_000L),
            HybridHomeNetworkPhase.DepartureGrace,
            nextEvaluationAtMs = 12_000L,
        )
        assertTransition(
            policy.observe(authorized = false, nowMs = 11_999L),
            HybridHomeNetworkPhase.DepartureGrace,
            nextEvaluationAtMs = 12_000L,
        )
        assertTransition(
            policy.onDeadline(nowMs = 12_000L),
            HybridHomeNetworkPhase.AwayWatching,
            HybridHomeNetworkEdgeAction.DepartureConfirmed,
            nextEvaluationAtMs = 312_000L,
        )
        assertTransition(
            policy.observe(authorized = false, nowMs = 100_000L),
            HybridHomeNetworkPhase.AwayWatching,
            nextEvaluationAtMs = 312_000L,
        )
        assertTransition(
            policy.onDeadline(nowMs = 312_000L),
            HybridHomeNetworkPhase.Stopped,
            HybridHomeNetworkEdgeAction.StopService,
        )
        assertTransition(
            policy.onDeadline(nowMs = 400_000L),
            HybridHomeNetworkPhase.Stopped,
        )
        assertTransition(
            policy.observe(authorized = false, nowMs = 400_001L),
            HybridHomeNetworkPhase.Stopped,
        )
    }

    @Test
    fun `a stale timer cannot affect a later departure generation`() {
        val policy = HybridHomeNetworkPolicy()

        policy.observe(authorized = false, nowMs = 0L)
        policy.observe(authorized = true, nowMs = 1_000L)
        assertTransition(
            policy.observe(authorized = false, nowMs = 1_500L),
            HybridHomeNetworkPhase.DepartureGrace,
            nextEvaluationAtMs = 3_500L,
        )
        assertTransition(
            policy.onDeadline(nowMs = 2_000L),
            HybridHomeNetworkPhase.DepartureGrace,
            nextEvaluationAtMs = 3_500L,
        )
    }

    @Test
    fun `reset clears a terminal policy for a new service lease`() {
        val policy = HybridHomeNetworkPolicy(
            departureGraceMs = 1L,
            watcherTimeoutMs = 1L,
        )
        policy.observe(authorized = false, nowMs = 0L)
        policy.onDeadline(nowMs = 1L)
        policy.onDeadline(nowMs = 2L)

        policy.reset()

        assertTransition(
            policy.observe(authorized = true, nowMs = 3L),
            HybridHomeNetworkPhase.Home,
        )
    }

    @Test
    fun `restored watcher keeps its original stop deadline`() {
        val policy = HybridHomeNetworkPolicy()

        assertTransition(
            policy.restoreAwayWatching(stopDeadlineMs = 42_000L),
            HybridHomeNetworkPhase.AwayWatching,
            nextEvaluationAtMs = 42_000L,
        )
        assertTransition(
            policy.onDeadline(nowMs = 41_999L),
            HybridHomeNetworkPhase.AwayWatching,
            nextEvaluationAtMs = 42_000L,
        )
        assertTransition(
            policy.onDeadline(nowMs = 42_000L),
            HybridHomeNetworkPhase.Stopped,
            HybridHomeNetworkEdgeAction.StopService,
        )
    }

    private fun assertTransition(
        actual: HybridHomeNetworkTransition,
        phase: HybridHomeNetworkPhase,
        edgeAction: HybridHomeNetworkEdgeAction = HybridHomeNetworkEdgeAction.None,
        nextEvaluationAtMs: Long? = null,
    ) {
        assertEquals(phase, actual.phase)
        assertEquals(edgeAction, actual.edgeAction)
        if (nextEvaluationAtMs == null) {
            assertNull(actual.nextEvaluationAtMs)
        } else {
            assertEquals(nextEvaluationAtMs, actual.nextEvaluationAtMs)
        }
    }
}
