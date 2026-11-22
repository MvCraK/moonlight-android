// EXTENSION DEVELOPMENT [EXT-REMOTE-VOICE] [ADDED]
package com.limelight.extensions.voice;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One overlay per Game activity. Game owns stream lifecycle, input state and recording permission. */
public final class VoiceInputExtension {
    public interface Host {
        boolean canSendInput();
        boolean prepareMicrophone();
        byte modifiers();
    }

    private View button;
    private RemoteVoiceInput input;
    private VoiceButtonTouch touch;
    private VoiceScreenTouch screen;

    public void attach(Activity activity, NvConnection stream, KeyboardTranslator keyboard,
                       View.OnTouchListener streamTouch, Host host) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        if (!prefs.getBoolean("checkbox_voice_input_enabled", true)) return;

        button = activity.findViewById(R.id.voiceInputButton);
        ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), runnable -> new Thread(runnable, "VoiceGesture"));
        input = new RemoteVoiceInput(new RemoteVoiceInput.Host() {
            @Override public boolean begin(AtomicBoolean cancelled, Runnable disconnected) {
                return stream.beginVoiceGesture(cancelled, disconnected);
            }
            @Override public boolean end() { return stream.endVoiceGesture(); }
            @Override public void onUi(Runnable callback) { activity.runOnUiThread(callback); }
            @Override public void show(boolean waiting, boolean speaking) { touch.show(waiting, speaking); }
            @Override public void clickUnconfirmed() {
                Toast.makeText(activity, "Mac 未确认语音结束，请重试", Toast.LENGTH_SHORT).show();
            }
            @Override public void disconnected() {
                Toast.makeText(activity, "远程语音连接已断开，点击可重新开始", Toast.LENGTH_SHORT).show();
            }
            @Override public void failed() {
                Toast.makeText(activity, "远程语音失败：" + stream.getLastMicUplinkMessage(), Toast.LENGTH_LONG).show();
            }
        }, worker);
        screen = new VoiceScreenTouch(input, streamTouch::onTouch, ViewConfiguration.get(activity).getScaledTouchSlop());
        touch = new VoiceButtonTouch(button, prefs, () -> {
            if (input.isRequested() || (host.canSendInput() && host.prepareMicrophone())) {
                input.tap(SystemClock.uptimeMillis());
            }
        }, new VoiceKeyMenu.Host() {
            @Override public void afterVoiceStop(Runnable action) { afterStop(action); }
            @Override public void sendKey(int code, boolean withShift) {
                if (host.canSendInput()) sendShortcut(stream, keyboard, code, withShift, host.modifiers());
            }
        });
    }

    public boolean isEnabled() { return input != null; }
    public boolean isBusy() { return isEnabled() && input.isBusy(); }
    public boolean isRecording() { return isEnabled() && button.isActivated(); }

    public void reset() {
        if (!isEnabled()) return;
        screen.reset();
        touch.reset();
        input.cancel();
    }

    public void setVisible(boolean visible) {
        if (!isEnabled()) return;
        View layer = (View) button.getParent();
        if (!visible && layer.getVisibility() == View.VISIBLE) reset();
        layer.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public boolean dispatchTouch(View view, MotionEvent event) {
        return screen.dispatch(view, event);
    }

    public void afterStop(Runnable action) {
        if (isBusy()) {
            input.beforeScreenClick(confirmed -> {
                if (confirmed) action.run();
            });
        } else {
            action.run();
        }
    }

    public void sendMousePosition(NvConnection stream, short x, short y, short width, short height) {
        if (isEnabled() && input.hasPendingInput()) {
            afterStop(() -> stream.sendMousePosition(x, y, width, height));
        } else {
            stream.sendMousePosition(x, y, width, height);
        }
    }

    public void sendMouseButton(NvConnection stream, byte key, boolean pressed) {
        if (pressed) afterStop(() -> stream.sendMouseButtonDown(key));
        else if (isEnabled() && input.hasPendingInput()) afterStop(() -> stream.sendMouseButtonUp(key));
        else stream.sendMouseButtonUp(key);
    }

    private static void sendShortcut(NvConnection stream, KeyboardTranslator keyboard, int code,
                                     boolean withShift, byte modifiers) {
        short key = keyboard.translate(code, -1);
        if (key == 0) return;
        boolean pressShift = withShift && (modifiers & KeyboardPacket.MODIFIER_SHIFT) == 0;
        byte activeModifiers = modifiers;
        short shift = 0;
        if (pressShift) {
            shift = keyboard.translate(KeyEvent.KEYCODE_SHIFT_LEFT, -1);
            activeModifiers |= KeyboardPacket.MODIFIER_SHIFT;
            stream.sendKeyboardInput(shift, KeyboardPacket.KEY_DOWN, activeModifiers, (byte) 0);
        }
        stream.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, activeModifiers, (byte) 0);
        stream.sendKeyboardInput(key, KeyboardPacket.KEY_UP, activeModifiers, (byte) 0);
        if (pressShift) stream.sendKeyboardInput(shift, KeyboardPacket.KEY_UP, modifiers, (byte) 0);
    }
}
