// EXTENSION DEVELOPMENT [EXT-TOUCH-RIGHT-CLICK] [ADDED]
package com.limelight.extensions.rightclick;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

/**
 * Right click for finger input: an armed "next tap" mode in every touch mode, and a
 * Windows-style press-and-hold while fingers are passed through as native multi-touch.
 * Game owns coordinate mapping and the stream; this class only decides when to click.
 */
public final class TouchRightClickExtension {
    /** Matches the mouse-mode long press in AbsoluteTouchContext. */
    static final long HOLD_MS = 650;

    public interface Host {
        /** Stream-normalized [x, y] for the pointer, as native touch would send it. */
        float[] normalize(View view, MotionEvent event, int pointerIndex);
        /** Abandon every native touch the host is tracking. */
        void cancelNativeTouch();
        void rightClick(float normalizedX, float normalizedY);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RightClickGesture gesture;
    private Host host;
    private RightClickIndicator indicator;
    private View feedbackView;
    private final Runnable timeout = () -> {
        if (gesture == null) return;
        gesture.onHoldTimeout();
    };

    public void attach(Activity activity, Host host) {
        detach();
        ViewGroup content = activity.findViewById(android.R.id.content);
        indicator = new RightClickIndicator(activity);
        indicator.setElevation(64 * activity.getResources().getDisplayMetrics().density);
        content.addView(indicator, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final RightClickIndicator view = indicator;
        gesture = new RightClickGesture(new RightClickGesture.Output() {
            @Override public void schedule(long delayMs) {
                handler.removeCallbacks(timeout);
                handler.postDelayed(timeout, delayMs);
            }
            @Override public void unschedule() { handler.removeCallbacks(timeout); }
            @Override public void cancelNativeTouch() {
                host.cancelNativeTouch();
                if (feedbackView != null) feedbackView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            @Override public void showHold(float x, float y) { view.showAt(true, x, y); }
            @Override public void showAim(float x, float y) { view.showAt(false, x, y); }
            @Override public void hide() { view.hideTarget(); }
            @Override public void armedChanged(boolean armed) { view.setArmed(armed); }
            @Override public void rightClick(float nx, float ny) { host.rightClick(nx, ny); }
        }, HOLD_MS, ViewConfiguration.get(activity).getScaledTouchSlop() * 1.5f);
        this.host = host;
    }

    public void detach() {
        if (gesture != null) gesture.reset();
        handler.removeCallbacks(timeout);
        if (indicator != null && indicator.getParent() instanceof ViewGroup) {
            ((ViewGroup) indicator.getParent()).removeView(indicator);
        }
        indicator = null;
        gesture = null;
        host = null;
    }

    /** Arms (or disarms) a right click on the next single-finger tap. */
    public void toggleArmed() {
        if (gesture != null) gesture.setArmed(!gesture.isArmed());
    }

    public boolean isArmed() { return gesture != null && gesture.isArmed(); }

    public void reset() {
        if (gesture != null) gesture.reset();
    }

    /** Every finger event, before mode-specific handling. True when consumed. */
    public boolean onFingerEvent(View view, MotionEvent event) {
        if (gesture == null || !gesture.wantsArmedEvents() || !isFinger(event)) return false;
        int index = event.getActionIndex();
        float[] n = host.normalize(view, event, index);
        return gesture.onArmedEvent(event.getActionMasked(), event.getRawX(), event.getRawY(), n[0], n[1]);
    }

    /** Finger events about to be sent as native touch. True when they must not be sent. */
    public boolean onNativeTouch(View view, MotionEvent event) {
        if (gesture == null || !isFinger(event)) return false;
        feedbackView = view;
        int index = event.getActionIndex();
        float[] n = host.normalize(view, event, index);
        return gesture.onNativeEvent(event.getActionMasked(), event.getRawX(), event.getRawY(),
                n[0], n[1], event.getEventTime(), event.getDownTime());
    }

    /** Native touch was rejected for this event, so the legacy mouse path handles it. */
    public void nativeTouchUnavailable() {
        if (gesture != null) gesture.nativeTouchUnavailable();
    }

    private static boolean isFinger(MotionEvent event) {
        return event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                && event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_FINGER;
    }
}
