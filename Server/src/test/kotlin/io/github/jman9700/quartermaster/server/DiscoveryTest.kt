package io.github.jman9700.quartermaster.server

import io.github.jman9700.quartermaster.protocol.Api
import io.github.jman9700.quartermaster.protocol.ServerInfo
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveryTest {
    @Test
    fun answersTheDiscoveryRequestWithItsInfo() {
        val info = ServerInfo("Basement server", "2.0.0", "abc1234", port = 47821)
        DiscoveryResponder({ info }, port = 0).use { responder ->
            DatagramSocket().use { socket ->
                socket.soTimeout = 3_000
                val ask = Api.DISCOVERY_REQUEST.toByteArray()
                // Something that isn't the handshake is ignored...
                socket.send(DatagramPacket("hello".toByteArray(), 5, InetAddress.getLoopbackAddress(), responder.boundPort))
                // ...the real request gets an answer.
                socket.send(DatagramPacket(ask, ask.size, InetAddress.getLoopbackAddress(), responder.boundPort))
                val buffer = ByteArray(1024)
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                val parsed = Json.decodeFromString(ServerInfo.serializer(), String(reply.data, 0, reply.length))
                assertEquals(info, parsed)
            }
        }
    }
}
