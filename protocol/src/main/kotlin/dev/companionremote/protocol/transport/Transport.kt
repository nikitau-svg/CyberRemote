package dev.companionremote.protocol.transport

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal byte transport used by the protocol layer. All I/O in `:protocol`
 * goes through this interface so tests can drive the stack with recorded
 * byte streams.
 */
interface Transport {
    /** Read up to some transport-defined amount of bytes; null on EOF. */
    suspend fun read(): ByteArray?

    /** Write all of [data]. */
    suspend fun write(data: ByteArray)

    fun close()
}

/** TCP socket transport. */
class SocketTransport private constructor(private val socket: Socket) : Transport {

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val buffer = ByteArray(8192)

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        // EOF and an I/O failure are deliberately different here. The
        // connection layer needs the original exception to report a useful
        // termination cause and fail in-flight exchanges immediately.
        val n = input.read(buffer)
        if (n < 0) null else buffer.copyOf(n)
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        output.write(data)
        output.flush()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        suspend fun connect(
            host: String,
            port: Int,
            timeoutMs: Int = 10_000,
            socketFactory: SocketFactory = SocketFactory.getDefault(),
        ): SocketTransport =
            withContext(Dispatchers.IO) {
                // Android can pass Network.socketFactory here so the TCP
                // socket stays on the selected network. JVM/CLI callers keep
                // using the platform default.
                val socket = socketFactory.createSocket()
                try {
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    SocketTransport(socket)
                } catch (cause: Throwable) {
                    runCatching { socket.close() }
                    throw cause
                }
            }
    }
}
