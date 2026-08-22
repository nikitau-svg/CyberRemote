package dev.companionremote.protocol.mrp

import dev.companionremote.protocol.mrp.proto.Command
import dev.companionremote.protocol.mrp.proto.ContentItem
import dev.companionremote.protocol.mrp.proto.ContentItemMetadata
import dev.companionremote.protocol.mrp.proto.NowPlayingClient
import dev.companionremote.protocol.mrp.proto.NowPlayingPlayer
import dev.companionremote.protocol.mrp.proto.PlayerPath
import dev.companionremote.protocol.mrp.proto.ProtocolMessage
import dev.companionremote.protocol.mrp.proto.RemoveClientMessageOuterClass
import dev.companionremote.protocol.mrp.proto.RemovePlayerMessageOuterClass
import dev.companionremote.protocol.mrp.proto.SetDefaultSupportedCommandsMessageOuterClass
import dev.companionremote.protocol.mrp.proto.SetNowPlayingClientMessageOuterClass
import dev.companionremote.protocol.mrp.proto.SetNowPlayingPlayerMessageOuterClass
import dev.companionremote.protocol.mrp.proto.SetStateMessage
import dev.companionremote.protocol.mrp.proto.SetStateMessageOuterClass
import dev.companionremote.protocol.mrp.proto.UpdateClientMessageOuterClass
import dev.companionremote.protocol.mrp.proto.UpdateContentItemArtworkMessageOuterClass
import dev.companionremote.protocol.mrp.proto.UpdateContentItemMessageOuterClass
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MrpPlaybackState {
    UNKNOWN,
    IDLE,
    PLAYING,
    PAUSED,
    STOPPED,
    LOADING,
    SEEKING,
}

enum class MrpMediaType { UNKNOWN, AUDIO, VIDEO }

data class MrpPositionAnchor(
    val positionSeconds: Double,
    val durationSeconds: Double?,
    val playbackRate: Double,
    val sourceTimestampCocoaSeconds: Double?,
    val anchoredAtElapsedRealtimeMillis: Long,
) {
    fun estimatedPositionSeconds(
        state: MrpPlaybackState,
        nowElapsedRealtimeMillis: Long = System.nanoTime() / 1_000_000L,
    ): Double {
        val elapsed = if (state == MrpPlaybackState.PLAYING) {
            ((nowElapsedRealtimeMillis - anchoredAtElapsedRealtimeMillis).coerceAtLeast(0L) / 1_000.0) * playbackRate
        } else {
            0.0
        }
        val estimate = (positionSeconds + elapsed).coerceAtLeast(0.0)
        return durationSeconds?.takeIf { it.isFinite() && it >= 0.0 }?.let { estimate.coerceAtMost(it) } ?: estimate
    }
}

data class MrpArtwork(
    val identifier: String?,
    val mimeType: String?,
    val url: String?,
    val fileUrl: String?,
    val bytes: ByteArray?,
    val width: Int?,
    val height: Int?,
)

data class NowPlayingSnapshot(
    val state: MrpPlaybackState = MrpPlaybackState.IDLE,
    val mediaType: MrpMediaType = MrpMediaType.UNKNOWN,
    val title: String? = null,
    val subtitle: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val series: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val appBundleIdentifier: String? = null,
    val appDisplayName: String? = null,
    val playerIdentifier: String? = null,
    val contentIdentifier: String? = null,
    val itemIdentifier: String? = null,
    val queueLocation: Int = 0,
    val position: MrpPositionAnchor? = null,
    val artwork: MrpArtwork? = null,
    val playSupported: Boolean = false,
    val pauseSupported: Boolean = false,
    val authoritative: Boolean = false,
    val updatedAtElapsedRealtimeMillis: Long = 0L,
)

interface MrpClock {
    fun wallTimeMillis(): Long
    fun elapsedRealtimeMillis(): Long
}

object SystemMrpClock : MrpClock {
    override fun wallTimeMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMillis(): Long = System.nanoTime() / 1_000_000L
}

