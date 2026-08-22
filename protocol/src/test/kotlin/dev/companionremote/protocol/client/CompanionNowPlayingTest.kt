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
