// EXTENSION DEVELOPMENT [EXT-SS-NETWORK] [ADDED]
// Shadowsocks routing for Moonlight's IPv4 home/company sockets.
//
// UDP (video, audio, ENet control, microphone) is handled directly on
// Moonlight's own socket: the hooks below encrypt each datagram to the SS
// server and decrypt replies in the receiving thread, so packets travel
// 5G -> SS decrypt -> Moonlight receiver with no relay thread or loopback hop.
// Setup TCP (RTSP etc.) still goes through the Rust loopback relay.
//
// The hooks are installed at runtime by rewriting libmoonlight-core.so's
// import slots (GOT), so the upstream native code and build stay untouched.
// Only libmoonlight-core is patched; this library and the Rust relay call libc
// directly.
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <arpa/inet.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <link.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/uio.h>
#include <unistd.h>

extern bool vss_config(const char *json);
extern void vss_set_net_handle(uint64_t handle);
extern uint16_t vss_open_tcp(uint32_t ip, uint16_t port, uint16_t source);
extern const void *vss_udp_acquire(uint32_t ip, uint32_t *server_ip, uint16_t *server_port);
extern void vss_udp_release(const void *node);
extern size_t vss_udp_overhead(const void *node);
extern intptr_t vss_udp_encrypt(const void *node, uint32_t ip, uint16_t port,
                                const void *data, size_t len, void *out, size_t cap);
extern intptr_t vss_udp_decrypt(const void *node, void *buf, size_t len,
                                uint32_t *ip, uint16_t *port);
extern intptr_t vss_udp_decrypt_view(const void *node, void *buf, size_t len,
                                     size_t *offset, uint32_t *ip, uint16_t *port);

#define MAX_FLOW_FD 4096
#define SCRATCH_SIZE 65536
#define SEND_STACK 2048
#define RECV_BATCH_CAP 16
#define RECV_SLOT_SIZE 2048

#if defined(__linux__) || defined(__ANDROID__)
#define HAVE_RECVMMSG_BATCH 1
struct recv_slot {
    struct sockaddr_in from;
    unsigned char data[RECV_SLOT_SIZE];
};
#endif

struct flow {
    const void *node;
    uint32_t prefix;             // destination /24 this socket was bound to
    struct sockaddr_in server;
    size_t overhead;
    uint64_t bound_handle;
    pthread_mutex_t recv_lock;   // guards scratch and batch ring; one receiver per socket in practice
    unsigned char *scratch;
#ifdef HAVE_RECVMMSG_BATCH
    struct recv_slot *batch;
    struct mmsghdr *msgs;
    struct iovec *iovs;
    int batch_head;
    int batch_count;
    int batch_state;             // 0 = uninitialized, 1 = enabled, -1 = disabled
#endif
};

static atomic_bool enabled;
static _Atomic(struct flow *) flows[MAX_FLOW_FD];
static pthread_mutex_t create_lock = PTHREAD_MUTEX_INITIALIZER;
static atomic_uint_fast64_t received_bytes;
static atomic_uint_fast64_t bypass_net_handle;

#if defined(__ANDROID__)
typedef int (*android_setsocknetwork_fn)(uint64_t network, int fd);
static pthread_once_t net_fn_once = PTHREAD_ONCE_INIT;
static android_setsocknetwork_fn p_android_setsocknetwork = NULL;

static void resolve_setsocknetwork(void) {
    void *lib = dlopen("libandroid.so", RTLD_LAZY);
    if (lib) {
        p_android_setsocknetwork = (android_setsocknetwork_fn)dlsym(lib, "android_setsocknetwork");
    }
}

static void apply_bypass_network(int fd, uint64_t handle) {
    if (handle == 0) return;
    pthread_once(&net_fn_once, resolve_setsocknetwork);
    if (p_android_setsocknetwork) {
        p_android_setsocknetwork(handle, fd);
    }
}
#else
static void apply_bypass_network(int fd, uint64_t handle) {
    (void)fd;
    (void)handle;
}
#endif

