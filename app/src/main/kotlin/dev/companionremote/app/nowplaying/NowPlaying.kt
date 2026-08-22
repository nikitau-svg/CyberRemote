package dev.companionremote.app.nowplaying

import kotlin.math.roundToLong

/** Playback state reported by the Apple TV, not inferred from button presses. */
enum class PlaybackStatus {
    Unknown,
    Idle,
    Buffering,
    Playing,
    Paused,
    Stopped,
}

/** Whether a snapshot can safely be used to choose an explicit transport command. */
enum class SnapshotFreshness {
    Disconnected,
    Stale,
    Live,
}

enum class NowPlayingSource {
    None,
    Companion,
    Mrp,
}

/**
 * A progress anchor from MRP. The UI advances it locally between server pushes,
 * avoiding a network request every second.
 */
data class PositionAnchor(
    val positionMs: Long,
    val capturedAtElapsedMs: Long,
    val playbackRate: Double,
) {
    fun positionAt(nowElapsedMs: Long, durationMs: Long?): Long {
        val elapsedMs = (nowElapsedMs - capturedAtElapsedMs).coerceAtLeast(0L)
        val advancedMs = (elapsedMs * playbackRate).roundToLong()
        val upperBound = durationMs?.coerceAtLeast(0L) ?: Long.MAX_VALUE
        return (positionMs + advancedMs).coerceIn(0L, upperBound)
    }
}

/** Artwork delivered by the authenticated Apple TV session. */
class ArtworkPayload(
    val id: String,
    val urlTemplate: String? = null,
    val data: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is ArtworkPayload &&
            id == other.id &&
            urlTemplate == other.urlTemplate &&
            data.contentEqualsNullable(other.data)

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (urlTemplate?.hashCode() ?: 0)
        result = 31 * result + (data?.contentHashCode() ?: 0)
        return result
    }
}

/** Protocol-neutral state consumed by Android MediaSession and the remote UI. */
data class NowPlayingSnapshot(
    val status: PlaybackStatus = PlaybackStatus.Unknown,
    val freshness: SnapshotFreshness = SnapshotFreshness.Disconnected,
    val source: NowPlayingSource = NowPlayingSource.None,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val seriesName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val appName: String? = null,
    val durationMs: Long? = null,
    val position: PositionAnchor? = null,
    val contentId: String? = null,
    val artworkId: String? = null,
    val artwork: ArtworkPayload? = null,
    val observedAtElapsedMs: Long = 0L,
) {
    val isAuthoritative: Boolean
        get() = source != NowPlayingSource.None && freshness == SnapshotFreshness.Live

    fun positionAt(nowElapsedMs: Long): Long? = position?.positionAt(nowElapsedMs, durationMs)

    companion object {
        val Disconnected = NowPlayingSnapshot()
    }
}

enum class PlaybackCommand {
    Play,
    Pause,
}

/**
 * Never guesses with a toggle. If state is unknown or stale, explicit Play is
 * idempotent for content that is already playing and cannot pause it by mistake.
 */
fun commandFor(snapshot: NowPlayingSnapshot): PlaybackCommand {
    if (!snapshot.isAuthoritative) return PlaybackCommand.Play
    return when (snapshot.status) {
        PlaybackStatus.Playing,
        PlaybackStatus.Buffering -> PlaybackCommand.Pause

        PlaybackStatus.Paused,
        PlaybackStatus.Idle,
        PlaybackStatus.Stopped -> PlaybackCommand.Play

        PlaybackStatus.Unknown -> PlaybackCommand.Play
    }
}

data class PendingPlaybackCommand(
    val command: PlaybackCommand,
    val issuedAtElapsedMs: Long,
    val timeoutMs: Long = DEFAULT_COMMAND_ACK_TIMEOUT_MS,
) {
    fun isExpired(nowElapsedMs: Long): Boolean =
        nowElapsedMs - issuedAtElapsedMs >= timeoutMs

    fun isAcknowledgedBy(snapshot: NowPlayingSnapshot): Boolean {
        if (!snapshot.isAuthoritative || snapshot.observedAtElapsedMs < issuedAtElapsedMs) {
            return false
        }
        return when (command) {
            PlaybackCommand.Play -> snapshot.status == PlaybackStatus.Playing
            PlaybackCommand.Pause -> snapshot.status == PlaybackStatus.Paused
        }
    }
}

private const val DEFAULT_COMMAND_ACK_TIMEOUT_MS = 3_000L

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> contentEquals(other)
}
