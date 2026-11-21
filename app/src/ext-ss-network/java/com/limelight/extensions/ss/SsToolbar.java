// EXTENSION DEVELOPMENT [EXT-SS-NETWORK] [ADDED]
package com.limelight.extensions.ss;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.limelight.R;

import java.util.WeakHashMap;

/**
 * Adds the SS circle button to PcView's home top bar at runtime, right after the
 * gamepad button, in the same style as the other header actions. The ring and
 * glyph take the state colour (grey off / green connected / amber node issue).
 * Re-applied whenever PcView re-inflates its layout.
 */
final class SsToolbar {
    private static final class Binding {
        final Runnable statusListener;
        final ViewTreeObserver.OnGlobalLayoutListener layoutListener;

        Binding(Activity activity) {
            statusListener = () -> update(activity);
            layoutListener = () -> ensureButton(activity);
        }
    }

    private static final WeakHashMap<Activity, Binding> bindings = new WeakHashMap<>();

    private SsToolbar() {}

    static void attach(Activity activity) {
        if (bindings.containsKey(activity)) return;
        Binding binding = new Binding(activity);
        bindings.put(activity, binding);
        activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(binding.layoutListener);
        ensureButton(activity);
    }

    static void resume(Activity activity) {
        Binding binding = bindings.get(activity);
        if (binding == null) return;
        SsRoutes.addListener(binding.statusListener);
        update(activity);
        SsRoutes.probeAsync();
    }

    static void pause(Activity activity) {
        Binding binding = bindings.get(activity);
        if (binding != null) SsRoutes.removeListener(binding.statusListener);
    }

    static void detach(Activity activity) {
        Binding binding = bindings.remove(activity);
        if (binding == null) return;
        SsRoutes.removeListener(binding.statusListener);
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnGlobalLayoutListener(binding.layoutListener);
        SsPanel.release(activity);
    }

    private static void ensureButton(Activity activity) {
        if (activity.findViewById(R.id.ssNetworkButton) != null) return;
        View anchor = activity.findViewById(R.id.axiButton);
        if (anchor == null || !(anchor.getParent() instanceof LinearLayout)) return;
        LinearLayout bar = (LinearLayout) anchor.getParent();

        ImageButton button = new ImageButton(activity);
        button.setId(R.id.ssNetworkButton);
        button.setFocusable(true);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = Math.round(10 * activity.getResources().getDisplayMetrics().density);
        button.setPadding(pad, pad, pad, pad);
        button.setImageDrawable(new SsStatusIcon(activity));
        button.setOnClickListener(v -> SsPanel.of(activity).show());

        ViewGroup.LayoutParams source = anchor.getLayoutParams();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(source.width, source.height);
        if (source instanceof ViewGroup.MarginLayoutParams) {
            params.setMarginStart(((ViewGroup.MarginLayoutParams) source).getMarginStart());
        }
        button.setNextFocusLeftId(anchor.getId());
        button.setNextFocusDownId(anchor.getNextFocusDownId());
        if (anchor.getNextFocusRightId() != View.NO_ID) button.setNextFocusRightId(anchor.getNextFocusRightId());
        anchor.setNextFocusRightId(R.id.ssNetworkButton);
        bar.addView(button, bar.indexOfChild(anchor) + 1, params);
        update(activity);
    }

    private static void update(Activity activity) {
        View view = activity.findViewById(R.id.ssNetworkButton);
        if (!(view instanceof ImageButton)) return;
        ImageButton button = (ImageButton) view;
        SsRoutes.Status status = SsRoutes.status();
        Drawable icon = button.getDrawable();
        if (icon instanceof SsStatusIcon) ((SsStatusIcon) icon).setStatus(status);
        button.setBackground(background(activity, status));
        String label = activity.getString(R.string.ss_toolbar_state, activity.getString(stateLabel(status)));
        button.setContentDescription(label);
        if (android.os.Build.VERSION.SDK_INT >= 26) button.setTooltipText(label);
    }

    static int stateLabel(SsRoutes.Status status) {
        switch (status) {
            case UNAVAILABLE: return R.string.ss_state_unavailable;
            case CHECKING: return R.string.ss_state_checking;
            case ONLINE: return R.string.ss_state_online;
            case DEGRADED: return R.string.ss_state_degraded;
            default: return R.string.ss_state_off;
        }
    }

    /** Same circle as the other header actions; active states tint its ring and fill. */
    private static Drawable background(Context context, SsRoutes.Status status) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                context.getResources().getDrawable(R.drawable.bg_home_circle_button_pressed));
        states.addState(new int[]{android.R.attr.state_focused},
                context.getResources().getDrawable(R.drawable.bg_home_circle_button_focused));
        if (status == SsRoutes.Status.ONLINE || status == SsRoutes.Status.CHECKING
                || status == SsRoutes.Status.DEGRADED) {
            int color = SsStatusIcon.color(context, status);
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor((0x2E << 24) | (color & 0xFFFFFF));
            ring.setStroke(Math.round(1.5f * context.getResources().getDisplayMetrics().density),
                    (0xB3 << 24) | (color & 0xFFFFFF));
            states.addState(new int[0], ring);
        } else {
            states.addState(new int[0], context.getResources().getDrawable(R.drawable.bg_home_circle_button_normal));
        }
        return states;
    }
}
