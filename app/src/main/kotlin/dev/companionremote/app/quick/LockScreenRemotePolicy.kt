package dev.companionremote.app.quick

/**
 * The only commands allowed without dismissing keyguard. Selection, Home and
 * power remain protected because they can launch apps, reveal accounts or
 * change the TV's power state.
 */
object LockScreenRemotePolicy {
    private val safeActions = setOf(
        QuickRemoteAction.Up,
        QuickRemoteAction.Down,
        QuickRemoteAction.Left,
        QuickRemoteAction.Right,
        QuickRemoteAction.PlayPause,
        QuickRemoteAction.VolumeUp,
        QuickRemoteAction.VolumeDown,
    )

    fun canExecute(
        action: QuickRemoteAction,
        keyguardLocked: Boolean,
        lockScreenControlsEnabled: Boolean,
    ): Boolean = !keyguardLocked || (lockScreenControlsEnabled && action in safeActions)

    fun requiresUnlock(action: QuickRemoteAction): Boolean = action !in safeActions
}

/** Small in-memory flood guard for commands accepted while keyguard is showing. */
class LockedRemoteRateLimiter(
    private val nowMs: () -> Long,
) {
    private val recent = ArrayDeque<Long>()
    private val lastByAction = mutableMapOf<QuickRemoteAction, Long>()

    @Synchronized
    fun tryAcquire(action: QuickRemoteAction): Boolean {
        val now = nowMs()
        while (recent.isNotEmpty() && now - recent.first() >= WINDOW_MS) {
            recent.removeFirst()
        }
        if (recent.size >= MAX_ACTIONS_PER_WINDOW) return false

        val last = lastByAction[action]
        val minimumGap = if (action == QuickRemoteAction.PlayPause) {
            PLAY_PAUSE_GAP_MS
        } else {
            REPEATED_ACTION_GAP_MS
        }
        if (last != null && now >= last && now - last < minimumGap) return false

        recent.addLast(now)
        lastByAction[action] = now
        return true
    }

    companion object {
        private const val WINDOW_MS = 1_000L
        private const val MAX_ACTIONS_PER_WINDOW = 8
        private const val PLAY_PAUSE_GAP_MS = 350L
        private const val REPEATED_ACTION_GAP_MS = 110L
    }
}
