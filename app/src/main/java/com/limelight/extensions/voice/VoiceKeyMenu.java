// EXTENSION DEVELOPMENT [EXT-REMOTE-VOICE] [ADDED]
package com.limelight.extensions.voice;

import com.limelight.R;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

/** Five local controls on an arc of circular keys. Only confirmed, complete keystrokes are sent. */
final class VoiceKeyMenu {
    interface Host {
        void afterVoiceStop(Runnable action);
        void sendKey(int androidKeyCode, boolean withShift);
        // EXTENSION DEVELOPMENT [EXT-TOUCH-RIGHT-CLICK] [ADDED] BEGIN
        void toggleRightClick();
        boolean isRightClickArmed();
        // EXTENSION DEVELOPMENT [EXT-TOUCH-RIGHT-CLICK] [ADDED] END
    }

    static final int DELETE_REPEAT_MS = 60;
    private static final int LABEL_DP = 15;
    private static final int ESC = 0, TAB = 1, DELETE = 2, ENTER = 3, RIGHT_CLICK = 4;

    private final Host host;
    private final FrameLayout layer;
    private final View[] keys = new View[5];
    private final TextView[] labels = new TextView[5];
    private final VoiceKeyFan fan = new VoiceKeyFan(5);
    private final float density;
    private final int labelHeight;
    private boolean open, holdingDelete, repeated;
    private int deleteGeneration;
    private final Runnable repeat = this::repeatDelete;

    VoiceKeyMenu(FrameLayout layer, Host host) {
        this.layer = layer;
        this.host = host;
        density = layer.getResources().getDisplayMetrics().density;
        labelHeight = Math.round(LABEL_DP * density);
        add(ESC, "Esc", "Escape", 0);
        add(TAB, "Tab", "Tab", R.drawable.ic_voice_tab);
        add(DELETE, "删除", "退格，按住连续删除", R.drawable.ic_voice_delete);
        add(ENTER, "回车", "回车，长按换行（Shift+回车）", R.drawable.ic_voice_enter);
        add(RIGHT_CLICK, "右键", "右键，点一下后在屏幕目标位置轻点", R.drawable.ic_voice_right_click);
        for (int i = ESC; i <= ENTER; i++) {
            int code = i == ESC ? KeyEvent.KEYCODE_ESCAPE : i == TAB ? KeyEvent.KEYCODE_TAB
                    : i == DELETE ? KeyEvent.KEYCODE_DEL : KeyEvent.KEYCODE_ENTER;
            keys[i].setOnClickListener(v -> host.afterVoiceStop(() -> host.sendKey(code, false)));
        }
        keys[DELETE].setOnTouchListener(this::deleteTouch);
        keys[ENTER].setOnLongClickListener(v -> {
            if (open) host.afterVoiceStop(() -> host.sendKey(KeyEvent.KEYCODE_ENTER, true));
            return true;
        });
        keys[RIGHT_CLICK].setOnClickListener(v -> host.toggleRightClick());
    }

    private void add(int index, String label, String description, int icon) {
        View key;
        if (icon == 0) {
            Button text = new Button(layer.getContext());
            text.setAllCaps(false);
            text.setText(label.toLowerCase());
            text.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            text.setTextColor(Color.WHITE);
            text.setTextSize(14);
            text.setIncludeFontPadding(false);
            text.setGravity(Gravity.CENTER);
            text.setMinHeight(0);
            text.setMinWidth(0);
            text.setMinimumHeight(0);
            text.setMinimumWidth(0);
            text.setPadding(0, 0, 0, 0);
            key = text;
        } else {
            ImageButton image = new ImageButton(layer.getContext());
            image.setImageResource(icon);
            image.setScaleType(ImageView.ScaleType.CENTER);
            image.setPadding(0, 0, 0, 0);
            key = image;
        }
        key.setContentDescription(description);
        key.setStateListAnimator(null);
        key.setBackground(keyBackground());
        key.setVisibility(View.GONE);
        layer.addView(key, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT));
        keys[index] = key;