JNIEXPORT void JNICALL Java_com_limelight_extensions_ss_SsNative_setBypassNetHandle(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    uint64_t h = (uint64_t)handle;
    atomic_store_explicit(&bypass_net_handle, h, memory_order_release);
    vss_set_net_handle(h);
}

JNIEXPORT jboolean JNICALL Java_com_limelight_extensions_ss_SsNative_setSsRoutes(
        JNIEnv *env, jclass cls, jbyteArray config) {
    if (config == NULL) {
        atomic_store(&enabled, false);
        return vss_config(NULL);
    }
    jsize length = (*env)->GetArrayLength(env, config);
    char *json = calloc((size_t)length + 1, 1);
    if (!json) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, config, 0, length, (jbyte *)json);
    bool ok = !(*env)->ExceptionCheck(env) && vss_config(json);
    free(json);
    if (ok) atomic_store(&enabled, true);
    return ok;
}

JNIEXPORT jint JNICALL Java_com_limelight_extensions_ss_SsNative_openSsTcp(
        JNIEnv *env, jclass cls, jint ip, jint port, jint source) {
    return vss_open_tcp((uint32_t)ip, port, source);
}

JNIEXPORT jlong JNICALL Java_com_limelight_extensions_ss_SsNative_ssReceivedBytes(JNIEnv *env, jclass cls) {
    return (jlong)atomic_load(&received_bytes);
}

static uint32_t routed_prefix(const struct sockaddr *peer, socklen_t len) {
    if (!peer || len < sizeof(struct sockaddr_in) || peer->sa_family != AF_INET) return 0;
    uint32_t prefix = ntohl(((const struct sockaddr_in *)peer)->sin_addr.s_addr) >> 8;
    return prefix == 0x0a0000 || prefix == 0x0a0101 ? prefix : 0;
}

static struct flow *flow_of(int fd) {
    return fd >= 0 && fd < MAX_FLOW_FD ? atomic_load_explicit(&flows[fd], memory_order_acquire) : NULL;
}

static void free_flow(struct flow *f) {
    vss_udp_release(f->node);
    pthread_mutex_destroy(&f->recv_lock);
#ifdef HAVE_RECVMMSG_BATCH
    free(f->batch);
    free(f->msgs);
    free(f->iovs);
#endif
    free(f->scratch);
    free(f);
}

// Returns the flow for a routed send, creating it on the socket's first one.
static struct flow *flow_for_send(int fd, uint32_t prefix, uint32_t ip) {
    struct flow *f = flow_of(fd);
    if (f) return f->prefix == prefix ? f : NULL;
    if (!atomic_load(&enabled) || fd < 0 || fd >= MAX_FLOW_FD) return NULL;

    pthread_mutex_lock(&create_lock);
    f = flow_of(fd);
    if (!f) {
        uint32_t server_ip;
        uint16_t server_port;
        const void *node = vss_udp_acquire(ip, &server_ip, &server_port);
        if (node) {
            f = calloc(1, sizeof(*f));
            unsigned char *scratch = malloc(SCRATCH_SIZE);
            if (f && scratch) {
                f->node = node;
                f->prefix = prefix;
                f->server = (struct sockaddr_in){.sin_family = AF_INET, .sin_port = htons(server_port),
                                                 .sin_addr.s_addr = htonl(server_ip)};
                f->overhead = vss_udp_overhead(node);
                f->scratch = scratch;
                uint64_t net_handle = atomic_load_explicit(&bypass_net_handle, memory_order_acquire);
                if (net_handle != 0) {
                    apply_bypass_network(fd, net_handle);
                }
                f->bound_handle = net_handle;
                pthread_mutex_init(&f->recv_lock, NULL);
                atomic_store_explicit(&flows[fd], f, memory_order_release);
            } else {
                free(f);
                free(scratch);
                vss_udp_release(node);
                f = NULL;
            }
        }
    }
    pthread_mutex_unlock(&create_lock);
    if (f && f->prefix != prefix) return NULL;
    return f;
}

static bool is_udp(int fd) {
    int type;
    socklen_t size = sizeof(type);
    return getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &size) == 0 && type == SOCK_DGRAM;
}

extern ssize_t __recvfrom_chk(int, void *, size_t, size_t, int, struct sockaddr *, socklen_t *);

