// EXTENSION DEVELOPMENT [EXT-TOUCH-RIGHT-CLICK] [ADDED]
package com.limelight.extensions.rightclick;

import android.view.MotionEvent;

/**
 * Finger gestures that end in a right click, independent of Android views and timers.
 *
 * <ul>
 * <li>Armed: the next single-finger touch aims; releasing it right-clicks at the release point.</li>
 * <li>Hold (native multi-touch only): a still finger held for {@code holdMs} cancels its native
 * touch, and releasing it right-clicks where it was pressed, like Windows press-and-hold.</li>
 * </ul>
 * Coordinates: {@code x/y} are screen pixels used for slop and feedback, {@code nx/ny} are the
 * stream-normalized position the click is sent to.
 */
final class RightClickGesture {
    interface Output {
        void schedule(long delayMs);
        void unschedule();
        void cancelNativeTouch();
        void showHold(float x, float y);
        void showAim(float x, float y);
        void hide();
        void armedChanged(boolean armed);
        void rightClick(float nx, float ny);
    }

    private enum Track { NONE, IGNORE, WAIT, HELD, AIM }

    private final Output out;
    private final long holdMs;
    private final float slop;
    private boolean armed;
    private Track track = Track.NONE;
    private boolean aborted;
    private float downX, downY, downNx, downNy;

    RightClickGesture(Output out, long holdMs, float slop) {
        this.out = out;
        this.holdMs = holdMs;
        this.slop = slop;
    }

    boolean isArmed() { return armed; }

    /** Whether onArmedEvent can consume anything right now. */
    boolean wantsArmedEvents() { return armed || track == Track.AIM; }

    void setArmed(boolean value) {
        if (armed == value) return;
        armed = value;
        if (!value && track == Track.AIM) {
            track = Track.IGNORE;
            out.hide();
        }
        out.armedChanged(value);
    }

    /** Armed gestures, in every touch mode. Returns true when the event is consumed. */
    boolean onArmedEvent(int action, float x, float y, float nx, float ny) {
        if (track == Track.AIM) {
            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    if (!aborted) out.showAim(x, y);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    aborted = true;
                    out.hide();
                    return true;
                case MotionEvent.ACTION_UP:
                    track = Track.NONE;
                    out.hide();
                    setArmed(false);
                    if (!aborted) out.rightClick(nx, ny);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    track = Track.NONE;
                    out.hide();
                    return true;
                default:
                    return true;
            }
        }
        if (armed && action == MotionEvent.ACTION_DOWN) {
            abandonHold();
            track = Track.AIM;
            aborted = false;
            out.showAim(x, y);
            return true;
        }
        return false;
    }

    /**
     * Native multi-touch events, seen before they are sent to the host.
     * Returns true when the event must not reach the host.
     */
    boolean onNativeEvent(int action, float x, float y, float nx, float ny, long eventTime, long downTime) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                abandonHold();
                track = Track.WAIT;
                aborted = false;
                downX = x; downY = y; downNx = nx; downNy = ny;
                // A deferred DOWN (e.g. held back for dictation) has already used part of the hold.
                out.schedule(Math.max(0, holdMs - Math.max(0, eventTime - downTime)));
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (track == Track.WAIT) {
                    out.unschedule();
                    track = Track.IGNORE;
                } else if (track == Track.HELD) {
                    aborted = true;
                    out.hide();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (track == Track.WAIT && moved(x, y)) {
                    out.unschedule();
                    track = Track.IGNORE;
                } else if (track == Track.HELD) {
                    if (!aborted && moved(x, y)) {
                        aborted = true;
                        out.hide();
                    }
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
                return finishHold(true);
            case MotionEvent.ACTION_CANCEL:
                return finishHold(false);
            default:
                return track == Track.HELD;
        }
    }

    /** The hold timer expired. */
    void onHoldTimeout() {
        if (track != Track.WAIT) return;
        track = Track.HELD;
        out.cancelNativeTouch();
        out.showHold(downX, downY);
    }

    /** The host rejected native touch; the legacy mouse path owns this gesture instead. */
    void nativeTouchUnavailable() {
        if (track == Track.WAIT) {
            out.unschedule();
            track = Track.IGNORE;
        }
    }

    void reset() {
        out.unschedule();
        if (track == Track.HELD || track == Track.AIM) out.hide();
        track = Track.NONE;
        setArmed(false);
    }

    private boolean finishHold(boolean click) {
        Track previous = track;
        if (previous == Track.WAIT) out.unschedule();
        track = Track.NONE;
        if (previous != Track.HELD) return false;
        out.hide();
        if (click && !aborted) out.rightClick(downNx, downNy);
        return true;
    }

    private void abandonHold() {
        if (track == Track.WAIT) out.unschedule();
        if (track == Track.HELD) out.hide();
        track = Track.NONE;
    }

    private boolean moved(float x, float y) {
        float dx = x - downX, dy = y - downY;
        return dx * dx + dy * dy > slop * slop;
    }
}
