package dev.companionremote.protocol.client

import dev.companionremote.protocol.plist.KeyedArchiver
import java.security.MessageDigest

enum class CompanionPlaybackState {
    Unknown,
    Playing,
    Paused,
}

/**
 * Best-effort decoding of tvOS Companion `NowPlayingInfo` events. tvOS 17
 * commonly sends only playbackRate; tvOS 18+ can additionally send timed
 * metadata and an image template after `FetchCurrentNowPlayingInfoEvent`.
 */
data class CompanionNowPlayingInfo(
    val playbackState: CompanionPlaybackState,
    val playbackRate: Double?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val seriesName: String?,
    val episodeNumber: Int?,
    val durationMs: Long?,
    val positionMs: Long?,
    val contentId: String?,
    val artworkUrlTemplate: String?,
    val artworkData: ByteArray?,
    val artworkId: String?,
    val capturedAtNanos: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is CompanionNowPlayingInfo &&
            playbackState == other.playbackState &&
            playbackRate == other.playbackRate &&
            title == other.title &&
            artist == other.artist &&
            album == other.album &&
            seriesName == other.seriesName &&
            episodeNumber == other.episodeNumber &&
            durationMs == other.durationMs &&
            positionMs == other.positionMs &&
            contentId == other.contentId &&
            artworkUrlTemplate == other.artworkUrlTemplate &&
            artworkData.contentEqualsNullable(other.artworkData) &&
            artworkId == other.artworkId &&
            capturedAtNanos == other.capturedAtNanos

    override fun hashCode(): Int {
        var result = playbackState.hashCode()
        result = 31 * result + (playbackRate?.hashCode() ?: 0)
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (seriesName?.hashCode() ?: 0)
        result = 31 * result + (episodeNumber ?: 0)
        result = 31 * result + (durationMs?.hashCode() ?: 0)
        result = 31 * result + (positionMs?.hashCode() ?: 0)
        result = 31 * result + (contentId?.hashCode() ?: 0)
        result = 31 * result + (artworkUrlTemplate?.hashCode() ?: 0)
        result = 31 * result + (artworkData?.contentHashCode() ?: 0)
        result = 31 * result + (artworkId?.hashCode() ?: 0)
        result = 31 * result + capturedAtNanos.hashCode()
        return result
    }
}

/**
 * tvOS sends NowPlayingInfo as a sequence of partial payloads. Metadata-only
 * and explicit-null transition envelopes have no playbackRate, so the parser
 * deliberately marks their state as Unknown. For the same media item, retain
 * the last explicit transport state while accepting newly supplied metadata.
 * A new content id remains a hard boundary so stale state or artwork cannot
 * leak into the next item.
 */
internal fun CompanionNowPlayingInfo.withMissingFieldsFrom(
    previous: CompanionNowPlayingInfo?,
): CompanionNowPlayingInfo {
    if (previous == null || (contentId != null && contentId != previous.contentId)) return this
    return copy(
        playbackState = if (playbackState == CompanionPlaybackState.Unknown) {
            previous.playbackState
        } else {
            playbackState
        },
        playbackRate = playbackRate ?: previous.playbackRate,
        title = title ?: previous.title,
        artist = artist ?: previous.artist,
        album = album ?: previous.album,
        seriesName = seriesName ?: previous.seriesName,
        episodeNumber = episodeNumber ?: previous.episodeNumber,
        durationMs = durationMs ?: previous.durationMs,
        positionMs = positionMs ?: previous.positionMs,
        contentId = contentId ?: previous.contentId,
        artworkUrlTemplate = artworkUrlTemplate ?: previous.artworkUrlTemplate,
        artworkData = artworkData ?: previous.artworkData,
        artworkId = artworkId ?: previous.artworkId,
    )
}

internal object CompanionNowPlayingParser {

    fun parse(
        content: Map<Any?, Any?>,
        capturedAtNanos: Long = System.nanoTime(),
    ): CompanionNowPlayingInfo? {
        val archive = content[NOW_PLAYING_KEY] as? ByteArray ?: return null
        val root = KeyedArchiver.unarchiveRoot(archive) ?: return null

        val playbackRate = root.number("playbackRate")?.toDouble()
        val episodeTitle = root.text("episodeTitle")
        val genericTitle = root.text("title", "name")
        val artworkUrl = root.text("imageURLTemplate", "artworkURL", "artworkUrl")
        val artworkData = root.bytes("imageData", "artworkData", "artwork")
        val contentId = root.text("contentIdentifier", "contentID", "showID", "mediaID")
        val artworkId = artworkUrl?.let(::sha256) ?: artworkData?.let(::sha256)

        return CompanionNowPlayingInfo(
            playbackState = when {
                playbackRate == null -> CompanionPlaybackState.Unknown
                playbackRate > 0.0 -> CompanionPlaybackState.Playing
                else -> CompanionPlaybackState.Paused
            },
            playbackRate = playbackRate,
            title = episodeTitle ?: genericTitle,
            artist = root.text("artist", "composer"),
            album = root.text("album"),
            seriesName = root.text("seriesName", "showTitle")
                ?: genericTitle.takeIf { episodeTitle != null },
            episodeNumber = root.number("episodeNumber")?.toInt(),
            durationMs = root.secondsAsMillis("duration", "totalTime"),
            positionMs = root.secondsAsMillis("elapsedTime", "playbackPosition", "position"),
            contentId = contentId,
            artworkUrlTemplate = artworkUrl,
            artworkData = artworkData,
            artworkId = artworkId,
            capturedAtNanos = capturedAtNanos,
        )
    }

    private fun Any?.text(vararg names: String): String? =
        deepValue(names.toSet()) as? String

    private fun Any?.number(vararg names: String): Number? =
        deepValue(names.toSet()) as? Number

    private fun Any?.bytes(vararg names: String): ByteArray? =
        deepValue(names.toSet()) as? ByteArray

    private fun Any?.secondsAsMillis(vararg names: String): Long? =
        number(*names)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }?.let { (it * 1_000).toLong() }

    private fun Any?.deepValue(names: Set<String>): Any? {
        val wanted = names.map { it.lowercase() }.toSet()
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

        fun visit(node: Any?): Any? {
            when (node) {
                is Map<*, *> -> {
                    if (!visited.add(node)) return null
                    for ((key, value) in node) {
                        val normalizedKey = (key as? String)?.lowercase()
                        if (normalizedKey != null && normalizedKey in wanted && value != null) {
                            return value
                        }
                    }
                    for (value in node.values) visit(value)?.let { return it }
                }
                is List<*> -> {
                    if (!visited.add(node)) return null
                    for (value in node) visit(value)?.let { return it }
                }
            }
            return null
        }

        return visit(this)
    }

    private fun sha256(value: String): String = sha256(value.toByteArray())

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private const val NOW_PLAYING_KEY = "NowPlayingInfoKey"
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> contentEquals(other)
}
