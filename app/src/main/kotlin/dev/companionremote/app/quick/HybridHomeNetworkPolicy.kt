package dev.companionremote.app.quick

internal const val HOME_NETWORK_DEPARTURE_GRACE_MS = 2_000L
internal const val HOME_NETWORK_WATCHER_TIMEOUT_MS = 5 * 60_000L

/**
 * Lifecycle phases for the hybrid home-network policy.
 *
 * Time values passed to [HybridHomeNetworkPolicy] must use the same monotonic clock. The policy
 * owns no timer itself; [HybridHomeNetworkTransition.nextEvaluationAtMs] tells the caller when to
 * invoke [HybridHomeNetworkPolicy.onDeadline].
 */
internal enum class HybridHomeNetworkPhase {
    Unknown,
    Home,
    DepartureGrace,
    AwayWatching,
    Stopped,
}

/** One-shot effects emitted only when a real lifecycle edge is crossed. */
internal enum class HybridHomeNetworkEdgeAction {
    None,
    DepartureConfirmed,
    ReturnedHome,
    StopService,
}

internal data class HybridHomeNetworkTransition(
    val phase: HybridHomeNetworkPhase,
    val edgeAction: HybridHomeNetworkEdgeAction,
    val nextEvaluationAtMs: Long?,
)

/**
 * Pure state machine for the balanced out-of-home lifecycle.
 *
 * A missing home network first enters a short grace period. If absence is still confirmed when
 * that deadline expires, the media UI can be replaced by a quiet watcher. The watcher is allowed
 * to live for a bounded period before the service is stopped completely. Repeated network
 * callbacks preserve the original deadlines and never repeat edge actions.
 */
internal class HybridHomeNetworkPolicy(
    private val departureGraceMs: Long = HOME_NETWORK_DEPARTURE_GRACE_MS,
    private val watcherTimeoutMs: Long = HOME_NETWORK_WATCHER_TIMEOUT_MS,
) {
    init {
        require(departureGraceMs > 0L) { "departureGraceMs must be positive" }
        require(watcherTimeoutMs > 0L) { "watcherTimeoutMs must be positive" }
    }

    private var phase = HybridHomeNetworkPhase.Unknown
    private var deadlineMs: Long? = null

    fun observe(authorized: Boolean, nowMs: Long): HybridHomeNetworkTransition {
        if (phase == HybridHomeNetworkPhase.Stopped) return currentTransition()

        if (authorized) {
            val action = if (
                phase == HybridHomeNetworkPhase.DepartureGrace ||
                phase == HybridHomeNetworkPhase.AwayWatching
            ) {
                HybridHomeNetworkEdgeAction.ReturnedHome
            } else {
                HybridHomeNetworkEdgeAction.None
            }
            phase = HybridHomeNetworkPhase.Home
            deadlineMs = null
            return currentTransition(action)
        }

        if (phase == HybridHomeNetworkPhase.Unknown || phase == HybridHomeNetworkPhase.Home) {
            phase = HybridHomeNetworkPhase.DepartureGrace
            deadlineMs = deadlineAfter(nowMs, departureGraceMs)
        }
        return currentTransition()
    }

    fun onDeadline(nowMs: Long): HybridHomeNetworkTransition {
        val deadline = deadlineMs ?: return currentTransition()
        if (nowMs < deadline) return currentTransition()

        return when (phase) {
            HybridHomeNetworkPhase.DepartureGrace -> {
                phase = HybridHomeNetworkPhase.AwayWatching
                deadlineMs = deadlineAfter(deadline, watcherTimeoutMs)
                currentTransition(HybridHomeNetworkEdgeAction.DepartureConfirmed)
            }
            HybridHomeNetworkPhase.AwayWatching -> {
                phase = HybridHomeNetworkPhase.Stopped
                deadlineMs = null
                currentTransition(HybridHomeNetworkEdgeAction.StopService)
            }
            else -> currentTransition()
        }
    }

    /** Restore the bounded watcher after a START_STICKY process restart. */
    fun restoreAwayWatching(stopDeadlineMs: Long): HybridHomeNetworkTransition {
        phase = HybridHomeNetworkPhase.AwayWatching
        deadlineMs = stopDeadlineMs
        return currentTransition()
    }

    fun reset() {
        phase = HybridHomeNetworkPhase.Unknown
        deadlineMs = null
    }

    private fun currentTransition(
        action: HybridHomeNetworkEdgeAction = HybridHomeNetworkEdgeAction.None,
    ) = HybridHomeNetworkTransition(
        phase = phase,
        edgeAction = action,
        nextEvaluationAtMs = deadlineMs,
    )

    private fun deadlineAfter(startMs: Long, delayMs: Long): Long =
        if (startMs > Long.MAX_VALUE - delayMs) Long.MAX_VALUE else startMs + delayMs
}