// ---- send path -----------------------------------------------------------


// Encrypts `plain` for `peer` and sends it to the SS server from the same socket.
static ssize_t send_direct(int fd, struct flow *f, const struct sockaddr_in *peer,
                           const void *plain, size_t n, int flags, const struct msghdr *ctl) {
    uint64_t net_handle = atomic_load_explicit(&bypass_net_handle, memory_order_relaxed);
    if (net_handle != f->bound_handle) {
        if (net_handle != 0) apply_bypass_network(fd, net_handle);
        f->bound_handle = net_handle;
    }
    unsigned char stack[SEND_STACK];
    size_t cap = (n == 0 ? 900 : n) + f->overhead;
    unsigned char *out = cap <= sizeof(stack) ? stack : malloc(cap);
    if (!out) { errno = ENOMEM; return -1; }
    intptr_t len = vss_udp_encrypt(f->node, ntohl(peer->sin_addr.s_addr), ntohs(peer->sin_port),
                                   plain, n, out, cap);
    ssize_t rc;
    if (len < 0) {
        errno = EMSGSIZE;
        rc = -1;
    } else if (ctl) {
        // Keep ancillary data (ENet's IP_PKTINFO source selection).
        struct iovec iov = {out, (size_t)len};
        struct msghdr copy = *ctl;
        copy.msg_name = (void *)&f->server;
        copy.msg_namelen = sizeof(f->server);
        copy.msg_iov = &iov;
        copy.msg_iovlen = 1;
        rc = sendmsg(fd, &copy, flags);
    } else {
        rc = sendto(fd, out, (size_t)len, flags, (const struct sockaddr *)&f->server, sizeof(f->server));
    }
    if (out != stack) free(out);
    return rc < 0 ? rc : (ssize_t)n;
}

static ssize_t ss_sendto(int fd, const void *data, size_t n, int flags, const struct sockaddr *peer, socklen_t len) {
    uint32_t prefix = routed_prefix(peer, len);
    if (!prefix) return sendto(fd, data, n, flags, peer, len);
    const struct sockaddr_in *dst = (const void *)peer;
    struct flow *f = flow_of(fd);
    if (!f) {
        if (!atomic_load(&enabled)) return sendto(fd, data, n, flags, peer, len);
        f = flow_for_send(fd, prefix, ntohl(dst->sin_addr.s_addr));
    }
    if (!f || f->prefix != prefix) { errno = EHOSTUNREACH; return -1; }
    return send_direct(fd, f, dst, data, n, flags, NULL);
}

static ssize_t ss_sendmsg(int fd, const struct msghdr *msg, int flags) {
    uint32_t prefix = routed_prefix(msg->msg_name, msg->msg_namelen);
    if (!prefix) return sendmsg(fd, msg, flags);
    const struct sockaddr_in *dst = msg->msg_name;
    struct flow *f = flow_of(fd);
    if (!f) {
        if (!atomic_load(&enabled)) return sendmsg(fd, msg, flags);
        f = flow_for_send(fd, prefix, ntohl(dst->sin_addr.s_addr));
    }
    if (!f || f->prefix != prefix) { errno = EHOSTUNREACH; return -1; }

    size_t total = 0;
    for (size_t i = 0; i < (size_t)msg->msg_iovlen; i++) total += msg->msg_iov[i].iov_len;
    unsigned char stack[SEND_STACK];
    unsigned char *plain = total <= sizeof(stack) ? stack : malloc(total);
    if (!plain) { errno = ENOMEM; return -1; }
    size_t off = 0;
    for (size_t i = 0; i < (size_t)msg->msg_iovlen; i++) {
        memcpy(plain + off, msg->msg_iov[i].iov_base, msg->msg_iov[i].iov_len);
        off += msg->msg_iov[i].iov_len;
    }
    ssize_t rc = send_direct(fd, f, dst, plain, total, flags, msg);
    if (plain != stack) free(plain);
    return rc;
}

// ---- receive path --------------------------------------------------------

static bool nonblocking(int fd, int flags) {
    if (flags & MSG_DONTWAIT) return true;
    int fl = fcntl(fd, F_GETFL);
    return fl >= 0 && (fl & O_NONBLOCK);
}


