package dev.companionremote.protocol.mrp

import com.google.protobuf.ByteString
import dev.companionremote.protocol.mrp.proto.Command
import dev.companionremote.protocol.mrp.proto.CommandInfo
import dev.companionremote.protocol.mrp.proto.ContentItem
import dev.companionremote.protocol.mrp.proto.ContentItemMetadata
import dev.companionremote.protocol.mrp.proto.NowPlayingClient
import dev.companionremote.protocol.mrp.proto.NowPlayingPlayer
import dev.companionremote.protocol.mrp.proto.PlaybackQueue
import dev.companionremote.protocol.mrp.proto.PlaybackState
import dev.companionremote.protocol.mrp.proto.PlayerPath
import dev.companionremote.protocol.mrp.proto.ProtocolMessage
import dev.companionremote.protocol.mrp.proto.SetNowPlayingClientMessage
import dev.companionremote.protocol.mrp.proto.SetNowPlayingClientMessageOuterClass
import dev.companionremote.protocol.mrp.proto.SetNowPlayingPlayerMessage
import dev.companionremote.protocol.mrp.proto.SetNowPlayingPlayerMessageOuterClass
import dev.companionremote.protocol.mrp.proto.SetStateMessage
import dev.companionremote.protocol.mrp.proto.SetStateMessageOuterClass
import dev.companionremote.protocol.mrp.proto.SupportedCommands
import dev.companionremote.protocol.mrp.proto.UpdateContentItemMessage
import dev.companionremote.protocol.mrp.proto.UpdateContentItemMessageOuterClass
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NowPlayingStoreTest {
    @Test
    fun `push state exposes metadata artwork and anchored position`() {
        val clock = FakeClock(
            wall = 1_700_000_000_000L,
            elapsed = 50_000L,
        )
        val store = MrpNowPlayingStore(clock)
        val client = NowPlayingClient.newBuilder()
            .setBundleIdentifier("com.example.video")
            .setDisplayName("Video")
            .build()
        val player = NowPlayingPlayer.newBuilder()
            .setIdentifier("main")
            .setDisplayName("Main")
            .build()
        val path = PlayerPath.newBuilder().setClient(client).setPlayer(player).build()
        val cocoaOneSecondAgo = clock.wall / 1_000.0 - 978_307_200.0 - 1.0
        val metadata = ContentItemMetadata.newBuilder()
            .setTitle("Episode title")
            .setSubtitle("Episode subtitle")
            .setTrackArtistName("Artist")
            .setAlbumName("Album")
            .setSeriesName("Series")
            .setSeasonNumber(2)
            .setEpisodeNumber(7)
            .setDuration(1_800.0)
            .setElapsedTime(10.0)
            .setElapsedTimeTimestamp(cocoaOneSecondAgo)
            .setPlaybackRate(1.0f)
            .setContentIdentifier("content-1")
            .setArtworkAvailable(true)
            .setArtworkIdentifier("art-1")
            .setArtworkMIMEType("image/jpeg")
            .setMediaType(ContentItemMetadata.MediaType.Video)
            .build()
        val item = ContentItem.newBuilder()
            .setIdentifier("item-1")
            .setMetadata(metadata)
            .setArtworkData(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .setArtworkDataWidth(640)
            .setArtworkDataHeight(360)
            .build()
        val queue = PlaybackQueue.newBuilder().setLocation(0).addContentItems(item).build()
        val commands = SupportedCommands.newBuilder()
            .addSupportedCommands(CommandInfo.newBuilder().setCommand(Command.Play).setEnabled(true))
            .addSupportedCommands(CommandInfo.newBuilder().setCommand(Command.Pause).setEnabled(true))
            .build()
        val state = SetStateMessage.newBuilder()
            .setPlayerPath(path)
            .setPlaybackState(PlaybackState.Enum.Playing)
            .setPlaybackQueue(queue)
            .setSupportedCommands(commands)
            .build()

        store.accept(envelope(ProtocolMessage.Type.SET_STATE_MESSAGE, SetStateMessageOuterClass.setStateMessage, state))
        store.accept(
            envelope(
                ProtocolMessage.Type.SET_NOW_PLAYING_CLIENT_MESSAGE,
                SetNowPlayingClientMessageOuterClass.setNowPlayingClientMessage,
                SetNowPlayingClientMessage.newBuilder().setClient(client).build(),
            ),
        )
        store.accept(
            envelope(
                ProtocolMessage.Type.SET_NOW_PLAYING_PLAYER_MESSAGE,
                SetNowPlayingPlayerMessageOuterClass.setNowPlayingPlayerMessage,
                SetNowPlayingPlayerMessage.newBuilder().setPlayerPath(path).build(),
            ),
        )

        val snapshot = store.snapshot.value
        assertEquals(MrpPlaybackState.PLAYING, snapshot.state)
        assertEquals(MrpMediaType.VIDEO, snapshot.mediaType)
        assertEquals("Episode title", snapshot.title)
        assertEquals("Series", snapshot.series)
        assertEquals("com.example.video", snapshot.appBundleIdentifier)
        assertEquals("content-1", snapshot.contentIdentifier)
        val position = snapshot.position!!
        assertEquals(11.0, position.positionSeconds, 0.01)
        clock.elapsed += 2_000
        assertEquals(13.0, position.estimatedPositionSeconds(snapshot.state, clock.elapsed), 0.01)
        val artwork = snapshot.artwork!!
        assertArrayEquals(byteArrayOf(1, 2, 3), artwork.bytes)
        assertEquals(640, artwork.width)
        assertTrue(snapshot.playSupported)
        assertTrue(snapshot.pauseSupported)
        assertTrue(snapshot.authoritative)

        // A physical Siri Remote update changes the authoritative state even
        // though CyberRemote did not originate the command.
        val paused = SetStateMessage.newBuilder()
            .setPlayerPath(path)
            .setPlaybackState(PlaybackState.Enum.Paused)
            .build()
        store.accept(envelope(ProtocolMessage.Type.SET_STATE_MESSAGE, SetStateMessageOuterClass.setStateMessage, paused))
        assertEquals(MrpPlaybackState.PAUSED, store.snapshot.value.state)
    }

    @Test
    fun `content update merges instead of erasing queue metadata`() {
        val store = MrpNowPlayingStore(FakeClock(1_700_000_000_000L, 10L))
        val client = NowPlayingClient.newBuilder().setBundleIdentifier("app").build()
        val player = NowPlayingPlayer.newBuilder().setIdentifier("p").build()
        val path = PlayerPath.newBuilder().setClient(client).setPlayer(player).build()
        val original = ContentItem.newBuilder()
            .setIdentifier("i")
            .setMetadata(ContentItemMetadata.newBuilder().setTitle("Old").setAlbumName("Keep"))
            .build()
        val initial = SetStateMessage.newBuilder()
            .setPlayerPath(path)
            .setPlaybackState(PlaybackState.Enum.Playing)
            .setPlaybackQueue(PlaybackQueue.newBuilder().addContentItems(original))
            .build()
        store.accept(envelope(ProtocolMessage.Type.SET_STATE_MESSAGE, SetStateMessageOuterClass.setStateMessage, initial))
        store.accept(
            envelope(
                ProtocolMessage.Type.SET_NOW_PLAYING_CLIENT_MESSAGE,
                SetNowPlayingClientMessageOuterClass.setNowPlayingClientMessage,
                SetNowPlayingClientMessage.newBuilder().setClient(client).build(),
            ),
        )
        store.accept(
            envelope(
                ProtocolMessage.Type.SET_NOW_PLAYING_PLAYER_MESSAGE,
                SetNowPlayingPlayerMessageOuterClass.setNowPlayingPlayerMessage,
                SetNowPlayingPlayerMessage.newBuilder().setPlayerPath(path).build(),
            ),
        )
        val patch = ContentItem.newBuilder()
            .setIdentifier("i")
            .setMetadata(ContentItemMetadata.newBuilder().setTitle("New"))
            .build()
        val update = UpdateContentItemMessage.newBuilder().setPlayerPath(path).addContentItems(patch).build()
        store.accept(
            envelope(
                ProtocolMessage.Type.UPDATE_CONTENT_ITEM_MESSAGE,
                UpdateContentItemMessageOuterClass.updateContentItemMessage,
                update,
            ),
        )

        assertEquals("New", store.snapshot.value.title)
        assertEquals("Keep", store.snapshot.value.album)
    }
}

private class FakeClock(var wall: Long, var elapsed: Long) : MrpClock {
    override fun wallTimeMillis(): Long = wall
    override fun elapsedRealtimeMillis(): Long = elapsed
}

private fun <T : com.google.protobuf.Message> envelope(
    type: ProtocolMessage.Type,
    extension: com.google.protobuf.GeneratedMessage.GeneratedExtension<ProtocolMessage, T>,
    value: T,
): ProtocolMessage = ProtocolMessage.newBuilder()
    .setType(type)
    .setExtension(extension, value)
    .build()
