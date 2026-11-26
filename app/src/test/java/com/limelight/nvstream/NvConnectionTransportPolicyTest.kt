package com.limelight.nvstream

import com.limelight.nvstream.NvConnection.Companion.CROSS_SUBNET_PRIVATE_PACKET_SIZE
import com.limelight.nvstream.NvConnection.Companion.MatchedRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NvConnectionTransportPolicyTest {

    @Test
    fun privateRoutableAddressMatchesRfc1918AndIpv6UlaOnly() {
        // RFC1918 IPv4
        assertTrue(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("10.0.0.4")))
        assertTrue(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("10.255.255.254")))
        assertTrue(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("172.16.0.1")))
        assertTrue(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("172.31.255.254")))
        assertTrue(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("192.168.0.1")))
        assertTrue(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("192.168.255.254")))

        // IPv6 ULA (fc00::/7)
        assertTrue(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("fc00::1")))
        assertTrue(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("fd12:3456:789a::1")))

        // Excluded: loopback, link-local, CGNAT, RFC1918 boundaries, public IPv4/IPv6
        assertFalse(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("::1")))
        assertFalse(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("169.254.1.10")))
        assertFalse(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("fe80::1")))
        assertFalse(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("fec0::1")))
        assertFalse(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("100.64.0.1")))
        assertFalse(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("172.15.255.255")))
        assertFalse(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("172.32.0.1")))
        assertFalse(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("192.167.1.1")))
        assertFalse(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("192.169.1.1")))
        assertFalse(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(NvConnection.isPrivateRoutableAddress(InetAddress.getByName("2001:db8::1")))
    }

    @Test
    fun crossSubnetPrivateIpv4UsesRemoteAnd1280() {
        val result = NvConnection.resolveRouteTransport(
            serverAddress = InetAddress.getByName("10.0.0.4"),
            matchedRoutes = listOf(MatchedRoute(prefixLength = 0, hasGateway = true)),
            maxPacketSize = 1392,
            routedByBuiltInSs = false,
        )

        assertEquals(StreamConfiguration.STREAM_CFG_REMOTE, result.remoteStreaming)
        assertEquals(CROSS_SUBNET_PRIVATE_PACKET_SIZE, result.packetSize)
        assertEquals(0, result.packetSize % 16)
        assertTrue(result.crossSubnetPrivate)
    }

    @Test
    fun crossSubnetPrivateIpv6UlaUsesRemoteAnd1280() {
        val result = NvConnection.resolveRouteTransport(
            serverAddress = InetAddress.getByName("fd00:2::4"),
            matchedRoutes = listOf(MatchedRoute(prefixLength = 0, hasGateway = true)),
            maxPacketSize = 1392,
            routedByBuiltInSs = false,
        )

        assertEquals(StreamConfiguration.STREAM_CFG_REMOTE, result.remoteStreaming)
        assertEquals(1280, result.packetSize)
        assertTrue(result.crossSubnetPrivate)
    }

    @Test
    fun crossSubnetPrivateRespectsSmallerMaxPacketSize() {
        val result = NvConnection.resolveRouteTransport(
            serverAddress = InetAddress.getByName("10.0.0.4"),
            matchedRoutes = listOf(MatchedRoute(prefixLength = 24, hasGateway = true)),
            maxPacketSize = 1024,
            routedByBuiltInSs = false,
        )

        assertEquals(StreamConfiguration.STREAM_CFG_REMOTE, result.remoteStreaming)
        assertEquals(1024, result.packetSize)
        assertTrue(result.crossSubnetPrivate)
    }

    @Test
    fun sameSubnetOnLinkBeatsDefaultGatewayRouteRegardlessOfOrder() {
        val host = InetAddress.getByName("10.1.1.20")
        val defaultFirst = NvConnection.resolveRouteTransport(
            serverAddress = host,
            matchedRoutes = listOf(
                MatchedRoute(prefixLength = 0, hasGateway = true),
                MatchedRoute(prefixLength = 24, hasGateway = false),
            ),
            maxPacketSize = 1392,
            routedByBuiltInSs = false,
        )
        val onLinkFirst = NvConnection.resolveRouteTransport(
            serverAddress = host,
            matchedRoutes = listOf(
                MatchedRoute(prefixLength = 24, hasGateway = false),
                MatchedRoute(prefixLength = 0, hasGateway = true),
            ),
            maxPacketSize = 1392,
            routedByBuiltInSs = false,
        )

        for (result in listOf(defaultFirst, onLinkFirst)) {
            assertEquals(StreamConfiguration.STREAM_CFG_LOCAL, result.remoteStreaming)
            assertEquals(1392, result.packetSize)
            assertFalse(result.crossSubnetPrivate)
        }
    }

    @Test
    fun loopbackRemainsLocal1392EvenWhenDefaultGatewayRouteMatches() {
        for (ip in listOf("127.0.0.1", "::1")) {
            val result = NvConnection.resolveRouteTransport(
                serverAddress = InetAddress.getByName(ip),
                matchedRoutes = listOf(MatchedRoute(prefixLength = 0, hasGateway = true)),
                maxPacketSize = 1392,
                routedByBuiltInSs = false,
            )

            assertEquals(StreamConfiguration.STREAM_CFG_LOCAL, result.remoteStreaming)
            assertEquals(1392, result.packetSize)
            assertFalse(result.crossSubnetPrivate)
        }
    }

    @Test
    fun builtInSsRoutedHostSkipsCrossSubnet1280Branch() {
        val result = NvConnection.resolveRouteTransport(
            serverAddress = InetAddress.getByName("10.0.0.4"),
            matchedRoutes = listOf(MatchedRoute(prefixLength = 0, hasGateway = true)),
            maxPacketSize = 1392,
            routedByBuiltInSs = true,
        )

        assertEquals(StreamConfiguration.STREAM_CFG_AUTO, result.remoteStreaming)
        assertEquals(1392, result.packetSize)
        assertFalse(result.crossSubnetPrivate)
    }

    @Test
    fun publicHostFallsThroughToAutoForNativeCap() {
        for (ip in listOf("8.8.8.8", "2001:db8::1")) {
            val result = NvConnection.resolveRouteTransport(
                serverAddress = InetAddress.getByName(ip),
                matchedRoutes = listOf(MatchedRoute(prefixLength = 0, hasGateway = true)),
                maxPacketSize = 1392,
                routedByBuiltInSs = false,
            )

            assertEquals(StreamConfiguration.STREAM_CFG_AUTO, result.remoteStreaming)
            assertEquals(1392, result.packetSize)
            assertFalse(result.crossSubnetPrivate)
        }
    }

    @Test
    fun emptyMatchedRoutesFallsThroughToAuto() {
        val result = NvConnection.resolveRouteTransport(
            serverAddress = InetAddress.getByName("10.0.0.4"),
            matchedRoutes = emptyList(),
            maxPacketSize = 1392,
            routedByBuiltInSs = false,
        )

        assertEquals(StreamConfiguration.STREAM_CFG_AUTO, result.remoteStreaming)
        assertEquals(1392, result.packetSize)
        assertFalse(result.crossSubnetPrivate)
    }
}
