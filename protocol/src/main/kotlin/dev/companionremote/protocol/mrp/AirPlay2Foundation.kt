package dev.companionremote.protocol.mrp

import dev.companionremote.protocol.crypto.Crypto
import dev.companionremote.protocol.plist.BinaryPlist
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MrpDiscoveredService(
    val type: String,
    val host: String,
    val port: Int,
    val properties: Map<String, String>,
)

enum class MrpTransportPath { DIRECT_MRP, AIRPLAY2_DATASTREAM, UNAVAILABLE }
enum class MrpCapabilityStatus { READY, PAIRING_REQUIRED, FOUNDATION_ONLY, UNSUPPORTED }

data class MrpCapability(
    val path: MrpTransportPath,
    val status: MrpCapabilityStatus,
    val host: String?,
    val port: Int?,
    val reason: String,
)

/**
 * Selects the MRP transport from mDNS results without probing other networks.
 *
 * Direct `_mediaremotetv` stopped working with tvOS 15. Modern Apple TV uses
 * MRP in an authenticated AirPlay 2 DataStream. The codec and crypto
 * primitives for that tunnel live in this module, but the RTSP/event-channel
 * orchestration is intentionally reported as [MrpCapabilityStatus.FOUNDATION_ONLY]
 * until it is exercised against real hardware.
 */
object MrpCapabilityProbe {
    const val DIRECT_SERVICE = "_mediaremotetv._tcp.local."
    const val AIRPLAY_SERVICE = "_airplay._tcp.local."

    fun evaluate(
        services: Collection<MrpDiscoveredService>,
        hasAirPlayCredentials: Boolean,
        forceAirPlayTunnel: Boolean = false,
    ): MrpCapability {
        val normalized = services.associateBy { normalizeType(it.type) }
        val direct = normalized[normalizeType(DIRECT_SERVICE)]
        val airPlay = normalized[normalizeType(AIRPLAY_SERVICE)]
        val directBuildMajor = direct?.properties?.caseInsensitive("SystemBuildVersion")
            ?.takeWhile(Char::isDigit)
            ?.toIntOrNull()

        if (!forceAirPlayTunnel && direct != null && (directBuildMajor == null || directBuildMajor < 19)) {
            return MrpCapability(
                MrpTransportPath.DIRECT_MRP,
                MrpCapabilityStatus.READY,
                direct.host,
                direct.port,
                "direct MRP service is usable on pre-tvOS 15 firmware",
            )
        }

        if (airPlay != null && supportsAirPlayRemoteControl(airPlay, forceAirPlayTunnel)) {
            return MrpCapability(
                MrpTransportPath.AIRPLAY2_DATASTREAM,
                if (hasAirPlayCredentials) {
                    MrpCapabilityStatus.FOUNDATION_ONLY
                } else {
                    MrpCapabilityStatus.PAIRING_REQUIRED
                },
                airPlay.host,
                airPlay.port,
                if (hasAirPlayCredentials) {
                    "AirPlay 2 MRP was detected; RTSP tunnel orchestration still requires live-device validation"
                } else {
                    "persistent AirPlay HAP credentials are required before opening the MRP tunnel"
                },
            )
        }

        if (direct != null && directBuildMajor != null && directBuildMajor >= 19) {
            return MrpCapability(
                MrpTransportPath.UNAVAILABLE,
                MrpCapabilityStatus.UNSUPPORTED,
                direct.host,
                direct.port,
                "direct MRP is disabled on tvOS 15+ and no usable AirPlay 2 service was discovered",
            )
        }
        return MrpCapability(
            MrpTransportPath.UNAVAILABLE,
            MrpCapabilityStatus.UNSUPPORTED,
            null,
            null,
            "no MRP-capable service was discovered",
        )
    }

    fun parseAirPlayFeatures(value: String): ULong? {
        val parts = value.split(",")
        if (parts.size !in 1..2) return null
        fun parse(part: String): ULong? = part.removePrefix("0x").toULongOrNull(16)
        val lower = parse(parts[0]) ?: return null
        if (parts.size == 1) return lower
        val upper = parse(parts[1]) ?: return null
        return (upper shl 32) or lower
    }

