package com.limelight.ss

/** JNI for libvplus_ss_jni.so; kept out of upstream's MoonBridge. */
object SsNative {
    init {
        // Hooks patch libmoonlight-core's imports, so it must be loaded first.
        System.loadLibrary("moonlight-core")
        System.loadLibrary("vplus_ss_jni")
    }

    /** Redirects libmoonlight-core's socket calls; returns hooked slots, or -1 if not found. */
    @JvmStatic external fun installHooks(): Int
    @JvmStatic external fun setSsRoutes(nodes: ByteArray?): Boolean
    @JvmStatic external fun setBypassNetHandle(netHandle: Long)
    @JvmStatic external fun ssReceivedBytes(): Long
    @JvmStatic external fun openSsTcp(ipv4: Int, port: Int, localPort: Int): Int
}
