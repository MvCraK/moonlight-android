// EXTENSION DEVELOPMENT [EXT-REMOTE-VOICE] [ADDED]
package com.limelight.extensions.voice;

import android.view.MotionEvent;

import org.junit.Test;

import static org.junit.Assert.*;

public class VoiceGestureBalanceTest {
    private final VoiceGestureBalance balance = new VoiceGestureBalance();

    @Test public void droppedUpAfterForwardedDownNeedsCancel() {
        balance.delivered(MotionEvent.ACTION_DOWN);
        balance.delivered(MotionEvent.ACTION_MOVE);
        assertTrue(balance.needsCancel(MotionEvent.ACTION_UP));
        assertTrue(balance.needsCancel(MotionEvent.ACTION_CANCEL));
        assertFalse(balance.needsCancel(MotionEvent.ACTION_MOVE));
    }

    @Test public void closedGestureNeedsNoCancel() {
        assertFalse(balance.needsCancel(MotionEvent.ACTION_UP));
        balance.delivered(MotionEvent.ACTION_DOWN);
        balance.delivered(MotionEvent.ACTION_UP);
        assertFalse(balance.needsCancel(MotionEvent.ACTION_UP));
    }

    @Test public void deliveredCancelClosesGesture() {
        balance.delivered(MotionEvent.ACTION_DOWN);
        balance.delivered(MotionEvent.ACTION_CANCEL);
        assertFalse(balance.isOpen());
        assertFalse(balance.needsCancel(MotionEvent.ACTION_UP));
    }
}
