// EXTENSION DEVELOPMENT [EXT-TOUCH-RIGHT-CLICK] [ADDED]
package com.limelight.extensions.rightclick;

import android.view.MotionEvent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class RightClickGestureTest implements RightClickGesture.Output {
    private static final int DOWN = MotionEvent.ACTION_DOWN, UP = MotionEvent.ACTION_UP,
            MOVE = MotionEvent.ACTION_MOVE, CANCEL = MotionEvent.ACTION_CANCEL,
            POINTER_DOWN = MotionEvent.ACTION_POINTER_DOWN;
    private final RightClickGesture gesture = new RightClickGesture(this, 650, 20);
    private final List<float[]> clicks = new ArrayList<>();
    private long scheduled = -1;
    private int cancels;
    private boolean holdShown, aimShown, armed;

    public void schedule(long delayMs) { scheduled = delayMs; }
    public void unschedule() { scheduled = -1; }
    public void cancelNativeTouch() { cancels++; }
    public void showHold(float x, float y) { holdShown = true; }
    public void showAim(float x, float y) { aimShown = true; }
    public void hide() { holdShown = aimShown = false; }
    public void armedChanged(boolean value) { armed = value; }
    public void rightClick(float nx, float ny) { clicks.add(new float[] {nx, ny}); }

    private boolean nativeEvent(int action, float x, float nx) {
        return gesture.onNativeEvent(action, x, 100, nx, 0.5f, 0, 0);
    }
    private void fireTimer() {
        assertTrue("timer was not scheduled", scheduled >= 0);
        scheduled = -1;
        gesture.onHoldTimeout();
    }

    @Test public void holdThenReleaseRightClicksAtPressPoint() {
        assertFalse(nativeEvent(DOWN, 100, 0.25f));
        assertEquals(650, scheduled);
        fireTimer();
        assertEquals(1, cancels);
        assertTrue(holdShown);
        assertTrue(nativeEvent(MOVE, 105, 0.26f));
        assertTrue(nativeEvent(UP, 105, 0.26f));
        assertEquals(1, clicks.size());
        assertEquals(0.25f, clicks.get(0)[0], 0);
        assertFalse(holdShown);
    }

    @Test public void quickTapStaysNative() {
        assertFalse(nativeEvent(DOWN, 100, 0.25f));
        assertFalse(nativeEvent(UP, 100, 0.25f));
        assertEquals(-1, scheduled);
        assertEquals(0, cancels);
        assertTrue(clicks.isEmpty());
    }

    @Test public void dragBeforeTimeoutStaysNative() {
        nativeEvent(DOWN, 100, 0.25f);
        assertFalse(nativeEvent(MOVE, 140, 0.3f));
        assertEquals(-1, scheduled);
        gesture.onHoldTimeout();
        assertFalse(nativeEvent(UP, 140, 0.3f));
        assertEquals(0, cancels);
        assertTrue(clicks.isEmpty());
    }

    @Test public void secondFingerBeforeTimeoutStaysNative() {
        nativeEvent(DOWN, 100, 0.25f);
        assertFalse(nativeEvent(POINTER_DOWN, 100, 0.25f));
        assertEquals(-1, scheduled);
        assertFalse(nativeEvent(UP, 100, 0.25f));
        assertTrue(clicks.isEmpty());
    }

    @Test public void movingAwayAfterHoldCancelsTheClick() {
        nativeEvent(DOWN, 100, 0.25f);
        fireTimer();
        assertTrue(nativeEvent(MOVE, 160, 0.4f));
        assertFalse(holdShown);
        assertTrue(nativeEvent(UP, 160, 0.4f));
        assertTrue(clicks.isEmpty());
    }

    @Test public void deferredDownOnlyWaitsForTheRemainingHold() {
        gesture.onNativeEvent(DOWN, 100, 100, 0.25f, 0.5f, 500, 0);
        assertEquals(150, scheduled);
        gesture.onNativeEvent(UP, 100, 100, 0.25f, 0.5f, 520, 0);
        gesture.onNativeEvent(DOWN, 100, 100, 0.25f, 0.5f, 900, 0);
        assertEquals(0, scheduled);
    }

    @Test public void rejectedNativeTouchLeavesTheLegacyLongPressAlone() {
        nativeEvent(DOWN, 100, 0.25f);
        gesture.nativeTouchUnavailable();
        assertEquals(-1, scheduled);
        gesture.onHoldTimeout();
        assertEquals(0, cancels);
    }

    @Test public void armedTapRightClicksAtReleaseAndDisarms() {
        gesture.setArmed(true);
        assertTrue(armed);
        assertTrue(gesture.onArmedEvent(DOWN, 10, 10, 0.1f, 0.1f));
        assertTrue(aimShown);
        assertTrue(gesture.onArmedEvent(MOVE, 30, 30, 0.3f, 0.3f));
        assertTrue(gesture.onArmedEvent(UP, 30, 30, 0.3f, 0.3f));
        assertEquals(1, clicks.size());
        assertEquals(0.3f, clicks.get(0)[0], 0);
        assertFalse(armed);
        assertFalse(gesture.onArmedEvent(DOWN, 10, 10, 0.1f, 0.1f));
    }

    @Test public void armedGestureWithSecondFingerDoesNotClickButDisarms() {
        gesture.setArmed(true);
        gesture.onArmedEvent(DOWN, 10, 10, 0.1f, 0.1f);
        assertTrue(gesture.onArmedEvent(POINTER_DOWN, 10, 10, 0.1f, 0.1f));
        assertTrue(gesture.onArmedEvent(UP, 10, 10, 0.1f, 0.1f));
        assertTrue(clicks.isEmpty());
        assertFalse(armed);
    }

    @Test public void cancelledArmedGestureStaysArmed() {
        gesture.setArmed(true);
        gesture.onArmedEvent(DOWN, 10, 10, 0.1f, 0.1f);
        assertTrue(gesture.onArmedEvent(CANCEL, 10, 10, 0.1f, 0.1f));
        assertTrue(armed);
        assertTrue(clicks.isEmpty());
    }

    @Test public void resetDropsHoldAndArming() {
        gesture.setArmed(true);
        nativeEvent(DOWN, 100, 0.25f);
        gesture.reset();
        assertFalse(armed);
        assertEquals(-1, scheduled);
    }
}
