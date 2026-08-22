package dev.companionremote.protocol.mrp

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AirPlay2FoundationTest {
    @Test
    fun `modern Apple TV selects AirPlay tunnel without claiming runtime ready`() {
        val service = MrpDiscoveredService(
            type = "_airplay._tcp.local.",
            host = "living-room.local",
            port = 7000,
            properties = mapOf("model" to "AppleTV14,1", "osvers" to "26.0"),
        )

        val beforePairing = MrpCapabilityProbe.evaluate(listOf(service), hasAirPlayCredentials = false)
        val afterPairing = MrpCapabilityProbe.evaluate(listOf(service), hasAirPlayCredentials = true)
        assertEquals(MrpTransportPath.AIRPLAY2_DATASTREAM, beforePairing.path)
        assertEquals(MrpCapabilityStatus.PAIRING_REQUIRED, beforePairing.status)
        assertEquals(MrpCapabilityStatus.FOUNDATION_ONLY, afterPairing.status)
    }

    @Test
    fun `legacy direct MRP remains ready`() {
        val service = MrpDiscoveredService(
            type = "_mediaremotetv._tcp.local",
            host = "old-atv.local",
            port = 49152,
            properties = mapOf("SystemBuildVersion" to "18M60"),
        )
        val result = MrpCapabilityProbe.evaluate(listOf(service), hasAirPlayCredentials = false)
        assertEquals(MrpTransportPath.DIRECT_MRP, result.path)
        assertEquals(MrpCapabilityStatus.READY, result.status)
    }

    @Test
    fun `HAP channel round trips multi-block fragmented input`() {
        val outgoing = ByteArray(32) { it.toByte() }
        val incoming = ByteArray(32) { (it + 64).toByte() }
        val sender = HapChannelCipher(outgoing, incoming)
        val receiver = HapChannelCipher(incoming, outgoing)
        val plaintext = Random(7).nextBytes(2_500)
        val encrypted = sender.encrypt(plaintext)

        val part1 = receiver.decrypt(encrypted.copyOfRange(0, 19))
        val part2 = receiver.decrypt(encrypted.copyOfRange(19, 1_400))
        val part3 = receiver.decrypt(encrypted.copyOfRange(1_400, encrypted.size))
        assertArrayEquals(plaintext, part1 + part2 + part3)
    }

    @Test
    fun `DataStream bplist carries varint framed protobuf messages`() {
        val encoder = AirPlayDataStreamCodec()
        val first = byteArrayOf(0x08, 0x0F)
        val second = byteArrayOf(0x08, 0x2A, 0xAA.toByte())
        val encoded = encoder.encodeSync(1234L, listOf(first, second))
        val decoder = AirPlayDataStreamCodec()

        assertEquals(emptyList<AirPlayDataStreamFrame>(), decoder.feed(encoded.copyOfRange(0, 17)))
        val frame = decoder.feed(encoded.copyOfRange(17, encoded.size)).single()
        assertEquals("sync", frame.messageType)
        assertEquals("comm", frame.command)
        assertEquals(1234L, frame.sequenceNumber)
        val messages = decoder.decodeProtobufMessages(frame)
        assertArrayEquals(first, messages[0])
        assertArrayEquals(second, messages[1])

        val reply = decoder.feed(decoder.encodeReply(1234L)).single()
        assertEquals("rply", reply.messageType)
        assertEquals(1234L, reply.sequenceNumber)
    }
}
