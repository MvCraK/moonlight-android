// EXTENSION DEVELOPMENT [EXT-SS-NETWORK] [ADDED]
package com.limelight.extensions.ss;

/** JNI for libss_network_jni.so; kept out of upstream's MoonBridge. */
final class SsNative {
    static {
        // The hooks patch libmoonlight-core's imports, so it must be loaded first.
        System.loadLibrary("moonlight-core");
        System.loadLibrary("ss_network_jni");
    }

    private SsNative() {}

    /** Redirects libmoonlight-core's socket calls; returns hooked slots, or -1 if not found. */
    static native int installHooks();
    static native boolean setSsRoutes(byte[] nodes);
    static native void setBypassNetHandle(long netHandle);
    static native long ssReceivedBytes();
    static native int openSsTcp(int ipv4, int port, int localPort);

    // Java DatagramSocket support (mic uplink).
    static native long udpAcquire(int ipv4, int[] server);
    static native void udpRelease(long node);
    static native byte[] udpEncrypt(long node, int ipv4, int port, byte[] data, int offset, int length);
    static native int udpDecrypt(long node, byte[] data, int length, int[] source);

}