// Receives one datagram from the SS server into f->scratch and decrypts it in place
// without shifting payload bytes. Writes the payload byte offset in f->scratch to *out_off.
// Caller holds f->recv_lock. Returns plaintext length, or -1 with errno set.
static ssize_t recv_direct(int fd, struct flow *f, int flags, struct sockaddr_in *source,
                           void *control, socklen_t *controllen, size_t *out_off) {
    flags &= ~MSG_TRUNC;
    for (;;) {
        struct sockaddr_in from;
        struct iovec iov = {f->scratch, SCRATCH_SIZE};
        struct msghdr hdr = {
            .msg_name = &from, .msg_namelen = sizeof(from),
            .msg_iov = &iov, .msg_iovlen = 1,
            .msg_control = control, .msg_controllen = controllen ? *controllen : 0,
        };
        ssize_t n = recvmsg(fd, &hdr, flags);
        if (n < 0) return n;
        if (hdr.msg_namelen >= sizeof(from) && from.sin_family == AF_INET &&
                from.sin_port == f->server.sin_port && from.sin_addr.s_addr == f->server.sin_addr.s_addr) {
            uint32_t ip;
            uint16_t port;
            size_t off = 0;
            intptr_t plain = vss_udp_decrypt_view(f->node, f->scratch, (size_t)n, &off, &ip, &port);
            if (plain >= 0) {
                if (controllen) *controllen = hdr.msg_controllen;
                *source = (struct sockaddr_in){.sin_family = AF_INET, .sin_port = htons(port),
                                               .sin_addr.s_addr = htonl(ip)};
                *out_off = off;
                atomic_fetch_add_explicit(&received_bytes, (uint64_t)plain, memory_order_relaxed);
                return plain;
            }
        }
        if (nonblocking(fd, flags)) { errno = EAGAIN; return -1; }
        // Blocking (possibly SO_RCVTIMEO) socket: wait for the next datagram.
    }
}

static void fill_source(const struct sockaddr_in *src, struct sockaddr *peer, socklen_t *len) {
    if (!peer || !len) return;
    socklen_t cap = *len;
    memcpy(peer, src, cap < sizeof(*src) ? cap : sizeof(*src));
    *len = sizeof(*src);
}

#ifdef HAVE_RECVMMSG_BATCH
// Batch receive is enabled only for blocking SO_RCVTIMEO sockets (VideoStream/AudioStream
// with useSelect == false) so poll()-gated sockets can never observe an empty kernel queue
// while decrypted datagrams sit buffered in user space.
static bool ensure_batch_ready(int fd, struct flow *f) {
    if (f->batch_state != 0) return f->batch_state > 0;
    struct timeval tv = {0, 0};
    socklen_t tvlen = sizeof(tv);
    int fl = fcntl(fd, F_GETFL);
    bool timed_blocking = (fl >= 0 && !(fl & O_NONBLOCK)) &&
            getsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, &tvlen) == 0 &&
            (tv.tv_sec > 0 || tv.tv_usec > 0);
    if (!timed_blocking) {
        f->batch_state = -1;
        return false;
    }
    struct recv_slot *batch = malloc(RECV_BATCH_CAP * sizeof(*batch));
    struct mmsghdr *msgs = calloc(RECV_BATCH_CAP, sizeof(*msgs));
    struct iovec *iovs = calloc(RECV_BATCH_CAP, sizeof(*iovs));
    if (!batch || !msgs || !iovs) {
        free(batch);
        free(msgs);
        free(iovs);
        f->batch_state = -1;
        return false;
    }
    f->batch = batch;
    f->msgs = msgs;
    f->iovs = iovs;
    f->batch_head = 0;
    f->batch_count = 0;
    f->batch_state = 1;
    return true;
}

