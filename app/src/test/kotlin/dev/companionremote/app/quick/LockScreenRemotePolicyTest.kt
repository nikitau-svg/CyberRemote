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
            QuickRemoteAction.SkipBack15,
            QuickRemoteAction.SkipForward15,
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
    fun `play pause is debounced while seek only throttles repeated direction`() {
        var now = 0L
        val limiter = LockedRemoteRateLimiter { now }

        assertTrue(limiter.tryAcquire(QuickRemoteAction.PlayPause))
        now = 200L
        assertFalse(limiter.tryAcquire(QuickRemoteAction.Play))
        now = 351L
        assertTrue(limiter.tryAcquire(QuickRemoteAction.Pause))
        now = 400L
        assertTrue(limiter.tryAcquire(QuickRemoteAction.SkipForward15))
        now = 450L
        assertFalse(limiter.tryAcquire(QuickRemoteAction.SkipForward15))
        now = 510L
        assertTrue(limiter.tryAcquire(QuickRemoteAction.SkipForward15))
        now = 520L
        assertTrue(limiter.tryAcquire(QuickRemoteAction.SkipBack15))
    }

    @Test
    fun `locked input caps an action burst`() {
        var now = 0L
        val limiter = LockedRemoteRateLimiter { now }
        val actions = listOf(
            QuickRemoteAction.Up,
            QuickRemoteAction.Down,
            QuickRemoteAction.Left,
            QuickRemoteAction.Right,
            QuickRemoteAction.Select,
            QuickRemoteAction.Back,
            QuickRemoteAction.VolumeUp,
            QuickRemoteAction.VolumeDown,
        )

        actions.forEachIndexed { index, action ->
            now = index * 115L
            assertTrue(limiter.tryAcquire(action), action.name)
        }
        now = 900L
        assertFalse(limiter.tryAcquire(QuickRemoteAction.SkipBack15))
        now = 1_000L
        assertTrue(limiter.tryAcquire(QuickRemoteAction.SkipBack15))
    }
}
