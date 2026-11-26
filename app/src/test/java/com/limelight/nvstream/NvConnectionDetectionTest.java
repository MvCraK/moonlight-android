package com.limelight.nvstream;

import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NvConnectionDetectionTest {
    private static final int LAN_MAX_PACKET_SIZE = 1392;

    @Test
    public void classifiesRfc1918AndIpv6UlaOnlyAndExcludesLoopback() throws Exception {
        assertTrue(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("10.0.0.4")));
        assertTrue(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("10.255.255.254")));
        assertTrue(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("172.16.0.1")));
        assertTrue(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("172.31.255.254")));
        assertTrue(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("192.168.0.1")));
        assertTrue(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("192.168.255.254")));
        assertTrue(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("fc00::1")));
        assertTrue(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("fd12:3456:789a::4")));

        assertFalse(NvConnection.isPrivateNetworkAddress(null));
        assertFalse(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("::1")));
        assertFalse(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("172.15.255.254")));
        assertFalse(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("172.32.0.1")));
        assertFalse(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("169.254.1.1")));
        assertFalse(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("100.64.0.1")));
        assertFalse(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("1.1.1.1")));
        assertFalse(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("203.0.113.4")));
        assertFalse(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("fe80::1")));
        assertFalse(NvConnection.isPrivateNetworkAddress(InetAddress.getByName("240e::1")));
    }

    @Test
    public void crossSubnetPrivateViaGatewayUsesRemoteAnd1280() throws Exception {
        InetAddress homeHost = InetAddress.getByName("10.0.0.4");
        NvConnection.DetectedConnection result = NvConnection.resolveDetectedConnection(
                false, true, false, homeHost, false, true, false, LAN_MAX_PACKET_SIZE);

        assertEquals(StreamConfiguration.STREAM_CFG_REMOTE, result.remoteStreaming);
        assertEquals(1280, result.packetSize);
        assertEquals(0, result.packetSize % 16);
        assertTrue(result.crossSubnetPrivate);
    }

    @Test
    public void crossSubnetIpv6UlaViaGatewayUsesRemoteAnd1280() throws Exception {
        InetAddress homeUla = InetAddress.getByName("fd00:10::4");
        NvConnection.DetectedConnection result = NvConnection.resolveDetectedConnection(
                false, true, false, homeUla, false, true, false, LAN_MAX_PACKET_SIZE);

        assertEquals(StreamConfiguration.STREAM_CFG_REMOTE, result.remoteStreaming);
        assertEquals(1280, result.packetSize);
        assertTrue(result.crossSubnetPrivate);
    }

    @Test
    public void crossSubnetRespectsSmallerCallerMaxPacketSize() throws Exception {
        InetAddress homeHost = InetAddress.getByName("10.0.0.4");
        NvConnection.DetectedConnection result = NvConnection.resolveDetectedConnection(
                false, true, false, homeHost, false, true, false, 1024);

        assertEquals(StreamConfiguration.STREAM_CFG_REMOTE, result.remoteStreaming);
        assertEquals(1024, result.packetSize);
        assertTrue(result.crossSubnetPrivate);
    }

    @Test
    public void sameSubnetOnLinkStaysLocalAnd1392EvenWhenDefaultGatewayAlsoMatched() throws Exception {
        InetAddress lanHost = InetAddress.getByName("10.1.1.5");
        NvConnection.DetectedConnection result = NvConnection.resolveDetectedConnection(
                false, true, false, lanHost, true, true, false, LAN_MAX_PACKET_SIZE);

        assertEquals(StreamConfiguration.STREAM_CFG_LOCAL, result.remoteStreaming);
        assertEquals(LAN_MAX_PACKET_SIZE, result.packetSize);
        assertFalse(result.crossSubnetPrivate);
    }

    @Test
    public void loopbackStaysLocalAnd1392() throws Exception {
        for (String addr : new String[]{"127.0.0.1", "::1"}) {
            NvConnection.DetectedConnection result = NvConnection.resolveDetectedConnection(
                    false, true, false, InetAddress.getByName(addr), false, true, false, LAN_MAX_PACKET_SIZE);

            assertEquals(StreamConfiguration.STREAM_CFG_LOCAL, result.remoteStreaming);
            assertEquals(LAN_MAX_PACKET_SIZE, result.packetSize);
            assertFalse(result.crossSubnetPrivate);
        }
    }

    @Test
    public void ssRoutedCrossSubnetDefersToSsRoutesTuneStream() throws Exception {
        InetAddress homeHost = InetAddress.getByName("10.0.0.4");
        NvConnection.DetectedConnection result = NvConnection.resolveDetectedConnection(
                false, true, false, homeHost, false, true, true, LAN_MAX_PACKET_SIZE);

        assertEquals(StreamConfiguration.STREAM_CFG_AUTO, result.remoteStreaming);
        assertEquals(LAN_MAX_PACKET_SIZE, result.packetSize);
        assertFalse(result.crossSubnetPrivate);
    }

    @Test
    public void vpnCellularAndNat64StayRemote1024() throws Exception {
        InetAddress homeHost = InetAddress.getByName("10.0.0.4");

        NvConnection.DetectedConnection vpnOrCell = NvConnection.resolveDetectedConnection(
                true, false, false, homeHost, false, true, false, LAN_MAX_PACKET_SIZE);
        assertEquals(StreamConfiguration.STREAM_CFG_REMOTE, vpnOrCell.remoteStreaming);
        assertEquals(1024, vpnOrCell.packetSize);
        assertFalse(vpnOrCell.crossSubnetPrivate);

        NvConnection.DetectedConnection nat64 = NvConnection.resolveDetectedConnection(
                false, true, true, InetAddress.getByName("fd00:64:ff9b::10.0.0.4"),
                false, true, false, LAN_MAX_PACKET_SIZE);
        assertEquals(StreamConfiguration.STREAM_CFG_REMOTE, nat64.remoteStreaming);
        assertEquals(1024, nat64.packetSize);
        assertFalse(nat64.crossSubnetPrivate);
    }

    @Test
    public void publicAddressAndMissingCapsOrGatewayFallBackToAuto() throws Exception {
        NvConnection.DetectedConnection publicV4 = NvConnection.resolveDetectedConnection(
                false, true, false, InetAddress.getByName("203.0.113.4"),
                false, true, false, LAN_MAX_PACKET_SIZE);
        assertEquals(StreamConfiguration.STREAM_CFG_AUTO, publicV4.remoteStreaming);
        assertEquals(LAN_MAX_PACKET_SIZE, publicV4.packetSize);
        assertFalse(publicV4.crossSubnetPrivate);

        NvConnection.DetectedConnection noCaps = NvConnection.resolveDetectedConnection(
                false, false, false, InetAddress.getByName("10.0.0.4"),
                false, true, false, LAN_MAX_PACKET_SIZE);
        assertEquals(StreamConfiguration.STREAM_CFG_AUTO, noCaps.remoteStreaming);
        assertFalse(noCaps.crossSubnetPrivate);

        NvConnection.DetectedConnection noGatewayRoute = NvConnection.resolveDetectedConnection(
                false, true, false, InetAddress.getByName("10.0.0.4"),
                false, false, false, LAN_MAX_PACKET_SIZE);
        assertEquals(StreamConfiguration.STREAM_CFG_AUTO, noGatewayRoute.remoteStreaming);
        assertFalse(noGatewayRoute.crossSubnetPrivate);
    }
}
