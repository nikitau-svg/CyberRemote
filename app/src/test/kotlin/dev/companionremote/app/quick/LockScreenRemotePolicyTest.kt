package dev.companionremote.app.quick

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LockScreenRemotePolicyTest {

    @Test
    fun `all actions work after unlock`() {
        QuickRemoteAction.entries.forEach { action ->
            assertTrue(LockScreenRemotePolicy.canExecute(action, false, false), action.name)
        }
    }

    @Test
    fun `lock-screen opt-in still protects selection home and power`() {
        listOf(
            QuickRemoteAction.Select,
            QuickRemoteAction.Home,
            QuickRemoteAction.HomeHold,
            QuickRemoteAction.Back,
            QuickRemoteAction.Wake,
            QuickRemoteAction.Sleep,
        ).forEach { action ->
            assertFalse(LockScreenRemotePolicy.canExecute(action, true, true), action.name)
            assertTrue(LockScreenRemotePolicy.requiresUnlock(action), action.name)
        }
    }

    @Test
    fun `safe controls require explicit opt-in while locked`() {
        val safe = listOf(
            QuickRemoteAction.Up,
            QuickRemoteAction.Down,
            QuickRemoteAction.Left,
            QuickRemoteAction.Right,
            QuickRemoteAction.Play,
            QuickRemoteAction.Pause,
            QuickRemoteAction.PlayPause,
            QuickRemoteAction.VolumeUp,
            QuickRemoteAction.VolumeDown,
        )
        safe.forEach { action ->
            assertFalse(LockScreenRemotePolicy.canExecute(action, true, false), action.name)
            assertTrue(LockScreenRemotePolicy.canExecute(action, true, true), action.name)
            assertFalse(LockScreenRemotePolicy.requiresUnlock(action), action.name)
        }
    }

    @Test
    fun `locked input is rate limited and play pause is debounced`() {
        var now = 0L
        val limiter = LockedRemoteRateLimiter { now }

        assertTrue(limiter.tryAcquire(QuickRemoteAction.PlayPause))
        now = 200L
        assertFalse(limiter.tryAcquire(QuickRemoteAction.Play))
        now = 351L
        assertTrue(limiter.tryAcquire(QuickRemoteAction.Pause))

        repeat(6) { index ->
            now = 400L + index * 115L
            assertTrue(limiter.tryAcquire(QuickRemoteAction.entries[index]))
        }
        now = 990L
        assertFalse(limiter.tryAcquire(QuickRemoteAction.VolumeUp))
        now = 1_001L
        assertTrue(limiter.tryAcquire(QuickRemoteAction.VolumeUp))
    }
}