/**
 * Stateful reducer for MRP push events. It mirrors pyatv's client -> player ->
 * queue model and emits one authoritative snapshot for the active player.
 */
class MrpNowPlayingStore(private val clock: MrpClock = SystemMrpClock) {
    private val clients = linkedMapOf<String, ClientRecord>()
    private var activeClientKey: String? = null
    private val mutableSnapshot = MutableStateFlow(NowPlayingSnapshot())
    val snapshot: StateFlow<NowPlayingSnapshot> = mutableSnapshot.asStateFlow()

    fun accept(message: ProtocolMessage) {
        when (message.type) {
            ProtocolMessage.Type.SET_STATE_MESSAGE -> if (
                message.hasExtension(SetStateMessageOuterClass.setStateMessage)
            ) {
                applySetState(message.getExtension(SetStateMessageOuterClass.setStateMessage))
            }
            ProtocolMessage.Type.UPDATE_CONTENT_ITEM_MESSAGE -> if (
                message.hasExtension(UpdateContentItemMessageOuterClass.updateContentItemMessage)
            ) {
                val update = message.getExtension(UpdateContentItemMessageOuterClass.updateContentItemMessage)
                mergeContentItems(update.playerPath, update.contentItemsList)
            }
            ProtocolMessage.Type.UPDATE_CONTENT_ITEM_ARTWORK_MESSAGE -> if (
                message.hasExtension(UpdateContentItemArtworkMessageOuterClass.updateContentItemArtworkMessage)
            ) {
                val update = message.getExtension(
                    UpdateContentItemArtworkMessageOuterClass.updateContentItemArtworkMessage,
                )
                mergeContentItems(update.playerPath, update.contentItemsList)
            }
            ProtocolMessage.Type.SET_NOW_PLAYING_CLIENT_MESSAGE -> if (
                message.hasExtension(SetNowPlayingClientMessageOuterClass.setNowPlayingClientMessage)
            ) {
                val client = message.getExtension(
                    SetNowPlayingClientMessageOuterClass.setNowPlayingClientMessage,
                ).client
                activeClientKey = clientKey(client)
                getClient(client).update(client)
            }
            ProtocolMessage.Type.SET_NOW_PLAYING_PLAYER_MESSAGE -> if (
                message.hasExtension(SetNowPlayingPlayerMessageOuterClass.setNowPlayingPlayerMessage)
            ) {
                val path = message.getExtension(
                    SetNowPlayingPlayerMessageOuterClass.setNowPlayingPlayerMessage,
                ).playerPath
                val client = getClient(path.client)
                val player = client.getPlayer(path.player)
                client.activePlayerId = player.identifier
            }
            ProtocolMessage.Type.UPDATE_CLIENT_MESSAGE -> if (
                message.hasExtension(UpdateClientMessageOuterClass.updateClientMessage)
            ) {
                val client = message.getExtension(UpdateClientMessageOuterClass.updateClientMessage).client
                getClient(client).update(client)
            }
            ProtocolMessage.Type.REMOVE_CLIENT_MESSAGE -> if (
                message.hasExtension(RemoveClientMessageOuterClass.removeClientMessage)
            ) {
                val client = message.getExtension(RemoveClientMessageOuterClass.removeClientMessage).client
                val key = clientKey(client)
                clients.remove(key)
                if (activeClientKey == key) activeClientKey = null
            }
            ProtocolMessage.Type.REMOVE_PLAYER_MESSAGE -> if (
                message.hasExtension(RemovePlayerMessageOuterClass.removePlayerMessage)
            ) {
                val path = message.getExtension(RemovePlayerMessageOuterClass.removePlayerMessage).playerPath
                val client = clients[clientKey(path.client)]
                val playerId = playerIdentifier(path.player)
                client?.players?.remove(playerId)
                if (client?.activePlayerId == playerId) client.activePlayerId = null
            }
            ProtocolMessage.Type.SET_DEFAULT_SUPPORTED_COMMANDS_MESSAGE -> if (
                message.hasExtension(
                    SetDefaultSupportedCommandsMessageOuterClass.setDefaultSupportedCommandsMessage,
                )
            ) {
                val update = message.getExtension(
                    SetDefaultSupportedCommandsMessageOuterClass.setDefaultSupportedCommandsMessage,
                )
                val client = getClient(update.playerPath.client)
                if (update.hasSupportedCommands()) {
                    client.defaultCommands = update.supportedCommands.supportedCommandsList
                        .filter { it.enabled }
                        .map { it.command }
                        .toSet()
                }
            }
            else -> return
        }
        publish()
    }

