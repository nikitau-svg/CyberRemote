package dev.companionremote.app.nowplaying

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NowPlayingTest {

    @Test
    fun `position advances locally and is capped by duration`() {
        val anchor = PositionAnchor(
            positionMs = 8_000,
            capturedAtElapsedMs = 1_000,
            playbackRate = 1.0,
        )

        assertEquals(10_500, anchor.positionAt(nowElapsedMs = 3_500, durationMs = 20_000))
        assertEquals(20_000, anchor.positionAt(nowElapsedMs = 30_000, durationMs = 20_000))
    }

    @Test
    fun `paused anchor does not advance`() {
        val anchor = PositionAnchor(
            positionMs = 8_000,
            capturedAtElapsedMs = 1_000,
            playbackRate = 0.0,
        )

        assertEquals(8_000, anchor.positionAt(nowElapsedMs = 30_000, durationMs = 20_000))
    }

    @Test
    fun `live state selects explicit commands`() {
        val playing = liveSnapshot(PlaybackStatus.Playing)
        val paused = liveSnapshot(PlaybackStatus.Paused)

        assertEquals(PlaybackCommand.Pause, commandFor(playing))
        assertEquals(PlaybackCommand.Play, commandFor(paused))
    }

    @Test
    fun `stale or unknown state uses idempotent play instead of toggle`() {
        val stale = liveSnapshot(PlaybackStatus.Playing).copy(freshness = SnapshotFreshness.Stale)
        val unknown = liveSnapshot(PlaybackStatus.Unknown)

        assertEquals(PlaybackCommand.Play, commandFor(stale))
        assertEquals(PlaybackCommand.Play, commandFor(unknown))
    }

    @Test
    fun `pending command requires a newer authoritative acknowledgement`() {
        val pending = PendingPlaybackCommand(PlaybackCommand.Pause, issuedAtElapsedMs = 2_000)

        assertFalse(pending.isAcknowledgedBy(liveSnapshot(PlaybackStatus.Paused, observedAt = 1_999)))
        assertTrue(pending.isAcknowledgedBy(liveSnapshot(PlaybackStatus.Paused, observedAt = 2_001)))
        assertFalse(pending.isExpired(nowElapsedMs = 4_999))
        assertTrue(pending.isExpired(nowElapsedMs = 5_000))
    }

    private fun liveSnapshot(
        status: PlaybackStatus,
        observedAt: Long = 10_000,
    ) = NowPlayingSnapshot(
        status = status,
        freshness = SnapshotFreshness.Live,
        source = NowPlayingSource.Mrp,
        observedAtElapsedMs = observedAt,
    )
}
