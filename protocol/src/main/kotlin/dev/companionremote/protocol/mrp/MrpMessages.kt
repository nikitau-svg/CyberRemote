package dev.companionremote.protocol.mrp

import com.google.protobuf.ExtensionRegistry
import dev.companionremote.protocol.mrp.proto.ClientUpdatesConfigMessage
import dev.companionremote.protocol.mrp.proto.ClientUpdatesConfigMessageOuterClass
import dev.companionremote.protocol.mrp.proto.Command
import dev.companionremote.protocol.mrp.proto.CryptoPairingMessage
import dev.companionremote.protocol.mrp.proto.CryptoPairingMessageOuterClass
import dev.companionremote.protocol.mrp.proto.DeviceClass
import dev.companionremote.protocol.mrp.proto.DeviceInfoMessage
import dev.companionremote.protocol.mrp.proto.DeviceInfoMessageOuterClass
import dev.companionremote.protocol.mrp.proto.GenericMessageOuterClass
import dev.companionremote.protocol.mrp.proto.PlaybackQueueRequestMessage
import dev.companionremote.protocol.mrp.proto.PlaybackQueueRequestMessageOuterClass
import dev.companionremote.protocol.mrp.proto.ProtocolMessage
import dev.companionremote.protocol.mrp.proto.RemoveClientMessageOuterClass
import dev.companionremote.protocol.mrp.proto.RemovePlayerMessageOuterClass
import dev.companionremote.protocol.mrp.proto.SendCommandMessage
import dev.companionremote.protocol.mrp.proto.SendCommandMessageOuterClass
import dev.companionremote.protocol.mrp.proto.SendCommandResultMessageOuterClass
import dev.companionremote.protocol.mrp.proto.SetConnectionStateMessage
import dev.companionremote.protocol.mrp.proto.SetConnectionStateMessageOuterClass
import dev.companionremote.protocol.mrp.proto.SetDefaultSupportedCommandsMessageOuterClass
import dev.companionremote.protocol.mrp.proto.SetNowPlayingClientMessageOuterClass
import dev.companionremote.protocol.mrp.proto.SetNowPlayingPlayerMessageOuterClass
import dev.companionremote.protocol.mrp.proto.SetStateMessageOuterClass
import dev.companionremote.protocol.mrp.proto.UpdateClientMessageOuterClass
import dev.companionremote.protocol.mrp.proto.UpdateContentItemArtworkMessageOuterClass
import dev.companionremote.protocol.mrp.proto.UpdateContentItemMessageOuterClass
import java.util.UUID

/** Device identity advertised inside the first MRP message. */
data class MrpClientInfo(
    val name: String = "CyberRemote",
    val uniqueIdentifier: String = UUID.randomUUID().toString().uppercase(),
    val systemBuildVersion: String = "21E236",
    val applicationVersion: String = "1.0",
)

/**
 * Builders and parser for the generated MediaRemote protobuf schema.
 *
 * Proto2 extension registration is mandatory. Parsing without [registry]
 * silently leaves every message payload in protobuf unknown fields.
 */
object MrpMessages {
    val registry: ExtensionRegistry = ExtensionRegistry.newInstance().also { registry ->
        ClientUpdatesConfigMessageOuterClass.registerAllExtensions(registry)
        CryptoPairingMessageOuterClass.registerAllExtensions(registry)
        DeviceInfoMessageOuterClass.registerAllExtensions(registry)
        GenericMessageOuterClass.registerAllExtensions(registry)
        PlaybackQueueRequestMessageOuterClass.registerAllExtensions(registry)
        RemoveClientMessageOuterClass.registerAllExtensions(registry)
        RemovePlayerMessageOuterClass.registerAllExtensions(registry)
        SendCommandMessageOuterClass.registerAllExtensions(registry)
        SendCommandResultMessageOuterClass.registerAllExtensions(registry)
        SetConnectionStateMessageOuterClass.registerAllExtensions(registry)
        SetDefaultSupportedCommandsMessageOuterClass.registerAllExtensions(registry)
        SetNowPlayingClientMessageOuterClass.registerAllExtensions(registry)
        SetNowPlayingPlayerMessageOuterClass.registerAllExtensions(registry)
        SetStateMessageOuterClass.registerAllExtensions(registry)
        UpdateClientMessageOuterClass.registerAllExtensions(registry)
        UpdateContentItemMessageOuterClass.registerAllExtensions(registry)
        UpdateContentItemArtworkMessageOuterClass.registerAllExtensions(registry)
    }

    fun parse(data: ByteArray): ProtocolMessage = ProtocolMessage.parseFrom(data, registry)