    private fun applySetState(update: SetStateMessage) {
        val client = getClient(update.playerPath.client)
        val player = client.getPlayer(update.playerPath.player)
        if (update.hasPlaybackState()) player.playbackState = update.playbackState.number
        if (update.hasSupportedCommands()) {
            player.commands = update.supportedCommands.supportedCommandsList
                .filter { it.enabled }
                .map { it.command }
                .toSet()
        }
        if (update.hasPlaybackQueue()) {
            player.location = update.playbackQueue.location
            player.items.clear()
            update.playbackQueue.contentItemsList.forEach { item ->
                player.items += ContentRecord.from(item)
            }
        } else if (update.hasNowPlayingInfo()) {
            val info = update.nowPlayingInfo
            val record = player.currentItem() ?: ContentRecord("legacy-now-playing").also {
                player.items.clear()
                player.items += it
                player.location = 0
            }
            record.metadata.apply {
                if (info.hasTitle()) title = info.title
                if (info.hasArtist()) artist = info.artist
                if (info.hasAlbum()) album = info.album
                if (info.hasDuration()) duration = info.duration
                if (info.hasElapsedTime()) elapsedTime = info.elapsedTime
                if (info.hasPlaybackRate()) playbackRate = info.playbackRate.toDouble()
                if (info.hasTimestamp()) elapsedTimestamp = info.timestamp
                if (info.hasArtworkDataDigest()) artworkIdentifier = info.artworkDataDigest.toByteArray().toHex()
            }
        }
    }

    private fun mergeContentItems(path: PlayerPath, updates: List<ContentItem>) {
        val player = getClient(path.client).getPlayer(path.player)
        updates.forEach { update ->
            val existing = player.items.firstOrNull { it.identifier == update.identifier }
                ?: ContentRecord(update.identifier).also { player.items += it }
            existing.merge(update)
        }
    }

