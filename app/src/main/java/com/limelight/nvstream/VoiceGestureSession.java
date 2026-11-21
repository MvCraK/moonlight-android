// EXTENSION DEVELOPMENT [EXT-REMOTE-VOICE] [ADDED]
package com.limelight.nvstream;

import com.limelight.nvstream.http.HostHttpResponseException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fn and its optional microphone lease. All operations run under the owning NvConnection lock. */
final class VoiceGestureSession {
    private VoiceFnConnection fn;
    private boolean ownsMic;

    boolean begin(NvConnection stream, ConnectionContext context, AtomicBoolean cancelled, Runnable onDisconnect)
            throws IOException, GeneralSecurityException {
        boolean alreadyOn = stream.isMicUplinkActive();
        if (!stream.startMicUplink(cancelled)) return false;
        VoiceFnConnection candidate = new VoiceFnConnection();
        try {
            candidate.open(context.serverAddress.address, context.fnPort, context.fnKey, cancelled);
            candidate.watch(onDisconnect);
            fn = candidate;
            ownsMic = !alreadyOn;
            return true;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            candidate.close();
            if (!alreadyOn) stream.stopMicUplink();
            throw e;
        }
    }

    boolean end(NvConnection stream) {
        boolean acknowledged = fn != null && fn.finish();
        fn = null;
        if (ownsMic) stream.stopMicUplink();
        return acknowledged;
    }

    void close() {
        if (fn != null) fn.close();
        fn = null;
        ownsMic = false;
    }

    static String uplinkError(Exception error) {
        if (error instanceof FileNotFoundException || (error instanceof HostHttpResponseException &&
                ((HostHttpResponseException) error).getErrorCode() == 404)) {
            return "主机未提供麦克风上行接口（/mic-uplink，404）";
        }
        return "mic-uplink unavailable: " + error.getMessage();
    }
}
