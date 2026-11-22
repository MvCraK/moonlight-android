// EXTENSION DEVELOPMENT [EXT-REMOTE-VOICE] [ADDED]
package com.limelight.extensions.voice;

import android.view.MotionEvent;

/**
 * Tracks whether the stream's touch handler holds an open gesture, so events withheld for
 * dictation can be dropped without leaving it waiting for an UP that never arrives.
 */
final class VoiceGestureBalance {
    private boolean open;

    /** Records an event that reached the stream's touch handler. */
    void delivered(int action) {
        if (action == MotionEvent.ACTION_DOWN) open = true;
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) open = false;
    }

    /** True when a dropped event ends a gesture the handler already started. */
    boolean needsCancel(int droppedAction) {
        return open && (droppedAction == MotionEvent.ACTION_UP || droppedAction == MotionEvent.ACTION_CANCEL);
    }

    boolean isOpen() { return open; }
}