    private fun publish() {
        val now = clock.elapsedRealtimeMillis()
        val client = activeClientKey?.let(clients::get)
        if (client == null) {
            mutableSnapshot.value = NowPlayingSnapshot(
                authoritative = true,
                updatedAtElapsedRealtimeMillis = now,
            )
            return
        }
        val player = client.activePlayerId?.let(client.players::get)
            ?: client.players[DEFAULT_PLAYER_ID]
        if (player == null) {
            mutableSnapshot.value = NowPlayingSnapshot(
                appBundleIdentifier = client.bundleIdentifier,
                appDisplayName = client.displayName,
                authoritative = true,
                updatedAtElapsedRealtimeMillis = now,
            )
            return
        }
        val item = player.currentItem()
        val metadata = item?.metadata
        val state = playbackState(player.playbackState, item != null)
        val duration = metadata?.duration?.takeIf { it.isFinite() && it >= 0.0 }
        val reportedRate = metadata?.playbackRate ?: 0.0
        val position = metadata?.elapsedTime?.let { elapsed ->
            val sourceTimestamp = metadata.elapsedTimestamp
            val ageSeconds = sourceTimestamp?.let {
                val sourceUnixMillis = ((it + COCOA_UNIX_EPOCH_SECONDS) * 1_000.0).roundToLong()
                ((clock.wallTimeMillis() - sourceUnixMillis) / 1_000.0).coerceIn(-5.0, MAX_TIMESTAMP_AGE_SECONDS)
            } ?: 0.0
            val anchored = if (state == MrpPlaybackState.PLAYING) {
                elapsed + ageSeconds * reportedRate
            } else {
                elapsed
            }
            MrpPositionAnchor(
                positionSeconds = anchored.coerceAtLeast(0.0),
                durationSeconds = duration,
                playbackRate = reportedRate,
                sourceTimestampCocoaSeconds = sourceTimestamp,
                anchoredAtElapsedRealtimeMillis = now,
            )
        }
        val commands = client.defaultCommands + player.commands
        mutableSnapshot.value = NowPlayingSnapshot(
            state = state,
            mediaType = when (metadata?.mediaType) {
                1 -> MrpMediaType.AUDIO
                2 -> MrpMediaType.VIDEO
                else -> MrpMediaType.UNKNOWN
            },
            title = metadata?.title,
            subtitle = metadata?.subtitle,
            artist = metadata?.artist,
            album = metadata?.album,
            series = metadata?.series,
            seasonNumber = metadata?.seasonNumber,
            episodeNumber = metadata?.episodeNumber,
            appBundleIdentifier = client.bundleIdentifier,
            appDisplayName = client.displayName,
            playerIdentifier = player.identifier,
            contentIdentifier = metadata?.contentIdentifier,
            itemIdentifier = item?.identifier,
            queueLocation = player.location,
            position = position,
            artwork = item?.artwork(),
            playSupported = Command.Play in commands,
            pauseSupported = Command.Pause in commands,
            authoritative = true,
            updatedAtElapsedRealtimeMillis = now,
        )
    }

    private fun getClient(client: NowPlayingClient): ClientRecord {
        val key = clientKey(client)
        return clients.getOrPut(key) { ClientRecord(key) }
    }

    private fun playbackState(raw: Int?, hasItem: Boolean): MrpPlaybackState = when (raw) {
        null, 0 -> MrpPlaybackState.IDLE
        1 -> MrpPlaybackState.PLAYING
        2 -> if (hasItem) MrpPlaybackState.PAUSED else MrpPlaybackState.IDLE
        3 -> MrpPlaybackState.STOPPED
        4 -> MrpPlaybackState.LOADING
        5 -> MrpPlaybackState.SEEKING
        else -> MrpPlaybackState.UNKNOWN
    }

    private class ClientRecord(val bundleIdentifier: String) {
        var displayName: String? = null
        var activePlayerId: String? = null
        var defaultCommands: Set<Command> = emptySet()
        val players = linkedMapOf<String, PlayerRecord>()

        fun update(client: NowPlayingClient) {
            if (client.hasDisplayName() && client.displayName.isNotEmpty()) displayName = client.displayName
        }

        fun getPlayer(player: NowPlayingPlayer): PlayerRecord {
            val id = playerIdentifier(player)
            return players.getOrPut(id) { PlayerRecord(id) }.also {
                if (player.hasDisplayName() && player.displayName.isNotEmpty()) it.displayName = player.displayName
            }
        }
    }

    private class PlayerRecord(val identifier: String) {
        var displayName: String? = null
        var playbackState: Int? = null
        var commands: Set<Command> = emptySet()
        var location: Int = 0
        val items = mutableListOf<ContentRecord>()
        fun currentItem(): ContentRecord? = items.getOrNull(location)
    }

    private class ContentRecord(val identifier: String) {
        val metadata = MetadataRecord()
        var artworkBytes: ByteArray? = null
        var artworkWidth: Int? = null
        var artworkHeight: Int? = null

        fun merge(item: ContentItem) {
            if (item.hasMetadata()) metadata.merge(item.metadata)
            if (item.hasArtworkData()) artworkBytes = item.artworkData.toByteArray()
            if (item.hasArtworkDataWidth()) artworkWidth = item.artworkDataWidth
            if (item.hasArtworkDataHeight()) artworkHeight = item.artworkDataHeight
        }

