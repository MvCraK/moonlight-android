package com.limelight.nvstream.av.video;

import java.nio.ByteBuffer;

public abstract class VideoDecoderRenderer {
    public static final int DIRECT_ACQUIRE_FALLBACK = -999;

    public abstract int setup(int format, int width, int height, int redrawRate);

    public abstract void start();

    public abstract void stop();

    // This is called once for each frame-start NALU. This means it will be called several times
    // for an IDR frame which contains several parameter sets and the I-frame data.
    public abstract int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                         int frameNumber, int frameType, char frameHostProcessingLatency,
                                         long receiveTimeMs, long enqueueTimeMs);

    // Optional zero-copy fast path for BUFFER_TYPE_PICDATA: returns a direct ByteBuffer
    // backed by the decoder's native input buffer, or null (check getLastDirectAcquireStatus()).
    public ByteBuffer acquireDirectInputBuffer(int decodeUnitLength, int frameNumber, int frameType,
                                               char frameHostProcessingLatency, long receiveTimeMs,
                                               long enqueueTimeMs) {
        return null;
    }

    public int getLastDirectAcquireStatus() {
        return DIRECT_ACQUIRE_FALLBACK;
    }

    public int commitDirectInputBuffer(int writtenLength) {
        return 0;
    }
    
    public abstract void cleanup();

    public abstract int getCapabilities();

    public abstract void setHdrMode(boolean enabled, byte[] hdrMetadata);
}