        TextView caption = new TextView(layer.getContext());
        caption.setText(label);
        caption.setTextSize(11);
        caption.setTextColor(0xFFD4DBE0);
        caption.setShadowLayer(3 * density, 0, 0, 0xCC000000);
        caption.setGravity(Gravity.CENTER);
        caption.setIncludeFontPadding(false);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        caption.setVisibility(View.GONE);
        layer.addView(caption, new FrameLayout.LayoutParams(1, labelHeight, Gravity.TOP | Gravity.LEFT));
        labels[index] = caption;
    }

    private RippleDrawable keyBackground() {
        int stroke = Math.max(1, Math.round(density));
        GradientDrawable idle = new GradientDrawable();
        idle.setShape(GradientDrawable.OVAL);
        idle.setColor(0xE0202A30);
        idle.setStroke(stroke, 0x38FFFFFF);
        GradientDrawable active = new GradientDrawable();
        active.setShape(GradientDrawable.OVAL);
        active.setColor(0xE625744D);
        active.setStroke(stroke, 0x66EAFFEF);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_activated}, active);
        states.addState(new int[0], idle);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(0x557AC59A), states, mask);
    }

    boolean isOpen() { return open; }

    void show(boolean visible) {
        stopDelete();
        if (!visible) keys[ENTER].cancelLongPress();
        open = visible;
        keys[RIGHT_CLICK].setActivated(visible && host.isRightClickArmed());
        for (int i = 0; i < keys.length; i++) {
            keys[i].setVisibility(visible ? View.VISIBLE : View.GONE);
            labels[i].setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        layer.setClickable(visible);
    }

    boolean position(Rect safe, int mainSize, int mainTop, boolean right) {
        fan.place(safe.width(), safe.height(), mainSize, mainTop-safe.top, right, density, labelHeight);
        if (fan.keySize == 0) return false;
        int gap = Math.max(1, Math.round(7 * density));
        for (int i = 0; i < keys.length; i++) {
            View key = keys[i];
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)key.getLayoutParams();
            if (lp.width != fan.keySize || lp.height != fan.keySize) {
                lp.width = lp.height = fan.keySize;
                key.setLayoutParams(lp);
            }
            float x = safe.left + fan.x[i], y = safe.top + fan.y[i];
            key.setX(x);
            key.setY(y);
            TextView caption = labels[i];
            FrameLayout.LayoutParams clp = (FrameLayout.LayoutParams)caption.getLayoutParams();
            if (clp.width != fan.keySize + gap) {
                clp.width = fan.keySize + gap;
                caption.setLayoutParams(clp);
            }
            caption.setX(x - gap / 2f);
            caption.setY(y + fan.keySize);
        }
        return true;
    }

    private boolean deleteTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopDelete();
                repeated = false;
                holdingDelete = true;
                v.setPressed(true);
                v.postDelayed(repeat, VoiceButtonTouch.HOLD_MS);
                break;
            case MotionEvent.ACTION_MOVE:
                if (e.getX()<0 || e.getY()<0 || e.getX()>=v.getWidth() || e.getY()>=v.getHeight()) stopDelete();
                break;
            case MotionEvent.ACTION_UP:
                boolean tap = holdingDelete && !repeated && e.getX()>=0 && e.getX()<v.getWidth()
                        && e.getY()>=0 && e.getY()<v.getHeight();
                stopDelete();
                if (tap) v.performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                stopDelete();
                break;
            default: break;
        }
        return true;
    }

    private void repeatDelete() {
        if (!holdingDelete) return;
        repeated = true;
        int generation = deleteGeneration;
        host.afterVoiceStop(() -> {
            if (!holdingDelete || generation != deleteGeneration) return;
            host.sendKey(KeyEvent.KEYCODE_DEL, false);
            keys[DELETE].postDelayed(repeat, DELETE_REPEAT_MS);
        });
    }

    private void stopDelete() {
        deleteGeneration++;
        holdingDelete = false;
        keys[DELETE].removeCallbacks(repeat);
        keys[DELETE].setPressed(false);
    }
}
