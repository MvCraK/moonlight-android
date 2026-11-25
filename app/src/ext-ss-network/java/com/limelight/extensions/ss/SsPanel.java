// EXTENSION DEVELOPMENT [EXT-SS-NETWORK] [ADDED]
package com.limelight.extensions.ss;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.ui.AppDialog;

import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

/** SS control panel styled like the other home dialogs (AppDialog + home palette). */
final class SsPanel {
    static final int IMPORT = 24032;
    private static final String IMPORT_TAG = "ext-ss-network-import";
    private static final WeakHashMap<Activity, SsPanel> panels = new WeakHashMap<>();

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable listener = this::render;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (dialog == null || !dialog.isShowing()) return;
            if (received != null) received.setText(Formatter.formatShortFileSize(activity, SsRoutes.receivedBytes()));
            main.postDelayed(this, 1000);
        }
    };
    private AlertDialog dialog;
    private View content;
    private TextView received;
    private int busy;          // progress message resource while working, 0 when idle
    private String notice;     // sanitized error or partial-import warning
    private boolean noticeIsError;

    private SsPanel(Activity activity) {
        this.activity = activity;
    }

    static SsPanel of(Activity activity) {
        SsPanel panel = panels.get(activity);
        if (panel == null) {
            panel = new SsPanel(activity);
            panels.put(activity, panel);
        }
        return panel;
    }

    static void release(Activity activity) {
        SsPanel panel = panels.remove(activity);
        if (panel != null) panel.dismiss();
    }

    void show() {
        if (activity.isFinishing() || (dialog != null && dialog.isShowing())) return;
        notice = null;
        noticeIsError = false;
        content = LayoutInflater.from(activity).inflate(R.layout.ss_panel, null);
        received = content.findViewById(R.id.ssPanelReceived);
        ((ImageView) content.findViewById(R.id.ssPanelIcon)).setImageDrawable(new SsStatusIcon(activity));
        View close = content.findViewById(R.id.ssPanelClose);
        View primary = content.findViewById(R.id.ssPanelPrimary);
        View importAction = content.findViewById(R.id.ssPanelImport);
        View refresh = content.findViewById(R.id.ssPanelRefresh);
        View probe = content.findViewById(R.id.ssPanelProbe);
        View modeDirect = content.findViewById(R.id.ssModeDirect);
        View modeRelay = content.findViewById(R.id.ssModeRelay);
        close.setOnClickListener(v -> dismiss());
        primary.setOnClickListener(v -> onPrimary());
        importAction.setOnClickListener(v -> pickConfig());
        refresh.setOnClickListener(v -> perform(R.string.ss_busy_refresh, () -> SsRoutes.refresh(activity, null)));
        probe.setOnClickListener(v -> SsRoutes.probeAsync(true));
        modeDirect.setOnClickListener(v -> selectHomeMode(SsRoutes.HomeMode.DIRECT));
        modeRelay.setOnClickListener(v -> selectHomeMode(SsRoutes.HomeMode.RELAY));

        dialog = AppDialog.createCustomDialog(activity, content, true);
        if (dialog == null) return;
        dialog.setOnDismissListener(d -> {
            SsRoutes.removeListener(listener);
            main.removeCallbacks(ticker);
        });
        SsRoutes.addListener(listener);
        render();
        AppDialog.showCustomDialog(activity, dialog, 0.92f, 480, primary, close,
                close, modeDirect, modeRelay, primary, importAction, refresh, probe);
        main.post(ticker);
        SsRoutes.probeAsync();
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private void onPrimary() {
        if (!SsRoutes.configured()) pickConfig();
        else perform(R.string.ss_busy_toggle, () -> {
            SsRoutes.toggle();
            return null;
        });
    }

    private void selectHomeMode(SsRoutes.HomeMode mode) {
        if (busy != 0 || mode == SsRoutes.homeMode()) return;
        perform(R.string.ss_busy_switch_mode, () -> {
            SsRoutes.setHomeMode(mode);
            return null;
        });
    }

    private void pickConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        FragmentManager fm = activity.getFragmentManager();
        Fragment fragment = fm.findFragmentByTag(IMPORT_TAG);
        if (!(fragment instanceof SsImportFragment)) {
            fragment = new SsImportFragment();
            fm.beginTransaction().add(fragment, IMPORT_TAG).commit();
            fm.executePendingTransactions();
        }
        fragment.startActivityForResult(intent, IMPORT);
    }

    void importConfig(Uri uri) {
        perform(R.string.ss_busy_import, () -> {
            String config;
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("cannot open file");
                config = SsRoutes.readText(in);
            }
            return SsRoutes.refresh(activity, config);
        });
        show();
    }

    private interface Task {
        String run() throws Exception;
    }

    private void perform(int progress, Task task) {
        if (busy != 0) return;
        busy = progress;
        notice = null;
        noticeIsError = false;
        render();
        SsRoutes.worker.execute(() -> {
            String resultNotice = null;
            boolean isError = false;
            try {
                resultNotice = task.run();
            } catch (Throwable t) {
                resultNotice = SsRoutes.describeError(activity, t);
                isError = true;
            }
            final String finalNotice = resultNotice;
            final boolean finalIsError = isError;
            main.post(() -> {
                busy = 0;
                notice = finalNotice;
                noticeIsError = finalIsError;
                render();
            });
        });
    }

    private void render() {
        if (content == null) return;
        SsRoutes.Status status = SsRoutes.status();
        SsRoutes.HomeMode homeMode = SsRoutes.homeMode();
        boolean configured = SsRoutes.configured();
        boolean enabled = SsRoutes.isEnabled();
        boolean available = status != SsRoutes.Status.UNAVAILABLE;
        boolean vpnActive = SsRoutes.isSystemVpnActive();
        boolean hasBypass = SsRoutes.hasBypassNetwork();
        int stateColor = SsStatusIcon.color(activity, status);

        ImageView icon = content.findViewById(R.id.ssPanelIcon);
        if (icon.getDrawable() instanceof SsStatusIcon) ((SsStatusIcon) icon.getDrawable()).setStatus(status);
        dot(content.findViewById(R.id.ssPanelDot), stateColor);
        TextView state = content.findViewById(R.id.ssPanelState);
        String stateText = !configured && available
                ? activity.getString(R.string.ss_state_unconfigured)
                : activity.getString(SsToolbar.stateLabel(status));
        if (configured && enabled) {
            stateText = stateText + " · " + activity.getString(
                    homeMode == SsRoutes.HomeMode.RELAY ? R.string.ss_mode_relay_short : R.string.ss_mode_direct_short);
        }
        state.setText(stateText);
        state.setTextColor(enabled ? stateColor : activity.getResources().getColor(R.color.home_secondary_text));

        // System VPN / FlClash coexistence banner
        TextView vpnBanner = content.findViewById(R.id.ssPanelVpnBanner);
        if (vpnActive) {
            vpnBanner.setVisibility(View.VISIBLE);
            if (!enabled) {
                vpnBanner.setText(R.string.ss_vpn_banner_off);
                vpnBanner.setBackgroundResource(R.drawable.ss_bg_banner_info);
                vpnBanner.setTextColor(activity.getResources().getColor(R.color.home_accent_bright));
            } else if (hasBypass) {
                vpnBanner.setText(R.string.ss_vpn_banner_bypass);
                vpnBanner.setBackgroundResource(R.drawable.ss_bg_banner_info);
                vpnBanner.setTextColor(activity.getResources().getColor(R.color.home_connected));
            } else {
                vpnBanner.setText(R.string.ss_vpn_banner_no_bypass);
                vpnBanner.setBackgroundResource(R.drawable.ss_bg_banner_warn);
                vpnBanner.setTextColor(activity.getResources().getColor(R.color.home_warning));
            }
        } else {
            vpnBanner.setVisibility(View.GONE);
        }

        content.findViewById(R.id.ssPanelEmpty).setVisibility(configured ? View.GONE : View.VISIBLE);
        content.findViewById(R.id.ssPanelContent).setVisibility(configured ? View.VISIBLE : View.GONE);
        content.findViewById(R.id.ssPanelSecondary).setVisibility(configured ? View.VISIBLE : View.GONE);
        if (configured) {
            renderHomeModeSelector(homeMode, available && busy == 0);
            renderNodes(enabled);
        }

        received.setText(Formatter.formatShortFileSize(activity, SsRoutes.receivedBytes()));
        long updated = SsRoutes.updated();
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(),
                DateFormat.is24HourFormat(activity) ? "MMdHHmm" : "MMdhhmma");
        ((TextView) content.findViewById(R.id.ssPanelUpdated)).setText(updated > 0
                ? DateFormat.format(pattern, new Date(updated)) : "—");

        TextView primary = content.findViewById(R.id.ssPanelPrimary);
        primary.setText(!configured ? R.string.ss_action_import
                : enabled ? R.string.ss_action_disconnect : R.string.ss_action_connect);
        primary.setBackgroundResource(enabled ? R.drawable.ss_bg_disconnect : R.drawable.ss_bg_primary);
        primary.setTextColor(enabled ? activity.getResources().getColor(R.color.home_destructive) : 0xFFFFFFFF);
        primary.setEnabled(available && busy == 0);
        primary.setAlpha(primary.isEnabled() ? 1f : 0.5f);
        setActionEnabled(R.id.ssPanelImport, available && busy == 0);
        setActionEnabled(R.id.ssPanelRefresh, available && busy == 0 && SsRoutes.hasSubscriptionUrls());
        setActionEnabled(R.id.ssPanelProbe, enabled && !SsRoutes.isProbing());

        TextView message = content.findViewById(R.id.ssPanelMessage);
        if (busy != 0 || (notice != null && !notice.isEmpty())) {
            message.setVisibility(View.VISIBLE);
            message.setText(busy != 0 ? activity.getString(busy) : notice);
            int colorRes = busy != 0 ? R.color.home_secondary_text
                    : (noticeIsError ? R.color.home_destructive : R.color.home_warning);
            message.setTextColor(activity.getResources().getColor(colorRes));
        } else {
            message.setVisibility(View.GONE);
        }
    }

    private void renderHomeModeSelector(SsRoutes.HomeMode activeMode, boolean interactive) {
        boolean hasDirect = SsRoutes.hasDirectHomeNodes();
        boolean hasRelay = SsRoutes.hasRelayHomeNodes();
        updateModeSegment(
                content.findViewById(R.id.ssModeDirect),
                content.findViewById(R.id.ssModeDirectTitle),
                content.findViewById(R.id.ssModeDirectSub),
                activeMode == SsRoutes.HomeMode.DIRECT,
                hasDirect,
                interactive,
                hasDirect ? R.string.ss_mode_direct_sub : R.string.ss_mode_unconfigured);
        updateModeSegment(
                content.findViewById(R.id.ssModeRelay),
                content.findViewById(R.id.ssModeRelayTitle),
                content.findViewById(R.id.ssModeRelaySub),
                activeMode == SsRoutes.HomeMode.RELAY,
                hasRelay,
                interactive,
                hasRelay ? R.string.ss_mode_relay_sub : R.string.ss_mode_unconfigured);
    }

    private void updateModeSegment(View container, TextView title, TextView sub,
                                   boolean selected, boolean available, boolean interactive, int subRes) {
        container.setBackgroundResource(selected ? R.drawable.ss_bg_segment_selected : R.drawable.ss_bg_segment_normal);
        container.setEnabled(interactive);
        container.setAlpha(available ? 1f : 0.55f);
        title.setTextColor(activity.getResources().getColor(
                selected ? R.color.home_accent_bright : R.color.home_primary_text));
        sub.setText(subRes);
        sub.setTextColor(activity.getResources().getColor(
                selected ? R.color.home_primary_text : R.color.home_secondary_text));
    }

    private void setActionEnabled(int id, boolean enabled) {
        View view = content.findViewById(id);
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.45f);
    }

    private void renderNodes(boolean enabled) {
        LinearLayout list = content.findViewById(R.id.ssPanelNodes);
        List<SsRoutes.Node> nodes = SsRoutes.nodes();
        List<SsRoutes.Probe> probes = SsRoutes.probes();
        boolean probing = SsRoutes.isProbing();
        LayoutInflater inflater = LayoutInflater.from(activity);
        while (list.getChildCount() > nodes.size()) list.removeViewAt(list.getChildCount() - 1);
        while (list.getChildCount() < nodes.size()) list.addView(inflater.inflate(R.layout.ss_node_row, list, false));
        int off = activity.getResources().getColor(R.color.home_secondary_text);
        int online = activity.getResources().getColor(R.color.home_connected);
        int warning = activity.getResources().getColor(R.color.home_warning);
        for (int i = 0; i < nodes.size(); i++) {
            View row = list.getChildAt(i);
            SsRoutes.Node node = nodes.get(i);
            SsRoutes.Probe probe = probes != null && i < probes.size() ? probes.get(i) : null;
            ((TextView) row.findViewById(R.id.ssNodeName)).setText(node.labelRes);
            ((TextView) row.findViewById(R.id.ssNodeHost)).setText(node.host + ":" + node.port);
            TextView latency = row.findViewById(R.id.ssNodeLatency);
            int color;
            if (!enabled) {
                color = off;
                latency.setText(R.string.ss_latency_idle);
            } else if (probe != null && probe.skipped) {
                color = off;
                latency.setText(R.string.ss_latency_skipped);
            } else if (probe == null) {
                color = off;
                latency.setText(probing ? R.string.ss_latency_checking : R.string.ss_latency_idle);
            } else if (probe.latencyMs < 0) {
                color = warning;
                latency.setText(R.string.ss_latency_unreachable);
            } else {
                color = online;
                latency.setText(activity.getString(R.string.ss_latency_ms, (int) probe.latencyMs));
            }
            dot(row.findViewById(R.id.ssNodeDot), color);
            latency.setTextColor(color);
        }
    }

    private static void dot(View view, int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        view.setBackground(drawable);
    }
}
