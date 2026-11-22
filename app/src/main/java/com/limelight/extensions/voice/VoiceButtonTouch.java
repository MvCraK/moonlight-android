// EXTENSION DEVELOPMENT [EXT-REMOTE-VOICE] [ADDED]
package com.limelight.extensions.voice;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/** Shares the microphone's hit target between a tap, a stationary hold and an edge-snapping drag. */
final class VoiceButtonTouch implements View.OnTouchListener {
    static final int HOLD_MS = 420;
    private final View view;
    private final FrameLayout layer;
    private final View recordingRing;
    private final ValueAnimator recordingRipple = ValueAnimator.ofFloat(0f, 1f);
    private final VoiceKeyMenu menu;
    private final SharedPreferences prefs;
    private final int slop, horizontalMargin, verticalMargin;
    private final Rect safe = new Rect();
    private int topInset, bottomInset;
    private final int[] location = new int[2];
    private int pointer = -1;
    private float downX, downY, originX, originY, heightRatio;
    private boolean dragging, right, held, reopen, waiting;
    private final Runnable hold = this::onHold;

    VoiceButtonTouch(View view, SharedPreferences prefs, Runnable click, VoiceKeyMenu.Host keys) {
        this.view = view;
        this.prefs = prefs;
        layer = (FrameLayout)view.getParent();
        menu = new VoiceKeyMenu(layer, keys);
        slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        float density = view.getResources().getDisplayMetrics().density;
        recordingRing = new View(view.getContext());
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(Math.max(1, Math.round(2 * density)), 0xFFC8463F);
        recordingRing.setBackground(ring);
        recordingRing.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        recordingRing.setVisibility(View.INVISIBLE);
        layer.addView(recordingRing, layer.indexOfChild(view),
                new FrameLayout.LayoutParams((FrameLayout.LayoutParams) view.getLayoutParams()));

        recordingRipple.setDuration(1300);
        recordingRipple.setRepeatCount(ValueAnimator.INFINITE);
        recordingRipple.setInterpolator(new LinearInterpolator());
        recordingRipple.addUpdateListener(animation -> {
            float progress = animation.getAnimatedFraction();
            float scale = 1.04f + 0.66f * progress;
            float remaining = 1f - progress;
            recordingRing.setScaleX(scale);
            recordingRing.setScaleY(scale);
            recordingRing.setAlpha(0.56f * remaining * remaining);
        });

        horizontalMargin = Math.round(8 * density);
        verticalMargin = Math.round(4 * density);
        right = prefs.getBoolean("voice_button_right", true);
        heightRatio = Math.max(0, Math.min(1, prefs.getFloat("voice_button_height", 0.5f)));
        layer.setOnClickListener(v -> setOpen(false));
        layer.setClickable(false);
        layer.setFocusable(false);
        view.setOnClickListener(v -> { if (menu.isOpen()) setOpen(false); else click.run(); });
        view.setOnLongClickListener(v -> {
            setOpen(true);
            return true;
        });
        view.setOnTouchListener(this);
        layer.setOnApplyWindowInsetsListener((v, value) -> {
            readInsets(value);
            if (!dragging) restore();
            return value;
        });
        View.OnLayoutChangeListener layout = (v,l,t,r,b,ol,ot,or,ob) -> {
            if (!dragging && view.getWidth() > 0) restore();
        };
        view.addOnLayoutChangeListener(layout);
        layer.addOnLayoutChangeListener(layout);
        layer.requestApplyInsets();
        updateDescription();
    }

    void show(boolean pending, boolean speaking) {
        waiting = pending;
        view.setActivated(speaking);
        updateRecordingRipple();
        updateDescription();
    }

    private void updateRecordingRipple() {
        boolean visible = view.isActivated() && view.isShown();
        if (visible == recordingRipple.isStarted()) return;
        if (visible) {
            recordingRing.setVisibility(View.VISIBLE);
            recordingRipple.start();
        } else {
            recordingRipple.cancel();
            recordingRing.setVisibility(View.INVISIBLE);
        }
    }

    private void positionButton(float x, float y) {
        view.setX(x);
        view.setY(y);
        recordingRing.setX(x);
        recordingRing.setY(y);
    }

    private void updateDescription() {
        view.setContentDescription(menu.isOpen() ? "收起快捷键" : (view.isActivated() ? "远程语音已连接，点击结束"
                : waiting ? "正在连接语音，点击取消" : "语音输入，点击开始") + "，长按展开快捷键，可拖动吸边");
    }

