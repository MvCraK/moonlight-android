package com.limelight.ss

import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import javax.net.SocketFactory

object SsSocketFactory : SocketFactory() {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    override fun createSocket(): Socket = object : Socket(Proxy.NO_PROXY) {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val target = endpoint as? InetSocketAddress
            if (target == null || !SsRoutes.routed(target.address)) {
                super.connect(endpoint, timeout)
                return
            }
            if (!isBound) bind(InetSocketAddress(loopback, 0))
            val port = SsNative.openSsTcp(ByteBuffer.wrap(target.address.address).int, target.port, localPort)
            if (port <= 0) throw IOException("SS relay unavailable")
            super.connect(InetSocketAddress(loopback, port), timeout)
        }
    }
    private fun connected(peer: InetSocketAddress, local: InetSocketAddress? = null): Socket {
        val socket = createSocket()
        try {
            if (local != null) socket.bind(local)
            socket.connect(peer)
            return socket
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }
    override fun createSocket(host: String, port: Int) = connected(InetSocketAddress(host, port))
    override fun createSocket(host: InetAddress, port: Int) = connected(InetSocketAddress(host, port))
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) =
        connected(InetSocketAddress(host, port), InetSocketAddress(local, localPort))
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) =
        connected(InetSocketAddress(host, port), InetSocketAddress(local, localPort))
}