static ssize_t recvfrom_batched(int fd, struct flow *f, void *buf, size_t n, int flags,
                                struct sockaddr_in *source) {
    for (;;) {
        while (f->batch_head < f->batch_count) {
            int idx = f->batch_head++;
            struct recv_slot *slot = &f->batch[idx];
            struct msghdr *hdr = &f->msgs[idx].msg_hdr;
            if (hdr->msg_flags & MSG_TRUNC) continue;
            if (hdr->msg_namelen >= sizeof(slot->from) &&
                    slot->from.sin_family == AF_INET &&
                    slot->from.sin_port == f->server.sin_port &&
                    slot->from.sin_addr.s_addr == f->server.sin_addr.s_addr) {
                uint32_t ip;
                uint16_t port;
                size_t off = 0;
                intptr_t plain = vss_udp_decrypt_view(
                        f->node, slot->data, (size_t)f->msgs[idx].msg_len, &off, &ip, &port);
                if (plain >= 0) {
                    size_t copy = (size_t)plain < n ? (size_t)plain : n;
                    if (copy > 0) memcpy(buf, slot->data + off, copy);
                    *source = (struct sockaddr_in){.sin_family = AF_INET, .sin_port = htons(port),
                                                   .sin_addr.s_addr = htonl(ip)};
                    atomic_fetch_add_explicit(&received_bytes, (uint64_t)plain, memory_order_relaxed);
                    return (!(flags & MSG_TRUNC) && (size_t)plain > n) ? (ssize_t)n : plain;
                }
            }
        }
        for (int i = 0; i < RECV_BATCH_CAP; i++) {
            f->iovs[i].iov_base = f->batch[i].data;
            f->iovs[i].iov_len = RECV_SLOT_SIZE;
            f->msgs[i].msg_hdr.msg_name = &f->batch[i].from;
            f->msgs[i].msg_hdr.msg_namelen = sizeof(f->batch[i].from);
            f->msgs[i].msg_hdr.msg_iov = &f->iovs[i];
            f->msgs[i].msg_hdr.msg_iovlen = 1;
            f->msgs[i].msg_hdr.msg_control = NULL;
            f->msgs[i].msg_hdr.msg_controllen = 0;
            f->msgs[i].msg_hdr.msg_flags = 0;
            f->msgs[i].msg_len = 0;
        }
        // MSG_WAITFORONE blocks for the first datagram (honoring SO_RCVTIMEO), then
        // drains any additional queued datagrams immediately without blocking.
        int got = recvmmsg(fd, f->msgs, RECV_BATCH_CAP, MSG_WAITFORONE, NULL);
        if (got < 0) return -1;
        if (got == 0) continue;
        f->batch_head = 0;
        f->batch_count = got;
    }
}
#endif

static ssize_t recvfrom_direct(int fd, struct flow *f, void *buf, size_t n, int flags,
                               struct sockaddr *peer, socklen_t *len) {
    struct sockaddr_in src;
    pthread_mutex_lock(&f->recv_lock);
#ifdef HAVE_RECVMMSG_BATCH
    if ((flags & ~MSG_TRUNC) == 0 && ensure_batch_ready(fd, f)) {
        ssize_t plain = recvfrom_batched(fd, f, buf, n, flags, &src);
        if (plain >= 0) fill_source(&src, peer, len);
        pthread_mutex_unlock(&f->recv_lock);
        return plain;
    }
#endif
    size_t off = 0;
    ssize_t plain = recv_direct(fd, f, flags, &src, NULL, NULL, &off);
    if (plain >= 0) {
        size_t copy = (size_t)plain < n ? (size_t)plain : n;
        if (copy > 0) memcpy(buf, f->scratch + off, copy);
        fill_source(&src, peer, len);
        if (!(flags & MSG_TRUNC) && (size_t)plain > n) plain = (ssize_t)n;
    }
    pthread_mutex_unlock(&f->recv_lock);
    return plain;
}

