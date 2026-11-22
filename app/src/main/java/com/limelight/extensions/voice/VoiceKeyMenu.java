// EXTENSION DEVELOPMENT [EXT-REMOTE-VOICE] [ADDED]
package com.limelight.extensions.voice;

import com.limelight.R;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;

/** Four local controls. Only confirmed, complete keystrokes are sent to the stream. */
final class VoiceKeyMenu {
    interface Host {
        void afterVoiceStop(Runnable action);
        void sendKey(int androidKeyCode, boolean withShift);
    }

    private final Host host;
    private final FrameLayout layer;
    private final View[] keys = new View[4];
    private final VoiceKeyFan fan = new VoiceKeyFan();
    private final float density;
    private boolean open, holdingDelete, repeated;
    private int deleteGeneration;
    static final int DELETE_REPEAT_MS = 60;
    private final Runnable repeat = this::repeatDelete;

    VoiceKeyMenu(FrameLayout layer, Host host) {
        this.layer = layer;
        this.host = host;
        density = layer.getResources().getDisplayMetrics().density;
        keys[0] = add("esc", "Escape", KeyEvent.KEYCODE_ESCAPE, 0);
        keys[1] = add(null, "Tab", KeyEvent.KEYCODE_TAB, R.drawable.ic_voice_tab);
        keys[2] = add(null, "退格，按住连续删除", KeyEvent.KEYCODE_DEL, R.drawable.ic_voice_delete);
        keys[3] = add(null, "回车，长按换行（Shift+回车）", KeyEvent.KEYCODE_ENTER, R.drawable.ic_voice_enter);
        keys[2].setOnTouchListener(this::deleteTouch);
        keys[3].setOnLongClickListener(v -> {
            if (open) host.afterVoiceStop(() -> host.sendKey(KeyEvent.KEYCODE_ENTER, true));
            return true;
        });
    }

    private View add(String label, String description, int code, int icon) {
        View key;
        if (icon == 0) {
            Button textButton = new Button(layer.getContext());
            textButton.setAllCaps(false);
            textButton.setText(label);
            textButton.setTypeface(Typeface.MONOSPACE);
            textButton.setTextColor(Color.WHITE);
            textButton.setTextSize(15);
            textButton.setIncludeFontPadding(false);
            textButton.setGravity(Gravity.CENTER);
            textButton.setMinHeight(0);
            textButton.setMinWidth(0);
            key = textButton;
        } else {
            ImageButton iconButton = new ImageButton(layer.getContext());
            iconButton.setImageResource(icon);
            iconButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            key = iconButton;
        }
        key.setContentDescription(description);
        key.setStateListAnimator(null);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xe6283134);
        shape.setShape(GradientDrawable.OVAL);
        shape.setStroke(Math.max(1, Math.round(density)), 0x34ffffff);
        key.setBackground(new RippleDrawable(ColorStateList.valueOf(0x447ac59a), shape, null));
        int pad = icon == 0 ? Math.round(5 * density) : 0;
        key.setPadding(0, pad, 0, pad);
        key.setOnClickListener(v -> host.afterVoiceStop(() -> host.sendKey(code, false)));
        key.setVisibility(View.GONE);
        layer.addView(key, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT));
        return key;
    }

    boolean isOpen() { return open; }

    void show(boolean visible) {
        stopDelete();
        if (!visible) keys[3].cancelLongPress();
        open = visible;
        for (View key : keys) key.setVisibility(visible ? View.VISIBLE : View.GONE);
        layer.setClickable(visible);
    }

    boolean position(Rect safe, int mainSize, int mainTop, boolean right) {
        fan.place(safe.width(), safe.height(), mainSize, mainTop-safe.top, right, density);
        if (fan.keySize == 0) return false;
        for (int i = 0; i < keys.length; i++) {
            View key = keys[i];
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)key.getLayoutParams();
            if (lp.width != fan.keySize || lp.height != fan.keySize) {
                lp.width = lp.height = fan.keySize;
                key.setLayoutParams(lp);
            }
            key.setX(safe.left + fan.x[i]);
            key.setY(safe.top + fan.y[i]);
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
            keys[2].postDelayed(repeat, DELETE_REPEAT_MS);
        });
    }

    private void stopDelete() {
        deleteGeneration++;
        holdingDelete = false;
        keys[2].removeCallbacks(repeat);
        keys[2].setPressed(false);
    }
}
