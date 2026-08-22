package dev.companionremote.protocol.mrp

import dev.companionremote.protocol.crypto.Crypto
import dev.companionremote.protocol.mrp.proto.ProtocolMessage
import dev.companionremote.protocol.transport.Transport
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

open class MrpException(message: String, cause: Throwable? = null) : Exception(message, cause)
class MrpConnectionClosedException(message: String, cause: Throwable? = null) : MrpException(message, cause)
class MrpCommandException(message: String, val sendError: Int?, val handlerStatus: Int?) : MrpException(message)

data class MrpSessionKeys(val outputKey: ByteArray, val inputKey: ByteArray)

/**
 * Message boundary abstraction shared by direct MRP and a future AirPlay 2
 * DataStream tunnel. It intentionally has no Android dependency.
 */
interface MrpWireTransport {
    suspend fun readMessage(): ByteArray?
    suspend fun writeMessage(message: ByteArray)
    fun enableEncryption(keys: MrpSessionKeys)
    fun close()
}

/**
 * Legacy/direct MRP framing over TCP: protobuf varint length followed by one
 * protobuf message. Encryption, when enabled, covers the complete protobuf.
 */
class DirectMrpWireTransport(private val transport: Transport) : MrpWireTransport {
    private var buffer = ByteArray(0)
    private var cipher: DirectMrpCipher? = null
    private val writeMutex = Mutex()

    override suspend fun readMessage(): ByteArray? {
        while (true) {
            takeFrame()?.let { frame ->
                return cipher?.decrypt(frame) ?: frame
            }
            val chunk = transport.read() ?: return null
            buffer += chunk
        }
    }

    override suspend fun writeMessage(message: ByteArray) {
        writeMutex.withLock {
            val body = cipher?.encrypt(message) ?: message
            transport.write(encodeVarint(body.size.toLong()) + body)
        }
    }

    override fun enableEncryption(keys: MrpSessionKeys) {
        check(cipher == null) { "MRP encryption already enabled" }
        cipher = DirectMrpCipher(keys.outputKey, keys.inputKey)
    }

    override fun close() = transport.close()

    private fun takeFrame(): ByteArray? {
        if (buffer.isEmpty()) return null
        var length = 0L
        var shift = 0
        var prefixLength = 0
        while (prefixLength < buffer.size && prefixLength < 10) {
            val byte = buffer[prefixLength].toInt() and 0xFF
            length = length or ((byte and 0x7F).toLong() shl shift)
            prefixLength += 1
            if (byte and 0x80 == 0) break
            shift += 7
        }
        if (prefixLength == buffer.size && buffer[prefixLength - 1].toInt() and 0x80 != 0) return null
        if (prefixLength == 10 && buffer[prefixLength - 1].toInt() and 0x80 != 0) {
            throw MrpException("MRP frame length varint is too long")
        }
        if (length > MAX_MRP_MESSAGE_BYTES) throw MrpException("MRP message too large: $length")
        val total = prefixLength + length.toInt()
        if (buffer.size < total) return null
        val frame = buffer.copyOfRange(prefixLength, total)
        buffer = buffer.copyOfRange(total, buffer.size)
        return frame
    }

    private companion object {
        const val MAX_MRP_MESSAGE_BYTES = 16L * 1024L * 1024L
    }
}

/** Whole-message ChaCha20-Poly1305 used only by direct/legacy MRP. */
internal class DirectMrpCipher(private val outputKey: ByteArray, private val inputKey: ByteArray) {
    private var outputCounter = 0L
    private var inputCounter = 0L

    fun encrypt(message: ByteArray): ByteArray = Crypto.chaChaEncrypt(
        outputKey,
        counterNonce(outputCounter++),
        message,
    )

    fun decrypt(message: ByteArray): ByteArray = Crypto.chaChaDecrypt(
        inputKey,
        counterNonce(inputCounter++),
        message,
    )

    private fun counterNonce(counter: Long): ByteArray {
        // pyatv Chacha20Cipher8byteNonce: 4 zero bytes || uint64 little-endian.
        val nonce = ByteArray(12)
        for (index in 0 until 8) {
            nonce[4 + index] = ((counter ushr (8 * index)) and 0xFF).toByte()
        }
        return nonce
    }
}

/** Request correlation and push dispatch above an [MrpWireTransport]. */
class MrpConnection(
    private val wire: MrpWireTransport,
    private val defaultTimeoutMs: Long = 5_000,
) {
    private val scope = CoroutineScope(Job() + Dispatchers.Default + CoroutineName("mrp-connection"))
    private val waiters = mutableMapOf<String, CompletableDeferred<ProtocolMessage>>()
    private val lock = Any()
    private var started = false
    private var closed = false
    private var closeCause: Throwable? = null

    private val _messages = MutableSharedFlow<ProtocolMessage>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<ProtocolMessage> = _messages

    private val reader = scope.launch(start = CoroutineStart.LAZY) {
        try {
            while (true) {
                val raw = wire.readMessage()
                    ?: throw MrpConnectionClosedException("MRP connection closed by device")
                handle(MrpMessages.parse(raw))
            }
        } catch (error: Throwable) {
            shutdown(error)
        }
    }

    fun start() {
        check(!started) { "MRP connection already started" }
        started = true
        reader.start()
    }

    fun enableEncryption(keys: MrpSessionKeys) = wire.enableEncryption(keys)

    suspend fun send(message: ProtocolMessage) {
        ensureOpen()
        wire.writeMessage(message.toByteArray())
    }

    suspend fun sendAndReceive(
        message: ProtocolMessage,
        generateIdentifier: Boolean = true,
        timeoutMs: Long = defaultTimeoutMs,
    ): ProtocolMessage {
        ensureOpen()
        val outgoing: ProtocolMessage
        val key: String
        if (generateIdentifier) {
            val identifier = UUID.randomUUID().toString().uppercase()
            outgoing = MrpMessages.withIdentifier(message, identifier)
            key = identifier
        } else {
            outgoing = message
            key = typeKey(message.type)
        }

        val deferred = CompletableDeferred<ProtocolMessage>()
        synchronized(lock) {
            closeCause?.let { throw MrpConnectionClosedException("MRP connection closed", it) }
            check(key !in waiters) { "request already pending for $key" }
            waiters[key] = deferred
        }
        try {
            send(outgoing)
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            synchronized(lock) { waiters.remove(key) }
        }
    }

    private fun handle(message: ProtocolMessage) {
        val key = if (message.hasIdentifier() && message.identifier.isNotEmpty()) {
            message.identifier
        } else {
            typeKey(message.type)
        }
        val waiter = synchronized(lock) { waiters.remove(key) }
        if (waiter != null) waiter.complete(message) else _messages.tryEmit(message)
    }

    private fun typeKey(type: ProtocolMessage.Type): String = "type_${type.number}"

    private fun ensureOpen() {
        check(started) { "call start() before using MRP connection" }
        closeCause?.let { throw MrpConnectionClosedException("MRP connection closed", it) }
        if (closed) throw MrpConnectionClosedException("MRP connection closed")
    }

    private fun shutdown(cause: Throwable) {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            closeCause = cause
            waiters.values.toList().also { waiters.clear() }
        }
        pending.forEach { it.completeExceptionally(MrpConnectionClosedException("MRP connection closed", cause)) }
        wire.close()
    }

    fun close() {
        shutdown(MrpConnectionClosedException("closed by client"))
        scope.cancel()
    }
}
