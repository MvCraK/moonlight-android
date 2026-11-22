// EXTENSION DEVELOPMENT [EXT-REMOTE-VOICE] [ADDED]
package com.limelight.extensions.voice;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** UI-thread state. At most one network operation is in flight for the voice button. */
final class RemoteVoiceInput {
    private static final long TAP_DEBOUNCE_MS = 500;

    interface Host {
        boolean begin(AtomicBoolean cancelled, Runnable onDisconnect);
        boolean end();
        void onUi(Runnable callback);
        void show(boolean waiting, boolean speaking);
        void failed();
        void disconnected();
        void clickUnconfirmed();
    }

    interface Stopped { void complete(boolean confirmed); }
    private final ArrayDeque<Stopped> pendingInput = new ArrayDeque<>();

    private enum Phase { IDLE, STARTING, ACTIVE, STOPPING }

    private final Host host;
    private final Executor worker;
    private Phase phase = Phase.IDLE;
    private boolean requested;
    private AtomicBoolean cancellation;
    private long lastTapMs = -1;

    RemoteVoiceInput(Host host, Executor worker) {
        this.host = host;
        this.worker = worker;
    }

    boolean isBusy() { return phase != Phase.IDLE; }
    boolean isRequested() { return requested; }

    void tap(long nowMs) {
        boolean repeated = lastTapMs >= 0 && nowMs - lastTapMs <= TAP_DEBOUNCE_MS;
        lastTapMs = nowMs;
        if (repeated || phase == Phase.STOPPING ||
                (phase == Phase.STARTING && !requested)) return;
        request(!requested);
    }
    void cancel() {
        finishPendingInput(false);
        request(false);
    }

    void beforeScreenClick(Stopped callback) {
        if (!isBusy()) {
            callback.complete(true);
            return;
        }
        pendingInput.add(callback);
        request(false);
    }

    // Keep pointer UP and subsequent movement ordered behind a deferred DOWN.
    boolean hasPendingInput() { return !pendingInput.isEmpty(); }

    private void finishPendingInput(boolean confirmed) {
        while (!pendingInput.isEmpty()) pendingInput.remove().complete(confirmed);
    }

    private void request(boolean enable) {
        requested = enable;
        if (!enable && phase == Phase.STARTING) cancellation.set(true);
        reconcile();
    }

    private void reconcile() {
        if (phase == Phase.IDLE && requested) {
            phase = Phase.STARTING;
            AtomicBoolean ticket = new AtomicBoolean();
            cancellation = ticket;
            worker.execute(() -> {
                boolean started = !ticket.get() && host.begin(ticket, () -> host.onUi(() -> {
                    if (cancellation == ticket && requested) {
                        cancel();
                        host.disconnected();
                    }
                }));
                host.onUi(() -> {
                    phase = started ? Phase.ACTIVE : Phase.IDLE;
                    if (!started && !ticket.get()) {
                        requested = false;
                        host.failed();
                    }
                    // A cancelled start can still win the handshake. End it before reopening.
                    if (started && ticket.get()) stop();
                    else {
                        if (!started) finishPendingInput(true); // No gesture was established.
                        reconcile();
                    }
                });
            });
        } else if (phase == Phase.ACTIVE && !requested) {
            stop();
        }
        host.show(requested && phase != Phase.ACTIVE, requested && phase == Phase.ACTIVE);
    }

    private void stop() {
        phase = Phase.STOPPING;
        cancellation = null;
        worker.execute(() -> {
            boolean acknowledged = false;
            try {
                acknowledged = host.end();
            } finally {
                boolean confirmed = acknowledged;
                host.onUi(() -> {
                    phase = Phase.IDLE;
                    if (!confirmed && hasPendingInput()) host.clickUnconfirmed();
                    finishPendingInput(confirmed);
                    reconcile();
                });
            }
        });
    }
}
