// EXTENSION DEVELOPMENT [EXT-SS-NETWORK] [ADDED]
package com.limelight.extensions.ss;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import javax.net.SocketFactory;

/**
 * TCP sockets for Java callers (pairing/HTTP, address probing, voice Fn).
 * Routed destinations connect to a one-shot loopback relay that forwards
 * through the SS node; everything else connects directly.
 */
public final class SsSocketFactory extends SocketFactory {
    public static final SsSocketFactory INSTANCE = new SsSocketFactory();
    // The native relay listens on IPv4 loopback; getLoopbackAddress() may return ::1.
    private static final InetAddress LOOPBACK = ipv4Loopback();

    private static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByAddress("localhost", new byte[]{127, 0, 0, 1});
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private SsSocketFactory() {}

    private static final class RoutedSocket extends Socket {
        RoutedSocket() {
            super(Proxy.NO_PROXY);
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            InetSocketAddress target = endpoint instanceof InetSocketAddress ? (InetSocketAddress) endpoint : null;
            if (target == null || !SsRoutes.routed(target.getAddress())) {
                super.connect(endpoint, timeout);
                return;
            }
            if (!isBound()) bind(new InetSocketAddress(LOOPBACK, 0));
            int port = SsNative.openSsTcp(ByteBuffer.wrap(target.getAddress().getAddress()).getInt(),
                    target.getPort(), getLocalPort());
            if (port <= 0) throw new IOException("SS relay unavailable");
            super.connect(new InetSocketAddress(LOOPBACK, port), timeout);
        }
    }

    @Override
    public Socket createSocket() {
        return new RoutedSocket();
    }

    private Socket connected(InetSocketAddress peer, InetSocketAddress local) throws IOException {
        Socket socket = createSocket();
        try {
            if (local != null) socket.bind(local);
            socket.connect(peer);
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return connected(new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return connected(new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return connected(new InetSocketAddress(host, port), new InetSocketAddress(localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress localHost, int localPort) throws IOException {
        return connected(new InetSocketAddress(host, port), new InetSocketAddress(localHost, localPort));
    }
}
