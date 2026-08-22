package dev.companionremote.app.quick

internal enum class ExplicitNowPlayingRefreshDecision {
    Scheduled,
    NotHome,
    NotConnected,
    BoundedPending,
    Cooldown,
}

/** Prevents duplicate Activity/service starts from turning a user refresh into polling. */
internal class ExplicitNowPlayingRefreshPolicy(
    private val cooldownMs: Long = 10_000L,
) {
    private var lastScheduledAtMs: Long? = null

    init {
        require(cooldownMs >= 0L)
    }

    fun evaluate(
        nowMs: Long,
        homeAuthorized: Boolean,
        connected: Boolean,
        boundedRefreshPending: Boolean,
    ): ExplicitNowPlayingRefreshDecision {
        if (!homeAuthorized) return ExplicitNowPlayingRefreshDecision.NotHome
        if (!connected) return ExplicitNowPlayingRefreshDecision.NotConnected
        if (boundedRefreshPending) return ExplicitNowPlayingRefreshDecision.BoundedPending

        val lastScheduled = lastScheduledAtMs
        if (lastScheduled != null && nowMs - lastScheduled < cooldownMs) {
            return ExplicitNowPlayingRefreshDecision.Cooldown
        }
        lastScheduledAtMs = nowMs
        return ExplicitNowPlayingRefreshDecision.Scheduled
    }
}
