// EXTENSION DEVELOPMENT [EXT-SS-NETWORK] [ADDED]
package com.limelight.extensions.ss;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;

/**
 * DatagramSocket for Java UDP callers (StarCore mic uplink). When connected to a
 * routed host it talks to the SS node instead, encrypting each datagram for the
 * original destination and decrypting replies, so callers see the host directly.
 */
public final class SsDatagramSocket extends DatagramSocket {
    private InetSocketAddress target;   // original destination while tunnelled
    private InetSocketAddress server;
    private long node;
    private final byte[] scratch = new byte[65536];

    private SsDatagramSocket() throws SocketException {
        super();
    }

    public static DatagramSocket create() throws SocketException {
        return new SsDatagramSocket();
    }

    private synchronized boolean tunnel(InetAddress address, int port) {
        if (!SsRoutes.routed(address) || !(address instanceof Inet4Address)) return false;
        int[] serverInfo = new int[2];
        long acquired = SsNative.udpAcquire(ByteBuffer.wrap(address.getAddress()).getInt(), serverInfo);
        if (acquired == 0) return false;
        SsRoutes.bindSocketToUnderlyingNetwork(this);
        releaseNode();
        node = acquired;
        target = new InetSocketAddress(address, port);
        server = new InetSocketAddress(ipv4(serverInfo[0]), serverInfo[1]);
        return true;
    }

    private static InetAddress ipv4(int ip) {
        try {
            return InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(ip).array());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void connect(InetAddress address, int port) {
        if (tunnel(address, port)) super.connect(server.getAddress(), server.getPort());
        else super.connect(address, port);
    }

    @Override
    public void connect(SocketAddress address) throws SocketException {
        if (address instanceof InetSocketAddress) {
            InetSocketAddress a = (InetSocketAddress) address;
            if (tunnel(a.getAddress(), a.getPort())) {
                super.connect(server);
                return;
            }
        }
        super.connect(address);
    }

    @Override
    public void send(DatagramPacket packet) throws IOException {
        InetSocketAddress destination = target;
        if (destination == null) {
            super.send(packet);
            return;
        }
        byte[] encrypted = SsNative.udpEncrypt(node,
                ByteBuffer.wrap(destination.getAddress().getAddress()).getInt(), destination.getPort(),
                packet.getData(), packet.getOffset(), packet.getLength());
        if (encrypted == null) throw new IOException("SS encrypt failed");
        // Connected to the SS node, so the packet carries no address of its own.
        super.send(new DatagramPacket(encrypted, encrypted.length));
    }

    @Override
    public synchronized void receive(DatagramPacket packet) throws IOException {
        if (target == null) {
            super.receive(packet);
            return;
        }
        int[] source = new int[2];
        while (true) {
            DatagramPacket raw = new DatagramPacket(scratch, scratch.length);
            super.receive(raw);   // honours SO_TIMEOUT
            int length = SsNative.udpDecrypt(node, scratch, raw.getLength(), source);
            if (length < 0) continue;   // not a valid SS reply: drop it
            int copy = Math.min(length, packet.getData().length - packet.getOffset());
            System.arraycopy(scratch, 0, packet.getData(), packet.getOffset(), copy);
            packet.setLength(copy);
            packet.setSocketAddress(new InetSocketAddress(ipv4(source[0]), source[1]));
            return;
        }
    }

    private synchronized void releaseNode() {
        if (node != 0) {
            SsNative.udpRelease(node);
            node = 0;
        }
    }

    @Override
    public void close() {
        super.close();
        releaseNode();
    }
}