static ssize_t ss_recvmsg(int fd, struct msghdr *msg, int flags) {
    struct flow *f = flow_of(fd);
    if (!f) return recvmsg(fd, msg, flags);
    struct sockaddr_in src;
    socklen_t controllen = msg->msg_controllen;
    size_t base_off = 0;
    pthread_mutex_lock(&f->recv_lock);
    ssize_t plain = recv_direct(fd, f, flags, &src, msg->msg_control, &controllen, &base_off);
    if (plain >= 0) {
        msg->msg_controllen = controllen;
        msg->msg_flags = 0;
        size_t off = 0;
        for (size_t i = 0; i < (size_t)msg->msg_iovlen && off < (size_t)plain; i++) {
            size_t chunk = msg->msg_iov[i].iov_len;
            if (chunk > (size_t)plain - off) chunk = (size_t)plain - off;
            memcpy(msg->msg_iov[i].iov_base, f->scratch + base_off + off, chunk);
            off += chunk;
        }
        if (off < (size_t)plain) {
            msg->msg_flags |= MSG_TRUNC;
            if (!(flags & MSG_TRUNC)) plain = (ssize_t)off;
        }
        if (msg->msg_name) fill_source(&src, msg->msg_name, &msg->msg_namelen);
    }
    pthread_mutex_unlock(&f->recv_lock);
    return plain;
}

static ssize_t ss_recvfrom(int fd, void *buf, size_t n, int flags, struct sockaddr *peer, socklen_t *len) {
    struct flow *f = flow_of(fd);
    if (!f) return recvfrom(fd, buf, n, flags, peer, len);
    return recvfrom_direct(fd, f, buf, n, flags, peer, len);
}

static ssize_t ss_recvfrom_chk(int fd, void *buf, size_t n, size_t size, int flags, struct sockaddr *peer, socklen_t *len) {
    struct flow *f = flow_of(fd);
    if (!f) return __recvfrom_chk(fd, buf, n, size, flags, peer, len);
    if (n > size) abort();  // same contract as the fortified libc call
    return recvfrom_direct(fd, f, buf, n, flags, peer, len);
}

// ---- TCP relay + lifecycle ----------------------------------------------

static int ss_connect(int fd, const struct sockaddr *peer, socklen_t len) {
    // UDP connect() to a routed host is only used for local-address discovery;
    // routed UDP traffic is redirected per datagram above.
    if (!atomic_load(&enabled) || !routed_prefix(peer, len) || is_udp(fd))
        return connect(fd, peer, len);

    const struct sockaddr_in *dst = (const void *)peer;
    struct sockaddr_in local = {0};
    socklen_t local_len = sizeof(local);
    if (getsockname(fd, (struct sockaddr *)&local, &local_len) < 0) return -1;
    if (!local.sin_port) {
        local = (struct sockaddr_in){.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
        local_len = sizeof(local);
        if (bind(fd, (struct sockaddr *)&local, sizeof(local)) < 0 ||
                getsockname(fd, (struct sockaddr *)&local, &local_len) < 0) return -1;
    }
    uint16_t port = vss_open_tcp(ntohl(dst->sin_addr.s_addr), ntohs(dst->sin_port), ntohs(local.sin_port));
    if (!port) { errno = EHOSTUNREACH; return -1; }
    struct sockaddr_in relay = {.sin_family = AF_INET, .sin_port = htons(port),
                                .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    return connect(fd, (struct sockaddr *)&relay, sizeof(relay));
}

static int ss_close(int fd) {
    // Moonlight joins every thread using a socket before closing it.
    if (fd >= 0 && fd < MAX_FLOW_FD) {
        struct flow *f = atomic_exchange(&flows[fd], NULL);
        if (f) free_flow(f);
    }
    return close(fd);
}

// ---- Java DatagramSocket support (StarCore mic uplink) --------------------

JNIEXPORT jlong JNICALL Java_com_limelight_extensions_ss_SsNative_udpAcquire(
        JNIEnv *env, jclass cls, jint ip, jintArray server) {
    if (!atomic_load(&enabled)) return 0;
    uint32_t server_ip;
    uint16_t server_port;
    const void *node = vss_udp_acquire((uint32_t)ip, &server_ip, &server_port);
    if (!node) return 0;
    jint values[2] = {(jint)server_ip, server_port};
    (*env)->SetIntArrayRegion(env, server, 0, 2, values);
    return (jlong)(intptr_t)node;
}

JNIEXPORT void JNICALL Java_com_limelight_extensions_ss_SsNative_udpRelease(JNIEnv *env, jclass cls, jlong node) {
    if (node) vss_udp_release((const void *)(intptr_t)node);
}

JNIEXPORT jbyteArray JNICALL Java_com_limelight_extensions_ss_SsNative_udpEncrypt(
        JNIEnv *env, jclass cls, jlong node, jint ip, jint port, jbyteArray data, jint off, jint len) {
    const void *n = (const void *)(intptr_t)node;
    size_t cap = (len == 0 ? 900 : (size_t)len) + vss_udp_overhead(n);
    unsigned char *buf = malloc(cap + (size_t)len);
    if (!buf) return NULL;
    unsigned char *plain = buf + cap;
    (*env)->GetByteArrayRegion(env, data, off, len, (jbyte *)plain);
    jbyteArray result = NULL;
    intptr_t out = vss_udp_encrypt(n, (uint32_t)ip, (uint16_t)port, plain, (size_t)len, buf, cap);
    if (out > 0 && !(*env)->ExceptionCheck(env)) {
        result = (*env)->NewByteArray(env, (jsize)out);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)out, (const jbyte *)buf);
    }
    free(buf);
    return result;
}

