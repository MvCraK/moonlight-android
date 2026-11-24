// Wrapper around upstream moonlight-common-c/src/RtpVideoQueue.c so the read-only
// git submodule stays unmodified while disabling false-positive speculative RFI on
// remote / 5G tunnel links (STREAM_CFG_REMOTE), where burst packet reordering is
// normal. Genuine frame loss is still reported immediately at frame boundaries.
#include "Limelight-internal.h"

#define RtpvInitializeQueue RtpvInitializeQueue_upstream
#define RtpvAddPacket RtpvAddPacket_upstream
#include "moonlight-common-c/src/RtpVideoQueue.c"
#undef RtpvInitializeQueue
#undef RtpvAddPacket

void RtpvInitializeQueue(PRTP_VIDEO_QUEUE queue) {
    RtpvInitializeQueue_upstream(queue);
    if (StreamConfig.streamingRemotely == STREAM_CFG_REMOTE) {
        queue->receivedOosData = true;
        queue->lastOosFramePresentationTimestamp = UINT64_MAX - SPECULATIVE_RFI_COOLDOWN_PERIOD_US;
    }
}

int RtpvAddPacket(PRTP_VIDEO_QUEUE queue, PRTP_PACKET packet, int length, PRTPV_QUEUE_ENTRY packetEntry) {
    if (StreamConfig.streamingRemotely == STREAM_CFG_REMOTE) {
        queue->receivedOosData = true;
    }
    int ret = RtpvAddPacket_upstream(queue, packet, length, packetEntry);
    if (StreamConfig.streamingRemotely == STREAM_CFG_REMOTE) {
        queue->receivedOosData = true;
    }
    return ret;
}
