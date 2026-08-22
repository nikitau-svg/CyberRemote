package dev.companionremote.protocol.transport

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SocketTransportTest {

    @Test
    fun `connect creates the socket through supplied factory`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val accepted = async(Dispatchers.IO) { server.accept() }
            val factory = RecordingSocketFactory()

            val transport = SocketTransport.connect(
                host = InetAddress.getLoopbackAddress().hostAddress,
                port = server.localPort,
                socketFactory = factory,
            )
            val peer = withTimeout(2_000) { accepted.await() }

            assertEquals(1, factory.unconnectedSocketCount.get())
            peer.close()
            transport.close()
        }
    }

    private class RecordingSocketFactory : SocketFactory() {
        private val delegate = getDefault()
        val unconnectedSocketCount = AtomicInteger()

        override fun createSocket(): Socket {
            unconnectedSocketCount.incrementAndGet()
            return delegate.createSocket()
        }

        override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port)

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = delegate.createSocket(host, port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port)

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = delegate.createSocket(address, port, localAddress, localPort)
    }
}
