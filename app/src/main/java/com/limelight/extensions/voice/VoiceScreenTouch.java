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
            if (voice.isBusy() && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                    && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                surface = view;
                down = MotionEvent.obtain(event);
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            }
        }
        if (down == null) return target.dispatch(view, event);
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
                            target.dispatch(startView, start);
                            target.dispatch(view, end);
                        }
                    } finally { start.recycle(); end.recycle(); }
                });
                return true;
            }
        }
        forwardDown();
        return target.dispatch(view, event);
    }

    private void defer(View view, MotionEvent event) {
        MotionEvent copy = MotionEvent.obtain(event);
        voice.beforeScreenClick(confirmed -> {
            try {
                if (confirmed) target.dispatch(view, copy);
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
        try { target.dispatch(view, event); }
        finally { event.recycle(); }
    }

    void reset() {
        if (down != null) takeDown().recycle();
    }
}
