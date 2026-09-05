package dev.dertyp.services.hue

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.time.Duration

object MdnsQuery {
    private const val MDNS_PORT = 5353
    private const val QU_BIT = 0x8000
    private const val TYPE_PTR = 12
    private const val CLASS_IN = 1
    private val MDNS_GROUP: InetAddress = InetAddress.getByName("224.0.0.251")

    fun buildQuery(serviceType: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(value: Int) {
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }
        u16(0)
        u16(0)
        u16(1)
        u16(0)
        u16(0)
        u16(0)
        serviceType.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        u16(TYPE_PTR)
        u16(CLASS_IN or QU_BIT)
        return out.toByteArray()
    }

    fun isResponse(packet: ByteArray): Boolean =
        packet.size >= 12 && (packet[2].toInt() and 0x80) != 0

    fun responders(serviceType: String, timeout: Duration): Set<String> {
        val query = buildQuery(serviceType)
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
                .filter { iface -> iface.inetAddresses.toList().any { it is Inet4Address } }
        }.getOrDefault(emptyList())
        val found = LinkedHashSet<String>()
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        for (iface in interfaces) {
            runCatching {
                MulticastSocket(0).use { socket ->
                    socket.networkInterface = iface
                    socket.timeToLive = 255
                    socket.send(DatagramPacket(query, query.size, MDNS_GROUP, MDNS_PORT))
                    val buffer = ByteArray(9000)
                    while (true) {
                        val remaining = deadline - System.currentTimeMillis()
                        if (remaining <= 0) break
                        socket.soTimeout = remaining.toInt().coerceAtMost(1000)
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        if (isResponse(packet.data.copyOf(packet.length))) {
                            (packet.address as? Inet4Address)?.hostAddress?.let(found::add)
                        }
                    }
                }
            }
        }
        return found
    }
}
