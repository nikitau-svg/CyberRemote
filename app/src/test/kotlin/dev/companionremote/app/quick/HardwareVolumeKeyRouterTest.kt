package dev.companionremote.app.quick

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HardwareVolumeKeyRouterTest {

    @Test
    fun `eligible first down is consumed and dispatched once`() {
        val router = HardwareVolumeKeyRouter()

        val down = router.onDown(HardwareVolumeKey.Up, repeatCount = 0, eventTimeMs = 1_000L, eligible = true)
        val up = router.onUp(HardwareVolumeKey.Up)

        assertTrue(down.consumed)
        assertTrue(down.dispatch)
        assertTrue(up.consumed)
        assertFalse(up.dispatch)
    }

    @Test
    fun `ineligible first down leaves the whole gesture to the system`() {
        val router = HardwareVolumeKeyRouter()

        val down = router.onDown(HardwareVolumeKey.Down, repeatCount = 0, eventTimeMs = 1_000L, eligible = false)
        val repeat = router.onDown(HardwareVolumeKey.Down, repeatCount = 1, eventTimeMs = 1_600L, eligible = true)
        val up = router.onUp(HardwareVolumeKey.Down)

        assertFalse(down.consumed)
        assertFalse(repeat.consumed)
        assertFalse(up.consumed)
    }

    @Test
    fun `captured repeats are consumed and throttled`() {
        val router = HardwareVolumeKeyRouter(repeatIntervalMs = 150L)

        assertTrue(router.onDown(HardwareVolumeKey.Up, 0, 1_000L, true).dispatch)
        val earlyRepeat = router.onDown(HardwareVolumeKey.Up, 1, 1_149L, true)
        val allowedRepeat = router.onDown(HardwareVolumeKey.Up, 2, 1_150L, true)

        assertTrue(earlyRepeat.consumed)
        assertFalse(earlyRepeat.dispatch)
        assertTrue(allowedRepeat.consumed)
        assertTrue(allowedRepeat.dispatch)
    }

    @Test
    fun `captured gesture stays consumed but stops dispatching when eligibility is lost`() {
        val router = HardwareVolumeKeyRouter()

        router.onDown(HardwareVolumeKey.Down, 0, 1_000L, true)
        val repeat = router.onDown(HardwareVolumeKey.Down, 1, 2_000L, false)
        val up = router.onUp(HardwareVolumeKey.Down)

        assertTrue(repeat.consumed)
        assertFalse(repeat.dispatch)
        assertTrue(up.consumed)
    }

    @Test
    fun `reset returns captured keys to normal system handling`() {
        val router = HardwareVolumeKeyRouter()

        router.onDown(HardwareVolumeKey.Up, 0, 1_000L, true)
        router.reset()

        assertFalse(router.onUp(HardwareVolumeKey.Up).consumed)
        assertFalse(router.onDown(HardwareVolumeKey.Up, 1, 2_000L, true).consumed)
    }

    @Test
    fun `up and down gestures are tracked independently`() {
        val router = HardwareVolumeKeyRouter()

        router.onDown(HardwareVolumeKey.Up, 0, 1_000L, true)
        router.onDown(HardwareVolumeKey.Down, 0, 1_010L, true)

        assertTrue(router.onUp(HardwareVolumeKey.Up).consumed)
        assertTrue(router.onDown(HardwareVolumeKey.Down, 1, 1_200L, true).dispatch)
        assertTrue(router.onUp(HardwareVolumeKey.Down).consumed)
    }
}
