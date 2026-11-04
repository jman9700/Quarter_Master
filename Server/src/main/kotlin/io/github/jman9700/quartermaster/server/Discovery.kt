package io.github.jman9700.quartermaster.server

import io.github.jman9700.quartermaster.protocol.Api
import io.github.jman9700.quartermaster.protocol.ServerInfo
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Answers LAN discovery broadcasts: the app sends [Api.DISCOVERY_REQUEST] to UDP [Api.DISCOVERY_PORT],
 * and every server on the network replies with its [ServerInfo].
 *
 * Under Docker Desktop (Windows/macOS) broadcasts don't reach containers, so there the app's
 * "connect by address" is the way in. Running the server directly, or Docker on Linux with
 * host networking, discovery works.
 */
class DiscoveryResponder(
    private val info: () -> ServerInfo,
    private val port: Int = Api.DISCOVERY_PORT,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(DiscoveryResponder::class.java)
    private val json = Json { encodeDefaults = true }
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        broadcast = true
        // Qualified: inside apply, a bare `port` would be the socket's own (unset) remote port.
        bind(InetSocketAddress(this@DiscoveryResponder.port))
    }

    val boundPort: Int get() = socket.localPort

    private val thread = Thread({ loop() }, "discovery").apply {
        isDaemon = true
        start()
    }

    private fun loop() {
        val buffer = ByteArray(512)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                if (text != Api.DISCOVERY_REQUEST) continue
                val reply = json.encodeToString(ServerInfo.serializer(), info()).toByteArray()
                socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
            } catch (e: SocketException) {
                if (!socket.isClosed) log.warn("Discovery socket error: {}", e.message)
            } catch (e: Exception) {
                log.warn("Ignoring a bad discovery packet: {}", e.message)
            }
        }
    }

    override fun close() {
        socket.close()
        thread.join(1_000)
    }
}
