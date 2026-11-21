package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONArray;
import com.limelight.BuildConfig;
import com.limelight.R;
import com.limelight.ui.AppDialog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class UpdateChecker {

    private static final String UPDATE_CONFIG_URL = "https://raw.githubusercontent.com/MvCraK/moonlight-android/updates/axixi.json";
    private static final String RELEASES = "https://github.com/MvCraK/moonlight-android/releases/download/";
    private static final String PREF_SKIPPED_UPDATE_CODE = "pref_skipped_update_code";

    private static final OkHttpClient CLIENT = new OkHttpClient();

    private UpdateChecker() {
    }

    public interface VersionHistoryCallback {
        void onLoaded(List<VersionEntry> entries);

        void onError();
    }

    public static final class VersionEntry {
        private final int code;
        private final String versionName;
        private final String description;
        private final boolean latest;

        private VersionEntry(int code, String versionName, String description, boolean latest) {
            this.code = code;
            this.versionName = versionName;
            this.description = description;
            this.latest = latest;
        }

        public int getCode() {
            return code;
        }

        public String getVersionName() {
            return versionName;
        }

        public String getDescription() {
            return description;
        }

        public boolean isLatest() {
            return latest;
        }
    }

    private interface UpdateConfigCallback {
        void onLoaded(UpdateResponse response);

        void onError(boolean parseError);
    }

    public static void checkForUpdates(Activity activity, boolean interactive) {
        SpinnerDialog spinner = null;
        if (interactive) {
            spinner = SpinnerDialog.displayDialog(activity, "检查更新", "正在获取版本信息...", false);
        }

        SpinnerDialog finalSpinner = spinner;
        fetchUpdateConfig(new UpdateConfigCallback() {
            @Override
            public void onLoaded(UpdateResponse updateResponse) {
                runOnUiThreadSafely(activity, () -> {
                    dismissSpinner(finalSpinner);
                    handleUpdateResponse(activity, updateResponse, interactive);
                });
            }

            @Override
            public void onError(boolean parseError) {
                runOnUiThreadSafely(activity, () -> {
                    dismissSpinner(finalSpinner);
                    if (interactive) {
                        showToast(activity, parseError
                                ? "更新信息解析失败"
                                : "检查更新失败");
                    }
                });
            }
        });
    }

    public static void loadVersionHistory(Activity activity, VersionHistoryCallback callback) {
        if (activity == null || callback == null) {
            return;
        }
        fetchUpdateConfig(new UpdateConfigCallback() {
            @Override
            public void onLoaded(UpdateResponse response) {
                runOnUiThreadSafely(activity, () -> {
                    if (response == null || response.data == null) {
                        callback.onLoaded(new ArrayList<>());
                        return;
                    }
                    List<VersionEntry> entries;
                    try {
                        entries = buildVersionEntries(response.data);
                    } catch (RuntimeException ignored) {
                        callback.onError();
                        return;
                    }
                    callback.onLoaded(entries);
                });
            }

            @Override
            public void onError(boolean parseError) {
                runOnUiThreadSafely(activity, callback::onError);
            }
        });
    }

    private static void fetchUpdateConfig(UpdateConfigCallback callback) {
        Request request = new Request.Builder()
                .url(UPDATE_CONFIG_URL)
                .get()
                .build();
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(false);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError(false);
                        return;
                    }
                    UpdateResponse updateResponse = parseUpdateResponse(response.body().string());
                    if (updateResponse == null) {
                        callback.onError(true);
                        return;
                    }
                    callback.onLoaded(updateResponse);
                } catch (Exception e) {
                    callback.onError(true);
                }
            }
        });
    }

    private static List<VersionEntry> buildVersionEntries(UpdateData data) {
        List<VersionEntry> entries = new ArrayList<>();
        Set<Integer> seenCodes = new HashSet<>();
        addVersionEntry(entries, seenCodes, data.latest, true);
        if (data.history != null) {
            for (UpdateRelease release : data.history) {
                addVersionEntry(entries, seenCodes, release, false);
            }
        }
        entries.sort((left, right) -> Integer.compare(right.code, left.code));
        return entries;
    }

    private static void addVersionEntry(List<VersionEntry> entries,
                                        Set<Integer> seenCodes,
                                        UpdateRelease release,
                                        boolean latest) {
        if (release == null || release.code <= 0 || !seenCodes.add(release.code)) {
            return;
        }
        entries.add(new VersionEntry(
                release.code,
                safeText(release.versionName, ""),
                normalizeDescription(release.desc),
                latest));
    }

    private static void handleUpdateResponse(Activity activity, UpdateResponse updateResponse, boolean interactive) {
        UpdateRelease latest = null;
        if (updateResponse != null && updateResponse.data != null) {
            latest = updateResponse.data.latest;
        }

        if (latest == null || latest.code <= 0) {
            if (interactive) {
                showToast(activity, "未找到可用更新信息");
            }
            return;
        }

        if (latest.code <= BuildConfig.VERSION_CODE) {
            if (interactive) {
                showToast(activity, "当前已是最新版本");
            }
            return;
        }

        if (!interactive && latest.code <= getSkippedUpdateCode(activity)) {
            return;
        }

        showUpdateDialog(activity, latest, normalizeDescription(latest.desc), interactive);
    }

    private static void showUpdateDialog(Activity activity, UpdateRelease latest, String description, boolean interactive) {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_update_prompt, null, false);

        TextView versionsView = dialogView.findViewById(R.id.tv_update_versions);
        TextView descView = dialogView.findViewById(R.id.tv_update_desc);
        View skipRow = dialogView.findViewById(R.id.layout_skip_row);
        View skipButton = dialogView.findViewById(R.id.btn_skip_update);
        View cancelButton = dialogView.findViewById(R.id.btn_cancel_update);
        View downloadButton = dialogView.findViewById(R.id.btn_download_update);

        versionsView.setText("当前版本：" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                + "\n最新版本：" + safeText(latest.versionName, "未知版本") + " (" + latest.code + ")");
        descView.setText(TextUtils.isEmpty(description) ? "暂无更新说明。" : description);
        skipRow.setVisibility(interactive ? View.GONE : View.VISIBLE);

        AlertDialog dialog = buildDialog(activity, dialogView);
        if (dialog == null) {
            return;
        }

        skipButton.setOnClickListener(v -> {
            saveSkippedUpdateCode(activity, latest.code);
            dialog.dismiss();
        });
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        downloadButton.setOnClickListener(v -> {
            dialog.dismiss();
            showDownloadOptions(activity, latest);
        });

        showDialog(activity, dialog, cancelButton, cancelButton, skipButton, cancelButton, downloadButton);
    }

    private static void showDownloadOptions(Activity activity, UpdateRelease latest) {
        List<String> labels = new ArrayList<>();
        List<String> urls = new ArrayList<>();

        addDownloadOption(labels, urls, "GitHub Releases", latest.github);

        if (urls.isEmpty()) {
            showToast(activity, "当前没有可用下载地址");
            return;
        }

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_update_channels, null, false);
        LinearLayout channelContainer = dialogView.findViewById(R.id.layout_download_channels);
        View cancelButton = dialogView.findViewById(R.id.btn_cancel_channels);

        for (int i = 0; i < labels.size(); i++) {
            View channelView = LayoutInflater.from(activity).inflate(R.layout.item_update_channel, channelContainer, false);
            TextView channelNameView = channelView.findViewById(R.id.tv_channel_name);
            String label = labels.get(i);
            String url = urls.get(i);
            channelNameView.setText(label);
            channelView.setTag(url);
            channelContainer.addView(channelView);
        }

        AlertDialog dialog = buildDialog(activity, dialogView);
        if (dialog == null) {
            return;
        }

        for (int i = 0; i < channelContainer.getChildCount(); i++) {
            View channelItem = channelContainer.getChildAt(i);
            channelItem.setFocusable(true);
            channelItem.setFocusableInTouchMode(true);
            channelItem.setOnClickListener(v -> {
                dialog.dismiss();
                Object taggedUrl = v.getTag();
                if (taggedUrl instanceof String) {
                    openUrl(activity, (String) taggedUrl);
                }
            });
        }
        cancelButton.setOnClickListener(v -> dialog.dismiss());

        View initialFocus = channelContainer.getChildCount() > 0
                ? channelContainer.getChildAt(0) : cancelButton;
        View[] actions = new View[channelContainer.getChildCount() + 1];
        for (int i = 0; i < channelContainer.getChildCount(); i++) {
            actions[i] = channelContainer.getChildAt(i);
        }
        actions[actions.length - 1] = cancelButton;
        showDialog(activity, dialog, initialFocus, cancelButton, actions);
    }

    private static AlertDialog buildDialog(Activity activity, View dialogView) {
        if (activity == null || activity.isFinishing()) {
            return null;
        }

        return AppDialog.createCustomDialog(activity, dialogView, true);
    }

    private static void showDialog(Activity activity,
                                   AlertDialog dialog,
                                   View initialFocus,
                                   View dismissAction,
                                   View... actionViews) {
        boolean portrait = activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        AppDialog.showCustomDialog(
                activity,
                dialog,
                portrait ? 0.88f : 0.68f,
                portrait ? 520 : 440,
                initialFocus,
                dismissAction,
                actionViews);
    }

    private static void addDownloadOption(List<String> labels, List<String> urls, String label, String url) {
        if (!TextUtils.isEmpty(url)) {
            labels.add(label);
            urls.add(url);
        }
    }

    private static void dismissSpinner(SpinnerDialog spinner) {
        if (spinner != null) {
            spinner.dismiss();
        }
    }

    private static int getSkippedUpdateCode(Activity activity) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        return prefs.getInt(PREF_SKIPPED_UPDATE_CODE, 0);
    }

    private static void saveSkippedUpdateCode(Activity activity, int code) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        prefs.edit().putInt(PREF_SKIPPED_UPDATE_CODE, code).apply();
    }

    private static UpdateResponse parseUpdateResponse(String responseBody) {
        try {
            JSONObject manifest = new JSONObject(responseBody);
            if (!BuildConfig.APPLICATION_ID.equals(manifest.getString("packageName"))) return null;
            JSONObject latest = manifest.getJSONObject("latest");
            UpdateResponse response = new UpdateResponse();
            response.data = new UpdateData();
            response.data.latest = readRelease(latest);
            JSONArray history = manifest.optJSONArray("history");
            if (history != null) {
                response.data.history = new UpdateRelease[history.length()];
                for (int i = 0; i < history.length(); i++) {
                    response.data.history[i] = readRelease(history.getJSONObject(i));
                }
            }
            return response;
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private static UpdateRelease readRelease(JSONObject entry) throws org.json.JSONException {
        UpdateRelease release = new UpdateRelease();
        release.code = entry.getInt("versionCode");
        release.versionName = entry.getString("versionName");
        release.desc = entry.optString("notes");
        String artifact = "axixi-" + release.code;
        release.github = RELEASES + artifact + "/" + artifact + ".apk";
        if (release.code < 1 || !release.github.equals(entry.getString("apkUrl"))) {
            throw new org.json.JSONException("Invalid Axixi release");
        }
        return release;
    }

    private static String normalizeDescription(String description) {
        if (TextUtils.isEmpty(description)) {
            return "";
        }
        return description
                .replace("\\n", "\n")
                .replace("\r\n", "\n")
                .trim();
    }

    private static String safeText(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    public static void openUrl(Activity activity, String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        if (intent.resolveActivity(activity.getPackageManager()) != null) {
            activity.startActivity(intent);
        } else {
            showToast(activity, "未找到可用浏览器");
        }
    }

    private static void runOnUiThreadSafely(Activity activity, Runnable runnable) {
        if (activity.isFinishing()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
            return;
        }
        activity.runOnUiThread(runnable);
    }

    private static void showToast(Activity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    private static final class UpdateResponse {
        private UpdateData data;
    }

    private static final class UpdateData {
        private UpdateRelease latest;
        private UpdateRelease[] history;
    }

    private static final class UpdateRelease {
        private int code;
        private String versionName;
        private String desc;
        private String github;
    }
}
