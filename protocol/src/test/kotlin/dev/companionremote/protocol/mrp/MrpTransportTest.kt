package dev.companionremote.protocol.mrp

import dev.companionremote.protocol.mrp.proto.Command
import dev.companionremote.protocol.mrp.proto.ProtocolMessage
import dev.companionremote.protocol.transport.Transport
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MrpTransportTest {
    @Test
    fun `direct framing accepts split varint and payload`() = runBlocking {
        val raw = FakeByteTransport()
        val wire = DirectMrpWireTransport(raw)
        val message = ByteArray(300) { (it and 0xFF).toByte() }
        raw.feed((encodeVarint(message.size.toLong()) + message).copyOfRange(0, 1))
        raw.feed((encodeVarint(message.size.toLong()) + message).copyOfRange(1, 91))
        raw.feed((encodeVarint(message.size.toLong()) + message).copyOfRange(91, 302))

        assertArrayEquals(message, wire.readMessage())
    }

    @Test
    fun `direct MRP cipher has independent direction counters`() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (it + 32).toByte() }
        val client = DirectMrpCipher(a, b)
        val server = DirectMrpCipher(b, a)

        assertArrayEquals("one".toByteArray(), server.decrypt(client.encrypt("one".toByteArray())))
        assertArrayEquals("two".toByteArray(), server.decrypt(client.encrypt("two".toByteArray())))
        assertArrayEquals("back".toByteArray(), client.decrypt(server.encrypt("back".toByteArray())))
    }

    @Test
    fun `connection correlates response and emits unrelated push`() = runBlocking {
        val wire = FakeMrpWireTransport()
        val connection = MrpConnection(wire)
        connection.start()

        val response = async { connection.sendAndReceive(MrpMessages.command(Command.Pause)) }
        val request = MrpMessages.parse(withTimeout(2_000) { wire.writes.receive() })
        val push = ProtocolMessage.newBuilder()
            .setType(ProtocolMessage.Type.GENERIC_MESSAGE)
            .build()
        val pushed = async(start = CoroutineStart.UNDISPATCHED) { connection.messages.first() }
        wire.respond(push)
        wire.respond(
            ProtocolMessage.newBuilder()
                .setType(ProtocolMessage.Type.SEND_COMMAND_RESULT_MESSAGE)
                .setIdentifier(request.identifier)
                .build(),
        )

        assertEquals(ProtocolMessage.Type.GENERIC_MESSAGE, withTimeout(2_000) { pushed.await() }.type)
        assertEquals(request.identifier, withTimeout(2_000) { response.await() }.identifier)
        connection.close()
    }
}

private class FakeByteTransport : Transport {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    override suspend fun read(): ByteArray? = incoming.receiveCatching().getOrNull()
    override suspend fun write(data: ByteArray) = Unit
    override fun close() {
        incoming.close()
    }
    fun feed(data: ByteArray) { incoming.trySend(data) }
}

internal class FakeMrpWireTransport : MrpWireTransport {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    val writes = Channel<ByteArray>(Channel.UNLIMITED)
    var keys: MrpSessionKeys? = null

    override suspend fun readMessage(): ByteArray? = incoming.receiveCatching().getOrNull()
    override suspend fun writeMessage(message: ByteArray) { writes.send(message) }
    override fun enableEncryption(keys: MrpSessionKeys) { this.keys = keys }
    override fun close() {
        incoming.close()
    }
    fun respond(message: ProtocolMessage) { incoming.trySend(message.toByteArray()) }
}
