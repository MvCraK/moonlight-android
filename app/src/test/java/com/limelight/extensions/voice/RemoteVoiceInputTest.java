// EXTENSION DEVELOPMENT [EXT-REMOTE-VOICE] [ADDED]
package com.limelight.extensions.voice;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class RemoteVoiceInputTest implements RemoteVoiceInput.Host {
    private final ArrayDeque<Runnable> worker = new ArrayDeque<>(), ui = new ArrayDeque<>();
    private final RemoteVoiceInput input = new RemoteVoiceInput(this, worker::add);
    private int starts, ends, errors, disconnects;
    private boolean fail, waiting, speaking;
    private Runnable lost;

    public boolean begin(AtomicBoolean cancelled, Runnable onDisconnect) {
        starts++;
        lost = onDisconnect;
        return !fail;
    }
    private boolean acknowledged = true;
    private int unconfirmedClicks;
    public void clickUnconfirmed() { unconfirmedClicks++; }
    public boolean end() { ends++; return acknowledged; }
    public void onUi(Runnable callback) { ui.add(callback); }
    public void show(boolean pending, boolean active) { waiting = pending; speaking = active; }
    public void failed() { errors++; }
    public void disconnected() { disconnects++; }
    private void tap(long time) { input.tap(time); }
    private void drain() {
        while (!worker.isEmpty() || !ui.isEmpty()) {
            if (!worker.isEmpty()) worker.remove().run();
            if (!ui.isEmpty()) ui.remove().run();
        }
    }

    @Test public void activeAfterSilenceDoubleTapStopsOnlyOnce() {
        tap(0); assertTrue(waiting); drain();
        assertTrue(speaking);
        tap(10000); assertFalse(speaking); drain();
        tap(10200); drain();
        assertEquals(1, starts); assertEquals(1, ends); assertFalse(input.isBusy());
        tap(11000); drain();
        assertEquals(2, starts); assertTrue(speaking);
    }
    @Test public void tapsMustBeMoreThan500MsApart() {
        tap(0);
        tap(150);
        drain();
        tap(650);
        drain();
        assertEquals(1, starts); assertEquals(0, ends); assertTrue(speaking);
        tap(1151);
        drain();
        assertEquals(1, ends);
        assertFalse(speaking);
    }
    @Test public void stoppingDoesNotQueueRestartEvenIfNetworkIsSlow() {
        tap(0); drain(); tap(1500); tap(2100); drain();
        assertEquals(1, starts); assertEquals(1, ends); assertFalse(input.isBusy());
    }
    @Test public void cancelBeforeWorkerNeverOpensMicrophone() {
        tap(0); input.cancel(); drain();
        assertEquals(0, starts); assertEquals(0, errors); assertFalse(input.isBusy());
    }
    @Test public void intentionalTapCanCancelPendingStart() {
        tap(0); worker.remove().run(); tap(501); tap(1100); drain();
        assertEquals(1, starts); assertEquals(1, ends); assertFalse(input.isRequested());
    }
    @Test public void unexpectedDisconnectClearsGreenAndReleasesMic() {
        tap(0); drain();
        Runnable stale = lost;
        stale.run(); drain();
        assertEquals(1, disconnects); assertEquals(1, ends); assertFalse(speaking);
        tap(2000); drain(); stale.run(); drain();
        assertEquals(2, starts); assertEquals(1, disconnects); assertTrue(speaking);
    }
    @Test public void disconnectRacingWithStartupDoesNotLeaveGreen() {
        tap(0); worker.remove().run(); lost.run();
        ui.removeLast().run(); drain();
        assertEquals(1, ends); assertEquals(1, disconnects); assertFalse(speaking);
    }
    @Test public void failureCanRetry() {
        fail = true; tap(0); drain(); assertEquals(1, errors); assertFalse(input.isRequested());
        fail = false; tap(1500); drain(); assertTrue(speaking);
    }

    @Test public void screenTapWaitsForFnEndAndPreservesOrder() {
        StringBuilder clicks = new StringBuilder();
        tap(0); drain();
        input.beforeScreenClick(ok -> { assertTrue(ok); clicks.append("A"); });
        input.beforeScreenClick(ok -> { assertTrue(ok); clicks.append("B"); });
        assertFalse(speaking); assertEquals("", clicks.toString());
        worker.remove().run();
        assertEquals(1, ends); assertEquals("", clicks.toString());
        drain(); assertEquals("AB", clicks.toString());
        tap(1500); drain(); assertTrue(speaking); assertEquals(2, starts);
    }

    @Test public void stopTimeoutAndBackgroundDropPendingClicks() {
        tap(0); drain(); acknowledged = false;
        input.beforeScreenClick(ok -> assertFalse(ok)); drain();
        tap(1000); drain(); acknowledged = true;
        input.beforeScreenClick(ok -> assertFalse(ok)); input.cancel(); drain();
        assertEquals(2, ends); assertEquals(1, unconfirmedClicks); assertFalse(input.hasPendingInput());
    }

    @Test public void tapWhileStartingCancelsBeforeForwarding() {
        tap(0);
        input.beforeScreenClick(ok -> { assertTrue(ok); assertEquals(0, starts); });
        drain();
        tap(1200); worker.remove().run(); // ACK is queued on UI.
        input.beforeScreenClick(ok -> { assertTrue(ok); assertEquals(1, ends); });
        drain(); assertFalse(input.isBusy()); assertFalse(speaking);
    }
}
