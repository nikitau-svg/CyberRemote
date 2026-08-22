package dev.companionremote.protocol.mrp

import dev.companionremote.protocol.mrp.proto.Command
import dev.companionremote.protocol.mrp.proto.SendCommandResultMessageOuterClass
import dev.companionremote.protocol.mrp.proto.SetStateMessageOuterClass
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * High-level MRP Now Playing API, independent from Companion Link.
 *
 * A direct MRP caller supplies [MrpCredentials] and enables heartbeat. An
 * AirPlay 2 DataStream adapter will call the same API without inner MRP
 * credentials because the containing HAP channel is already authenticated.
 */
class MrpNowPlayingClient(
    private val connection: MrpConnection,
    private val clientInfo: MrpClientInfo = MrpClientInfo(),
    private val store: MrpNowPlayingStore = MrpNowPlayingStore(),
) {
    private val scope = CoroutineScope(Job() + Dispatchers.Default + CoroutineName("mrp-now-playing"))
    private var started = false
    private var heartbeatJob: Job? = null

    val snapshot: StateFlow<NowPlayingSnapshot> = store.snapshot

    /**
     * Start MRP and subscribe to server push state.
     *
     * [credentials] are for legacy/direct MRP only. Set [directHeartbeat] to
     * false when MRP is carried by an AirPlay 2 tunnel, whose `/feedback`
     * heartbeat belongs to the outer transport.
     */
    suspend fun start(credentials: MrpCredentials? = null, directHeartbeat: Boolean = credentials != null) {
        check(!started) { "MRP client already started" }
        started = true
        connection.start()

        // The device information message must be first and plaintext.
        connection.sendAndReceive(MrpMessages.deviceInformation(clientInfo))
        if (credentials != null) {
            val keys = MrpPairVerify(connection, credentials).verify()
            connection.enableEncryption(keys)
        }

        // Subscribe before ClientUpdatesConfig, which causes the server to
        // synchronously flush current players and queues before its ACK.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            connection.messages.collect(store::accept)
        }
        connection.send(MrpMessages.setConnectionStateConnected())
        connection.sendAndReceive(MrpMessages.clientUpdatesConfig())

        if (directHeartbeat) startHeartbeat()
    }

    /** Prepare a plaintext direct-MRP connection for [MrpPairSetup]. */
    suspend fun preparePairing(): MrpPairSetup {
        check(!started) { "MRP client already started" }
        started = true
        connection.start()
        connection.sendAndReceive(MrpMessages.deviceInformation(clientInfo))
        return MrpPairSetup(connection)
    }

    suspend fun play() = sendExplicit(Command.Play)

    suspend fun pause() = sendExplicit(Command.Pause)

    /** Fetch artwork bytes from the active playback queue when push metadata only contains a URL/id. */
    suspend fun requestArtwork(width: Int = -1, height: Int = 400): MrpArtwork? {
        ensureStarted()
        val current = snapshot.value
        val response = connection.sendAndReceive(
            MrpMessages.playbackQueueRequest(current.queueLocation, width, height),
        )
        if (!response.hasExtension(SetStateMessageOuterClass.setStateMessage)) return current.artwork
        val state = response.getExtension(SetStateMessageOuterClass.setStateMessage)
        if (!state.hasPlaybackQueue() || state.playbackQueue.contentItemsCount == 0) return current.artwork
        val location = state.playbackQueue.location
        val item = state.playbackQueue.contentItemsList.getOrNull(location)
            ?: state.playbackQueue.contentItemsList.first()
        val metadata = item.metadata
        val pushed = current.artwork
        return MrpArtwork(
            identifier = when {
                metadata.hasArtworkIdentifier() -> metadata.artworkIdentifier
                metadata.hasContentIdentifier() -> metadata.contentIdentifier
                item.hasIdentifier() -> item.identifier
                else -> pushed?.identifier
            },
            mimeType = metadata.artworkMIMEType.takeIf { metadata.hasArtworkMIMEType() }
                ?: pushed?.mimeType,
            url = metadata.artworkURL.takeIf { metadata.hasArtworkURL() } ?: pushed?.url,
            fileUrl = metadata.artworkFileURL.takeIf { metadata.hasArtworkFileURL() }
                ?: pushed?.fileUrl,
            bytes = item.artworkData.toByteArray().takeIf { item.hasArtworkData() }
                ?: pushed?.bytes,
            width = item.artworkDataWidth.takeIf { item.hasArtworkDataWidth() }
                ?: metadata.artworkDataWidth.takeIf { metadata.hasArtworkDataWidth() }
                ?: pushed?.width,
            height = item.artworkDataHeight.takeIf { item.hasArtworkDataHeight() }
                ?: metadata.artworkDataHeight.takeIf { metadata.hasArtworkDataHeight() }
                ?: pushed?.height,
        )
    }

    private suspend fun sendExplicit(command: Command) {
        ensureStarted()
        val response = connection.sendAndReceive(MrpMessages.command(command))
        if (!response.hasExtension(SendCommandResultMessageOuterClass.sendCommandResultMessage)) {
            if (response.hasErrorCode() && response.errorCode.number != 0) {
                throw MrpCommandException(
                    "$command failed with protocol error ${response.errorCode}",
                    response.errorCode.number,
                    null,
                )
            }
            return
        }
        val result = response.getExtension(SendCommandResultMessageOuterClass.sendCommandResultMessage)
        val sendError = result.sendError.number
        val handlerStatus = result.handlerReturnStatus.number
        if (sendError != 0 || handlerStatus != 0) {
            throw MrpCommandException(
                "$command failed: sendError=${result.sendError}, handlerStatus=${result.handlerReturnStatus}",
                sendError,
                handlerStatus,
            )
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                connection.sendAndReceive(MrpMessages.generic())
            }
        }
    }

    private fun ensureStarted() = check(started) { "call start() first" }

    fun close() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        scope.cancel()
        connection.close()
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
