// Wrapper around upstream moonlight-common-c/src/Platform.c so the read-only
// git submodule stays untouched while boosting critical native streaming threads
// onto Android's display/audio priority classes.
#define _GNU_SOURCE
#include <pthread.h>
#include <string.h>
#include <sys/resource.h>

static inline int android_set_thread_name_and_priority(pthread_t tid, const char *name) {
    int rc = pthread_setname_np(tid, name);
    if (name != NULL) {
        if (strcmp(name, "AudioRecv") == 0 || strcmp(name, "AudioDec") == 0) {
            // Match Process.THREAD_PRIORITY_URGENT_AUDIO (-16)
            setpriority(PRIO_PROCESS, 0, -16);
        } else if (strcmp(name, "VideoRecv") == 0 || strcmp(name, "VideoDec") == 0 ||
                   strcmp(name, "InputSend") == 0 || strcmp(name, "ControlRecv") == 0) {
            // Match Process.THREAD_PRIORITY_URGENT_DISPLAY (-8)
            setpriority(PRIO_PROCESS, 0, -8);
        }
    }
    return rc;
}

#define pthread_setname_np android_set_thread_name_and_priority
#include "moonlight-common-c/src/Platform.c"
#undef pthread_setname_np