    private fun supportsAirPlayRemoteControl(service: MrpDiscoveredService, forced: Boolean): Boolean {
        if (forced) return true
        val model = service.properties.caseInsensitive("model") ?: return false
        if (!model.startsWith("AppleTV")) return false
        val majorVersion = service.properties.caseInsensitive("osvers")
            ?.substringBefore('.')
            ?.toIntOrNull()
            ?: return false
        return majorVersion >= 13
    }

    private fun normalizeType(type: String): String = type.trim().trimEnd('.').lowercase()
    private fun Map<String, String>.caseInsensitive(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

data class HapChannelKeys(val outputKey: ByteArray, val inputKey: ByteArray)

/** Exact AirPlay 2 HKDF labels used after AirPlay pair-verify. */
object AirPlay2HapKeys {
    fun control(sharedSecret: ByteArray): HapChannelKeys = derive(
        sharedSecret,
        "Control-Salt",
        "Control-Write-Encryption-Key",
        "Control-Read-Encryption-Key",
    )

    /** Event read/write labels are reversed because of the channel direction. */
    fun events(sharedSecret: ByteArray): HapChannelKeys = derive(
        sharedSecret,
        "Events-Salt",
        "Events-Read-Encryption-Key",
        "Events-Write-Encryption-Key",
    )

    fun dataStream(sharedSecret: ByteArray, seed: ULong): HapChannelKeys = derive(
        sharedSecret,
        "DataStream-Salt$seed",
        "DataStream-Output-Encryption-Key",
        "DataStream-Input-Encryption-Key",
    )

    private fun derive(sharedSecret: ByteArray, salt: String, outputInfo: String, inputInfo: String) =
        HapChannelKeys(
            Crypto.hkdfSha512(salt, outputInfo, sharedSecret),
            Crypto.hkdfSha512(salt, inputInfo, sharedSecret),
        )
}

/** HAP-IP 1024-byte segmented encryption for AirPlay control/event/data sockets. */
class HapChannelCipher(private val outputKey: ByteArray, private val inputKey: ByteArray) {
    private var outputCounter = 0L
    private var inputCounter = 0L
    private var encryptedBuffer = ByteArray(0)

    fun encrypt(plaintext: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 0
        while (offset < plaintext.size) {
            val size = minOf(HAP_FRAME_SIZE, plaintext.size - offset)
            val length = byteArrayOf((size and 0xFF).toByte(), ((size ushr 8) and 0xFF).toByte())
            val block = plaintext.copyOfRange(offset, offset + size)
            output.write(length)
            output.write(Crypto.chaChaEncrypt(outputKey, counterNonce(outputCounter++), block, length))
            offset += size
        }
        return output.toByteArray()
    }

    /** Feed arbitrary encrypted segmentation and return all complete plaintext blocks. */
    fun decrypt(data: ByteArray): ByteArray {
        encryptedBuffer += data
        val output = ByteArrayOutputStream()
        while (encryptedBuffer.size >= 2) {
            val plainLength = (encryptedBuffer[0].toInt() and 0xFF) or
                ((encryptedBuffer[1].toInt() and 0xFF) shl 8)
            if (plainLength > HAP_FRAME_SIZE) throw MrpException("invalid HAP frame length $plainLength")
            val total = 2 + plainLength + AUTH_TAG_SIZE
            if (encryptedBuffer.size < total) break
            val aad = encryptedBuffer.copyOfRange(0, 2)
            val encrypted = encryptedBuffer.copyOfRange(2, total)
            output.write(Crypto.chaChaDecrypt(inputKey, counterNonce(inputCounter++), encrypted, aad))
            encryptedBuffer = encryptedBuffer.copyOfRange(total, encryptedBuffer.size)
        }
        return output.toByteArray()
    }

    private fun counterNonce(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        for (index in 0 until 8) nonce[4 + index] = ((counter ushr (8 * index)) and 0xFF).toByte()
        return nonce
    }

    private companion object {
        const val HAP_FRAME_SIZE = 1024
        const val AUTH_TAG_SIZE = 16
    }
}

data class AirPlayDataStreamFrame(
    val messageType: String,
    val command: String,
    val sequenceNumber: Long,
    val payload: ByteArray,
)

/** Codec for the plaintext inside the AirPlay 2 HAP data channel. */
class AirPlayDataStreamCodec {
    private var buffer = ByteArray(0)

    fun encodeSync(sequenceNumber: Long, messages: List<ByteArray>): ByteArray {
        val protobufs = ByteArrayOutputStream()
        messages.forEach { message ->
            protobufs.write(encodeVarint(message.size.toLong()))
            protobufs.write(message)
        }
        val plist = BinaryPlist.encode(mapOf("params" to mapOf("data" to protobufs.toByteArray())))
        return encodeFrame("sync", "comm", sequenceNumber, plist)
    }

    fun encodeReply(sequenceNumber: Long): ByteArray = encodeFrame("rply", "", sequenceNumber, ByteArray(0))

    fun feed(data: ByteArray): List<AirPlayDataStreamFrame> {
        buffer += data
        val frames = mutableListOf<AirPlayDataStreamFrame>()
        while (buffer.size >= HEADER_SIZE) {
            val header = ByteBuffer.wrap(buffer, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            val size = header.int
            if (size < HEADER_SIZE || size > MAX_DATASTREAM_FRAME) {
                throw MrpException("invalid AirPlay DataStream frame size $size")
            }
            if (buffer.size < size) break
            val typeBytes = ByteArray(12).also(header::get)
            val commandBytes = ByteArray(4).also(header::get)
            val sequence = header.long
            header.int // padding
            val payload = buffer.copyOfRange(HEADER_SIZE, size)
            buffer = buffer.copyOfRange(size, buffer.size)
            frames += AirPlayDataStreamFrame(
                messageType = typeBytes.trimZeroes(),
                command = commandBytes.trimZeroes(),
                sequenceNumber = sequence,
                payload = payload,
            )
        }
        return frames
    }

    fun decodeProtobufMessages(frame: AirPlayDataStreamFrame): List<ByteArray> {
        if (frame.payload.isEmpty()) return emptyList()
        val plist = BinaryPlist.decode(frame.payload) as? Map<*, *> ?: return emptyList()
        val params = plist["params"] as? Map<*, *> ?: return emptyList()
        var data = params["data"] as? ByteArray ?: return emptyList()
        val messages = mutableListOf<ByteArray>()
        while (data.isNotEmpty()) {
            // ConfigureConnection is sometimes not length-prefixed. Every
            // regular ProtocolMessage begins with type tag 0x08.
            if (data[0] == 0x08.toByte()) {
                messages += data
                break
            }
            val (lengthLong, consumed) = decodeVarint(data, 0)
            if (lengthLong > Int.MAX_VALUE) throw MrpException("protobuf message too large")
            val length = lengthLong.toInt()
            if (consumed + length > data.size) throw MrpException("truncated protobuf in DataStream frame")
            messages += data.copyOfRange(consumed, consumed + length)
            data = data.copyOfRange(consumed + length, data.size)
        }
        return messages
    }

    private fun encodeFrame(type: String, command: String, sequence: Long, payload: ByteArray): ByteArray {
        val size = HEADER_SIZE + payload.size
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            .putInt(size)
            .put(type.paddedAscii(12))
            .put(command.paddedAscii(4))
            .putLong(sequence)
            .putInt(0)
            .put(payload)
            .array()
    }

    private fun String.paddedAscii(size: Int): ByteArray {
        val encoded = toByteArray(Charsets.US_ASCII)
        require(encoded.size <= size) { "value is longer than $size bytes" }
        return encoded.copyOf(size)
    }

    private fun ByteArray.trimZeroes(): String {
        val length = indexOfFirst { it == 0.toByte() }.let { if (it < 0) size else it }
        return copyOfRange(0, length).toString(Charsets.US_ASCII)
    }

    private companion object {
        const val HEADER_SIZE = 32
        const val MAX_DATASTREAM_FRAME = 16 * 1024 * 1024
    }
}
