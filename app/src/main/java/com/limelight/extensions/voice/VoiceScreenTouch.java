// EXTENSION DEVELOPMENT [EXT-REMOTE-VOICE] [ADDED]
package com.limelight.extensions.voice;

import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Recognizes a stream tap before any of its pointer events can dismiss Mac dictation. */
final class VoiceScreenTouch {
    interface Target { boolean dispatch(View view, MotionEvent event); }

    private final RemoteVoiceInput voice;
    private final Target target;
    private final int slop;
    private View surface;
    private MotionEvent down;
    private final Runnable longPress = this::forwardDown;
    private final VoiceGestureBalance balance = new VoiceGestureBalance();
    private View balanceView;
    // A reset discarded a held DOWN the handler never saw; drop the rest of that gesture too.
    private boolean discardUntilDown;

    VoiceScreenTouch(RemoteVoiceInput voice, Target target, int slop) {
        this.voice = voice;
        this.target = target;
        this.slop = slop;
    }

    boolean dispatch(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (voice.hasPendingInput()) {
            // A second tap can arrive before the host acknowledges the first one.
            defer(view, event);
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
            discardUntilDown = false;
            if (voice.isBusy() && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                    && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                surface = view;
                down = MotionEvent.obtain(event);
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            }
        }
        if (discardUntilDown) return true;
        if (down == null) return deliver(view, event);
        if (action == MotionEvent.ACTION_CANCEL) {
            reset();
            return true;
        }
        if (surface == view && event.getPointerCount() == 1 && withinSlop(event)
                && event.getEventTime() - down.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
            if (action == MotionEvent.ACTION_MOVE) return true;
            if (action == MotionEvent.ACTION_UP) {
                // Copy both ends before requesting a stop, including the original tap coordinates.
                View startView = surface;
                MotionEvent start = takeDown();
                MotionEvent end = MotionEvent.obtain(event);
                voice.beforeScreenClick(confirmed -> {
                    try {
                        if (confirmed) {
                            deliver(startView, start);
                            deliver(view, end);
                        }
                    } finally { start.recycle(); end.recycle(); }
                });
                return true;
            }
        }
        forwardDown();
        return deliver(view, event);
    }

    private void defer(View view, MotionEvent event) {
        MotionEvent copy = MotionEvent.obtain(event);
        voice.beforeScreenClick(confirmed -> {
            try {
                if (confirmed) deliver(view, copy);
                else {
                    int action = copy.getActionMasked();
                    if (balance.needsCancel(action)) cancelOpenGesture(copy);
                    // The handler never sees this DOWN, so the live remainder of its gesture must not reach it either.
                    else if (action == MotionEvent.ACTION_DOWN) discardUntilDown = true;
                }
            } finally { copy.recycle(); }
        });
    }

    private boolean withinSlop(MotionEvent event) {
        float dx = down.getX() - event.getX(), dy = down.getY() - event.getY();
        return dx * dx + dy * dy <= (float) slop * slop;
    }

    private MotionEvent takeDown() {
        surface.removeCallbacks(longPress);
        surface = null;
        MotionEvent result = down;
        down = null;
        return result;
    }

    private void forwardDown() {
        if (down == null) return;
        View view = surface;
        MotionEvent event = takeDown();
        try { deliver(view, event); }
        finally { event.recycle(); }
    }

    private boolean deliver(View view, MotionEvent event) {
        balance.delivered(event.getActionMasked());
        balanceView = balance.isOpen() ? view : null;
        return target.dispatch(view, event);
    }

    /** The host did not confirm the stop: drop the input, but release the handler's open gesture. */
    private void cancelOpenGesture(MotionEvent dropped) {
        View view = balanceView;
        MotionEvent cancel = MotionEvent.obtain(dropped);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        try { deliver(view, cancel); }
        finally { cancel.recycle(); }
    }

    void reset() {
        if (down != null) {
            takeDown().recycle();
            discardUntilDown = true;
        }
    }
}
