package dev.companionremote.protocol.client

import dev.companionremote.protocol.plist.BinaryPlist
import dev.companionremote.protocol.plist.PlistUid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompanionNowPlayingTest {

    @Test
    fun `parses playback state from tvOS 17 archive`() {
        val event = mapOf<Any?, Any?>("NowPlayingInfoKey" to archive(mapOf("playbackRate" to 1.0)))

        val parsed = CompanionNowPlayingParser.parse(event, capturedAtNanos = 42)

        assertEquals(CompanionPlaybackState.Playing, parsed?.playbackState)
        assertEquals(1.0, parsed?.playbackRate)
        assertEquals(42L, parsed?.capturedAtNanos)
    }

    @Test
    fun `parses timed metadata and artwork from tvOS 18 archive`() {
        val event = mapOf<Any?, Any?>(
            "NowPlayingInfoKey" to archive(
                linkedMapOf(
                    "playbackRate" to 0.0,
                    "title" to "Example Show",
                    "episodeTitle" to "Pilot",
                    "episodeNumber" to 1L,
                    "duration" to 120.5,
                    "elapsedTime" to 12.25,
                    "artist" to null,
                    "imageURLTemplate" to "http://apple-tv.local/image/{w}x{h}",
                ),
            ),
        )

        val parsed = CompanionNowPlayingParser.parse(event, capturedAtNanos = 100)!!

        assertEquals(CompanionPlaybackState.Paused, parsed.playbackState)
        assertEquals("Pilot", parsed.title)
        assertEquals("Example Show", parsed.seriesName)
        assertEquals(1, parsed.episodeNumber)
        assertEquals(120_500L, parsed.durationMs)
        assertEquals(12_250L, parsed.positionMs)
        assertNull(parsed.artist)
        assertTrue(parsed.artworkId?.isNotBlank() == true)
    }

    @Test
    fun `ignores event without archive`() {
        assertNull(CompanionNowPlayingParser.parse(emptyMap()))
    }

    @Test
    fun `metadata-only update retains explicit playing state for the same item`() {
        val playing = parse(
            linkedMapOf(
                "playbackRate" to 1.0,
                "contentIdentifier" to "episode-1",
            ),
            capturedAtNanos = 10,
        )
        val metadata = parse(
            linkedMapOf(
                "contentIdentifier" to "episode-1",
                "episodeTitle" to "Pilot",
                "duration" to 120.0,
                "imageURLTemplate" to "http://apple-tv.local/image/{w}x{h}",
            ),
            capturedAtNanos = 20,
        )

        val merged = metadata.withMissingFieldsFrom(playing)

        assertEquals(CompanionPlaybackState.Playing, merged.playbackState)
        assertEquals(1.0, merged.playbackRate)
        assertEquals("Pilot", merged.title)
        assertEquals(120_000L, merged.durationMs)
        assertTrue(merged.artworkId?.isNotBlank() == true)
        assertEquals(20L, merged.capturedAtNanos)
    }

    @Test
    fun `explicit paused update replaces previous playing state`() {
        val playing = parse(mapOf("playbackRate" to 1.0), capturedAtNanos = 10)
        val paused = parse(mapOf("playbackRate" to 0.0), capturedAtNanos = 20)

        val merged = paused.withMissingFieldsFrom(playing)

        assertEquals(CompanionPlaybackState.Paused, merged.playbackState)
        assertEquals(0.0, merged.playbackRate)
    }

    @Test
    fun `new content id does not inherit stale playback or metadata`() {
        val previous = parse(
            linkedMapOf(
                "playbackRate" to 1.0,
                "contentIdentifier" to "episode-1",
                "episodeTitle" to "Pilot",
                "duration" to 120.0,
                "imageURLTemplate" to "http://apple-tv.local/image/{w}x{h}",
            ),
            capturedAtNanos = 10,
        )
        val next = parse(
            linkedMapOf(
                "contentIdentifier" to "episode-2",
                "episodeTitle" to "Finale",
            ),
            capturedAtNanos = 20,
        )

        val merged = next.withMissingFieldsFrom(previous)

        assertEquals(CompanionPlaybackState.Unknown, merged.playbackState)
        assertNull(merged.playbackRate)
        assertEquals("Finale", merged.title)
        assertEquals("episode-2", merged.contentId)
    }

    @Test
    fun `explicit-null envelope after transport update retains last confirmed state`() {
        val playing = parse(
            linkedMapOf(
                "playbackRate" to 1.0,
                "contentIdentifier" to "episode-1",
                "episodeTitle" to "Pilot",
                "duration" to 120.0,
                "imageURLTemplate" to "http://apple-tv.local/image/{w}x{h}",
            ),
            capturedAtNanos = 10,
        )
        val intermediate = parse(explicitNullEnvelope(), capturedAtNanos = 20)

        val merged = intermediate.withMissingFieldsFrom(playing)

        assertEquals(CompanionPlaybackState.Playing, merged.playbackState)
        assertEquals(1.0, merged.playbackRate)
        assertEquals("Pilot", merged.title)
        assertEquals("episode-1", merged.contentId)
        assertEquals(120_000L, merged.durationMs)
        assertTrue(merged.artworkId?.isNotBlank() == true)
    }

    @Test
    fun `explicit-null envelope after pause retains paused state`() {
        val paused = parse(
            linkedMapOf(
                "playbackRate" to 0.0,
                "contentIdentifier" to "episode-1",
                "episodeTitle" to "Pilot",
            ),
            capturedAtNanos = 10,
        )
        val intermediate = parse(explicitNullEnvelope(), capturedAtNanos = 20)

        val merged = intermediate.withMissingFieldsFrom(paused)

        assertEquals(CompanionPlaybackState.Paused, merged.playbackState)
        assertEquals(0.0, merged.playbackRate)
        assertEquals("Pilot", merged.title)
        assertEquals("episode-1", merged.contentId)
    }

    @Test
    fun `runtime transport sequence never falls back to unknown after first confirmed state`() {
        val updates = listOf(
            parse(explicitNullEnvelope(), capturedAtNanos = 10),
            parse(mapOf("playbackRate" to 1.0), capturedAtNanos = 20),
            parse(
                linkedMapOf(
                    "episodeTitle" to "Pilot",
                ),
                capturedAtNanos = 30,
            ),
            parse(explicitNullEnvelope(), capturedAtNanos = 40),
            parse(mapOf("playbackRate" to 0.0), capturedAtNanos = 50),
            parse(explicitNullEnvelope(), capturedAtNanos = 60),
        )
        var merged: CompanionNowPlayingInfo? = null

        val observed = updates.map { update ->
            merged = update.withMissingFieldsFrom(merged)
            requireNotNull(merged).playbackState
        }

        assertEquals(
            listOf(
                CompanionPlaybackState.Unknown,
                CompanionPlaybackState.Playing,
                CompanionPlaybackState.Playing,
                CompanionPlaybackState.Playing,
                CompanionPlaybackState.Paused,
                CompanionPlaybackState.Paused,
            ),
            observed,
        )
    }

    private fun explicitNullEnvelope(): Map<String, Any?> = linkedMapOf(
        "playbackRate" to null,
        "playbackState" to null,
        "metadata" to null,
        "imageData" to null,
        "identifier" to null,
        "playerIdentifier" to null,
    )

    private fun parse(properties: Map<String, Any?>, capturedAtNanos: Long): CompanionNowPlayingInfo =
        requireNotNull(
            CompanionNowPlayingParser.parse(
                mapOf("NowPlayingInfoKey" to archive(properties)),
                capturedAtNanos = capturedAtNanos,
            ),
        )

    private fun archive(properties: Map<String, Any?>): ByteArray {
        val root = linkedMapOf<String, Any?>("\$class" to PlistUid(2))
        val objects = mutableListOf<Any?>(
            "\$null",
            root,
            linkedMapOf(
                "\$classname" to "TVRCNowPlayingInfo",
                "\$classes" to listOf("TVRCNowPlayingInfo", "NSObject"),
            ),
        )
        properties.forEach { (key, value) ->
            root[key] = if (value == null) {
                PlistUid(0)
            } else {
                objects.add(value)
                PlistUid(objects.lastIndex.toLong())
            }
        }
        return BinaryPlist.encode(
            linkedMapOf(
                "\$version" to 100000L,
                "\$archiver" to "NSKeyedArchiver",
                "\$top" to linkedMapOf("root" to PlistUid(1)),
                "\$objects" to objects,
            ),
        )
    }
}