/** Decrypts buf[0..len) in place; returns the payload length or -1, and writes {ip, port} to source. */
JNIEXPORT jint JNICALL Java_com_limelight_extensions_ss_SsNative_udpDecrypt(
        JNIEnv *env, jclass cls, jlong node, jbyteArray data, jint len, jintArray source) {
    unsigned char *buf = malloc((size_t)len);
    if (!buf) return -1;
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)buf);
    uint32_t ip;
    uint16_t port;
    intptr_t plain = (*env)->ExceptionCheck(env) ? -1
            : vss_udp_decrypt((const void *)(intptr_t)node, buf, (size_t)len, &ip, &port);
    if (plain >= 0) {
        (*env)->SetByteArrayRegion(env, data, 0, (jsize)plain, (const jbyte *)buf);
        jint values[2] = {(jint)ip, port};
        (*env)->SetIntArrayRegion(env, source, 0, 2, values);
        atomic_fetch_add_explicit(&received_bytes, (uint64_t)plain, memory_order_relaxed);
    }
    free(buf);
    return (jint)plain;
}

// ---- runtime GOT patching -------------------------------------------------

struct hook { const char *name; void *fn; };
static const struct hook hooks[] = {
    {"connect", (void *)ss_connect},
    {"sendto", (void *)ss_sendto},
    {"sendmsg", (void *)ss_sendmsg},
    {"recvfrom", (void *)ss_recvfrom},
    {"recvmsg", (void *)ss_recvmsg},
    {"__recvfrom_chk", (void *)ss_recvfrom_chk},
    {"close", (void *)ss_close},
};

#if defined(__LP64__)
#define R_SYM(info) ELF64_R_SYM(info)
#define R_TYPE(info) ELF64_R_TYPE(info)
#else
#define R_SYM(info) ELF32_R_SYM(info)
#define R_TYPE(info) ELF32_R_TYPE(info)
#endif

static bool function_slot(unsigned type) {
#if defined(__aarch64__)
    return type == R_AARCH64_JUMP_SLOT || type == R_AARCH64_GLOB_DAT || type == R_AARCH64_ABS64;
#elif defined(__arm__)
    return type == R_ARM_JUMP_SLOT || type == R_ARM_GLOB_DAT || type == R_ARM_ABS32;
#elif defined(__x86_64__)
    return type == R_X86_64_JUMP_SLOT || type == R_X86_64_GLOB_DAT || type == R_X86_64_64;
#elif defined(__i386__)
    return type == R_386_JMP_SLOT || type == R_386_GLOB_DAT || type == R_386_32;
#else
    return false;
#endif
}

struct target {
    const char *suffix;
    int patched;
    bool found;
};

static bool ends_with(const char *s, const char *suffix) {
    size_t a = strlen(s), b = strlen(suffix);
    return a >= b && strcmp(s + a - b, suffix) == 0 && (a == b || s[a - b - 1] == '/');
}