    fun deviceInformation(info: MrpClientInfo): ProtocolMessage {
        val device = DeviceInfoMessage.newBuilder()
            .setUniqueIdentifier(info.uniqueIdentifier)
            .setName(info.name)
            .setLocalizedModelName("Android")
            // Apple TV currently expects the TV Remote bundle identity here.
            .setApplicationBundleIdentifier("com.apple.TVRemote")
            .setApplicationBundleVersion(info.applicationVersion)
            .setSystemBuildVersion(info.systemBuildVersion)
            .setProtocolVersion(1)
            .setLastSupportedMessageType(108)
            .setSupportsSystemPairing(true)
            .setAllowsPairing(true)
            .setSupportsACL(true)
            .setSupportsSharedQueue(true)
            .setSharedQueueVersion(2)
            .setSupportsExtendedMotion(true)
            .setSystemMediaApplication("com.apple.TVMusic")
            .setDeviceClass(DeviceClass.Enum.iPhone)
            .setLogicalDeviceCount(1)
            .build()
        return base(ProtocolMessage.Type.DEVICE_INFO_MESSAGE)
            .setExtension(DeviceInfoMessageOuterClass.deviceInfoMessage, device)
            .build()
    }

    fun cryptoPairing(pairingData: ByteArray, isPairing: Boolean = false): ProtocolMessage {
        val crypto = CryptoPairingMessage.newBuilder()
            .setPairingData(com.google.protobuf.ByteString.copyFrom(pairingData))
            .setStatus(0)
            .setIsRetrying(false)
            .setIsUsingSystemPairing(false)
            .setState(if (isPairing) 2 else 0)
            .build()
        return base(ProtocolMessage.Type.CRYPTO_PAIRING_MESSAGE)
            .setExtension(CryptoPairingMessageOuterClass.cryptoPairingMessage, crypto)
            .build()
    }

    fun setConnectionStateConnected(): ProtocolMessage {
        val state = SetConnectionStateMessage.newBuilder()
            .setState(SetConnectionStateMessage.ConnectionState.Connected)
            .build()
        return base(ProtocolMessage.Type.SET_CONNECTION_STATE_MESSAGE)
            .setExtension(SetConnectionStateMessageOuterClass.setConnectionStateMessage, state)
            .build()
    }

    /** Matches pyatv's subscription values; nonessential streams can be ignored by consumers. */
    fun clientUpdatesConfig(): ProtocolMessage {
        val config = ClientUpdatesConfigMessage.newBuilder()
            .setArtworkUpdates(true)
            .setNowPlayingUpdates(false)
            .setVolumeUpdates(true)
            .setKeyboardUpdates(true)
            .setOutputDeviceUpdates(true)
            .build()
        return base(ProtocolMessage.Type.CLIENT_UPDATES_CONFIG_MESSAGE)
            .setExtension(ClientUpdatesConfigMessageOuterClass.clientUpdatesConfigMessage, config)
            .build()
    }

    fun command(command: Command): ProtocolMessage {
        val inner = SendCommandMessage.newBuilder().setCommand(command).build()
        return base(ProtocolMessage.Type.SEND_COMMAND_MESSAGE)
            .setExtension(SendCommandMessageOuterClass.sendCommandMessage, inner)
            .build()
    }

    fun playbackQueueRequest(location: Int, width: Int = -1, height: Int = 400): ProtocolMessage {
        val request = PlaybackQueueRequestMessage.newBuilder()
            .setLocation(location)
            .setLength(1)
            .setArtworkWidth(width.toDouble())
            .setArtworkHeight(height.toDouble())
            .setReturnContentItemAssetsInUserCompletion(true)
            .build()
        return base(ProtocolMessage.Type.PLAYBACK_QUEUE_REQUEST_MESSAGE)
            .setExtension(PlaybackQueueRequestMessageOuterClass.playbackQueueRequestMessage, request)
            .build()
    }

    fun generic(): ProtocolMessage = base(ProtocolMessage.Type.GENERIC_MESSAGE)
        .setExtension(GenericMessageOuterClass.genericMessage, dev.companionremote.protocol.mrp.proto.GenericMessage.getDefaultInstance())
        .build()

    fun withIdentifier(message: ProtocolMessage, identifier: String): ProtocolMessage =
        message.toBuilder().setIdentifier(identifier).build()

    private fun base(type: ProtocolMessage.Type): ProtocolMessage.Builder = ProtocolMessage.newBuilder()
        .setType(type)
        .setErrorCode(dev.companionremote.protocol.mrp.proto.ErrorCode.Enum.NoError)
        .setUniqueIdentifier(UUID.randomUUID().toString().uppercase())
}
