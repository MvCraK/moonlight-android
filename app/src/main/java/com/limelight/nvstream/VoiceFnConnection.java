// EXTENSION DEVELOPMENT [EXT-REMOTE-VOICE] [ADDED]
package com.limelight.nvstream;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** An authenticated TCP connection owns exactly one native Fn gesture until closed. */
final class VoiceFnConnection {
    private final Socket socket = new Socket();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CountDownLatch peerEnded = new CountDownLatch(1);
    private boolean watching;
    private volatile boolean acknowledged;
    void open(String address, int port, byte[] key, AtomicBoolean cancelled) throws IOException, GeneralSecurityException {
        if (port <= 0 || port > 65535 || key == null || key.length != 32)
            throw new IOException("请启动支持 Fn 的 StarCore Pro 构建 19 或更新版");
        socket.connect(new InetSocketAddress(address, port), 2500);
        socket.setSoTimeout(2500);
        socket.setTcpNoDelay(true);
        DataInputStream input = new DataInputStream(socket.getInputStream());
        byte[] challenge = new byte[17];
        input.readFully(challenge, 0, 16);
        challenge[16] = 2; // Toggle Fn once on open and once on finish.
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] response = new byte[33];
        response[0] = challenge[16];
        System.arraycopy(mac.doFinal(challenge), 0, response, 1, 32);
        if (cancelled.get()) throw new IOException("已取消");
        socket.getOutputStream().write(response);
        int ack = input.read();
        if (ack == 2) throw new IOException("请在 Mac 系统设置的「隐私与安全性 → 辅助功能」允许 StarCore Pro");
        if (ack != 1) throw new IOException("Mac 未确认 Fn 操作");
    }
    // The host closes this socket when its audio receiver expires, even on an idle UI.
    void watch(Runnable onDisconnect) throws IOException {
        socket.setSoTimeout(0);
        Thread reader = new Thread(() -> {
            try {
                acknowledged = socket.getInputStream().read() == 1;
            } catch (IOException ignored) {
            } finally {
                peerEnded.countDown();
                if (!closing.get()) onDisconnect.run();
            }
        }, "VoiceFnStatus");
        reader.setDaemon(true);
        reader.start();
        watching = true;
    }
    void close() {
        closing.set(true);
        try { socket.close(); } catch (IOException ignored) {}
    }
    boolean finish() {
        closing.set(true);
        try {
            OutputStream output = socket.getOutputStream();
            output.write(0);
            if (watching) peerEnded.await(2500, TimeUnit.MILLISECONDS);
            else acknowledged = socket.getInputStream().read() == 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The host also ends the gesture on EOF or audio-session expiry.
        } finally { close(); }
        return acknowledged;
    }
}