static int patch_table(ElfW(Addr) base, const ElfW(Sym) *symtab, const char *strtab,
                       ElfW(Addr) table, size_t size, size_t entry) {
    int patched = 0;
    long page = sysconf(_SC_PAGESIZE);
    for (size_t off = 0; table && entry && off + entry <= size; off += entry) {
        // r_offset/r_info are laid out identically in Rel and Rela.
        const ElfW(Rel) *rel = (const ElfW(Rel) *)(table + off);
        unsigned sym = (unsigned)R_SYM(rel->r_info);
        if (!sym || !function_slot((unsigned)R_TYPE(rel->r_info))) continue;
        const char *name = strtab + symtab[sym].st_name;
        for (size_t i = 0; i < sizeof(hooks) / sizeof(hooks[0]); i++) {
            if (strcmp(name, hooks[i].name) != 0) continue;
            void **slot = (void **)(base + rel->r_offset);
            if (*slot != hooks[i].fn) {
                uintptr_t start = (uintptr_t)slot & ~(uintptr_t)(page - 1);
                // The GOT sits in RELRO after loading; make the page writable.
                if (mprotect((void *)start, (size_t)page, PROT_READ | PROT_WRITE) != 0) break;
                __atomic_store_n(slot, hooks[i].fn, __ATOMIC_RELEASE);
            }
            patched++;
            break;
        }
    }
    return patched;
}

static int patch_library(struct dl_phdr_info *info, size_t size, void *data) {
    struct target *t = data;
    if (!info->dlpi_name || !ends_with(info->dlpi_name, t->suffix)) return 0;
    ElfW(Addr) base = info->dlpi_addr;
    const ElfW(Dyn) *dyn = NULL;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) dyn = (const ElfW(Dyn) *)(base + info->dlpi_phdr[i].p_vaddr);
    }
    if (!dyn) return 1;

    const ElfW(Sym) *symtab = NULL;
    const char *strtab = NULL;
    ElfW(Addr) jmprel = 0, rela = 0, rel = 0;
    size_t pltrelsz = 0, relasz = 0, relsz = 0;
    ElfW(Sxword) pltrel = 0;
    for (; dyn->d_tag != DT_NULL; dyn++) {
        switch (dyn->d_tag) {
            case DT_SYMTAB: symtab = (const ElfW(Sym) *)(base + dyn->d_un.d_ptr); break;
            case DT_STRTAB: strtab = (const char *)(base + dyn->d_un.d_ptr); break;
            case DT_JMPREL: jmprel = base + dyn->d_un.d_ptr; break;
            case DT_PLTRELSZ: pltrelsz = dyn->d_un.d_val; break;
            case DT_PLTREL: pltrel = (ElfW(Sxword))dyn->d_un.d_val; break;
            case DT_RELA: rela = base + dyn->d_un.d_ptr; break;
            case DT_RELASZ: relasz = dyn->d_un.d_val; break;
            case DT_REL: rel = base + dyn->d_un.d_ptr; break;
            case DT_RELSZ: relsz = dyn->d_un.d_val; break;
        }
    }
    if (!symtab || !strtab) return 1;
    t->found = true;
    t->patched += patch_table(base, symtab, strtab, jmprel, pltrelsz,
                              pltrel == DT_RELA ? sizeof(ElfW(Rela)) : sizeof(ElfW(Rel)));
    t->patched += patch_table(base, symtab, strtab, rela, relasz, sizeof(ElfW(Rela)));
    t->patched += patch_table(base, symtab, strtab, rel, relsz, sizeof(ElfW(Rel)));
    return 1;
}

static pthread_mutex_t install_lock = PTHREAD_MUTEX_INITIALIZER;
static int installed = -1;

/** Patches libmoonlight-core.so (already loaded). Returns the number of hooked slots, or -1 if absent. */
int vss_install_hooks(const char *library) {
    pthread_mutex_lock(&install_lock);
    if (installed <= 0) {
        struct target t = {library, 0, false};
        dl_iterate_phdr(patch_library, &t);
        installed = t.found ? t.patched : -1;
    }
    int result = installed;
    pthread_mutex_unlock(&install_lock);
    return result;
}

JNIEXPORT jint JNICALL Java_com_limelight_extensions_ss_SsNative_installHooks(JNIEnv *env, jclass cls) {
    return vss_install_hooks("libmoonlight-core.so");
}