        fun artwork(): MrpArtwork? {
            val identifier = metadata.artworkIdentifier ?: metadata.contentIdentifier ?: this.identifier
            if (!metadata.artworkAvailable && artworkBytes == null && metadata.artworkUrl == null &&
                metadata.artworkFileUrl == null && metadata.artworkIdentifier == null
            ) return null
            return MrpArtwork(
                identifier = identifier,
                mimeType = metadata.artworkMimeType,
                url = metadata.artworkUrl,
                fileUrl = metadata.artworkFileUrl,
                bytes = artworkBytes,
                width = artworkWidth ?: metadata.artworkWidth,
                height = artworkHeight ?: metadata.artworkHeight,
            )
        }

        companion object {
            fun from(item: ContentItem): ContentRecord = ContentRecord(item.identifier).also { it.merge(item) }
        }
    }

    private class MetadataRecord {
        var title: String? = null
        var subtitle: String? = null
        var album: String? = null
        var artist: String? = null
        var seasonNumber: Int? = null
        var episodeNumber: Int? = null
        var duration: Double? = null
        var artworkAvailable: Boolean = false
        var artworkMimeType: String? = null
        var elapsedTime: Double? = null
        var playbackRate: Double? = null
        var contentIdentifier: String? = null
        var series: String? = null
        var mediaType: Int? = null
        var artworkUrl: String? = null
        var elapsedTimestamp: Double? = null
        var artworkWidth: Int? = null
        var artworkHeight: Int? = null
        var artworkIdentifier: String? = null
        var artworkFileUrl: String? = null

        fun merge(value: ContentItemMetadata) {
            if (value.hasTitle()) title = value.title
            if (value.hasSubtitle()) subtitle = value.subtitle
            if (value.hasAlbumName()) album = value.albumName
            if (value.hasTrackArtistName()) artist = value.trackArtistName
            if (value.hasSeasonNumber()) seasonNumber = value.seasonNumber
            if (value.hasEpisodeNumber()) episodeNumber = value.episodeNumber
            if (value.hasDuration()) duration = value.duration
            if (value.hasArtworkAvailable()) artworkAvailable = value.artworkAvailable
            if (value.hasArtworkMIMEType()) artworkMimeType = value.artworkMIMEType
            if (value.hasElapsedTime()) elapsedTime = value.elapsedTime
            if (value.hasPlaybackRate()) playbackRate = value.playbackRate.toDouble()
            if (value.hasContentIdentifier()) contentIdentifier = value.contentIdentifier
            if (value.hasSeriesName()) series = value.seriesName
            if (value.hasMediaType()) mediaType = value.mediaType.number
            if (value.hasArtworkURL()) artworkUrl = value.artworkURL
            if (value.hasElapsedTimeTimestamp()) elapsedTimestamp = value.elapsedTimeTimestamp
            if (value.hasArtworkDataWidth()) artworkWidth = value.artworkDataWidth
            if (value.hasArtworkDataHeight()) artworkHeight = value.artworkDataHeight
            if (value.hasArtworkIdentifier()) artworkIdentifier = value.artworkIdentifier
            if (value.hasArtworkFileURL()) artworkFileUrl = value.artworkFileURL
        }
    }

    private companion object {
        const val DEFAULT_PLAYER_ID = "MediaRemote-DefaultPlayer"
        const val COCOA_UNIX_EPOCH_SECONDS = 978_307_200.0
        const val MAX_TIMESTAMP_AGE_SECONDS = 24.0 * 60.0 * 60.0

        fun clientKey(client: NowPlayingClient): String = when {
            client.hasBundleIdentifier() && client.bundleIdentifier.isNotEmpty() -> client.bundleIdentifier
            client.hasProcessIdentifier() -> "pid:${client.processIdentifier}"
            else -> "unknown-client"
        }

        fun playerIdentifier(player: NowPlayingPlayer): String =
            player.identifier.takeIf { player.hasIdentifier() && it.isNotEmpty() } ?: DEFAULT_PLAYER_ID
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