    private void setOpen(boolean open) {
        menu.show(open);
        view.setSelected(open);
        if (open) restore();
        updateDescription();
    }

    @SuppressWarnings("deprecation")
    private void readInsets(WindowInsets value) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets i = value.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.systemGestures() | WindowInsets.Type.ime());
            topInset = i.top;
            bottomInset = i.bottom;
        } else {
            topInset = value.getSystemWindowInsetTop();
            bottomInset = value.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout cut = value.getDisplayCutout();
                if (cut != null) {
                    topInset = Math.max(topInset, cut.getSafeInsetTop());
                    bottomInset = Math.max(bottomInset, cut.getSafeInsetBottom());
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                android.graphics.Insets i = value.getSystemGestureInsets();
                topInset = Math.max(topInset, i.top);
                bottomInset = Math.max(bottomInset, i.bottom);
            }
        }
    }

    private void updateBounds() {
        // Insets are relative to the window; a parent already laid out inside them needs no extra inset.
        if (Build.VERSION.SDK_INT >= 23 && layer.getRootWindowInsets() != null) {
            readInsets(layer.getRootWindowInsets());
        }
        layer.getLocationInWindow(location);
        View root = layer.getRootView();
        int top = Math.max(0, topInset - location[1]);
        int bottom = Math.min(layer.getHeight(), root.getHeight() - location[1] - bottomInset);
        safe.set(horizontalMargin, top + verticalMargin,
                layer.getWidth() - horizontalMargin, bottom - verticalMargin);
    }

    private void restore() {
        updateBounds();
        if (view.getWidth() <= 0) return;
        if (safe.width()<view.getWidth() || safe.height()<view.getHeight()) {
            setOpen(false);
            view.setVisibility(View.INVISIBLE);
            updateRecordingRipple();
            return;
        }
        view.setVisibility(View.VISIBLE);
        int top = safe.top+Math.round((safe.height()-view.getHeight())*heightRatio);
        positionButton(right ? safe.right-view.getWidth() : safe.left, top);
        updateRecordingRipple();
        if (menu.isOpen() && !menu.position(safe, view.getWidth(), top, right)) setOpen(false);
    }

    private void snap() {
        updateBounds();
        right = view.getX()+view.getWidth()/2f >= safe.exactCenterX();
        int available = safe.height()-view.getHeight();
        if (available>0) heightRatio = Math.max(0, Math.min(1,(view.getY()-safe.top)/available));
        restore();
        prefs.edit().putBoolean("voice_button_right", right).putFloat("voice_button_height", heightRatio).apply();
    }

    private void onHold() {
        if (pointer < 0 || dragging || !view.isShown() || menu.isOpen()) return;
        held = true;
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        view.performLongClick();
    }

    private void move(MotionEvent e, int i) {
        if (held) return;
        float dx = e.getRawX()+e.getX(i)-e.getX(0)-downX;
        float dy = e.getRawY()+e.getY(i)-e.getY(0)-downY;
        if (!dragging && dx*dx+dy*dy <= (float)slop*slop) return;
        if (!dragging) {
            view.removeCallbacks(hold);
            reopen = menu.isOpen();
            if (reopen) setOpen(false);
            dragging = true;
        }
        updateBounds();
        positionButton(Math.max(safe.left,Math.min(safe.right-view.getWidth(),originX+dx)),
                Math.max(safe.top,Math.min(safe.bottom-view.getHeight(),originY+dy)));
    }

    private void finish(boolean commit) {
        view.removeCallbacks(hold);
        if (dragging) {
            if (commit) snap(); else restore();
            if (commit && reopen) setOpen(true);
        }
        pointer = -1;
        dragging = held = reopen = false;
    }

    void reset() { finish(false); setOpen(false); }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouch(View ignored, MotionEvent e) {
        int i = e.getActionIndex();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                finish(false);
                pointer = e.getPointerId(0);
                downX=e.getRawX(); downY=e.getRawY(); originX=view.getX(); originY=view.getY();
                if (!menu.isOpen()) view.postDelayed(hold,HOLD_MS);
                break;
            case MotionEvent.ACTION_MOVE:
                i=e.findPointerIndex(pointer);
                if (i>=0) move(e,i);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                finish(false);
                break;
            case MotionEvent.ACTION_UP:
                if (e.getPointerId(i)!=pointer) break;
                move(e,i);
                if (!dragging && !held) {
                    if (!menu.isOpen() && e.getEventTime()-e.getDownTime() >= HOLD_MS) onHold();
                    else view.performClick();
                }
                finish(true);
                break;
            default: break;
        }
        return true;
    }
}
