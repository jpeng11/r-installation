package dev.jpeng.rinstaller;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsActivity extends LocalizedActivity {
    private static final int REQUEST_NOTIFICATIONS = 4301;
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final String STANDARD_APK_MIME =
            "application/vnd.android.package-archive";
    private static final String VENDOR_APK_MIME = "application/apk.1";
    private static final Uri ROUTING_PROBE_URI =
            Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".routing/probe.apk");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private InstallerSettings installerSettings;
    private SettingRow shizukuRow;
    private SettingRow routingRow;
    private SettingRow languageRow;
    private SettingRow appearanceRow;
    private Switch completionToastSwitch;
    private Switch silentInstallSwitch;
    private Switch downloadMonitorSwitch;
    private boolean notificationRequestPending;
    private boolean updatingDownloadMonitorSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installerSettings = new InstallerSettings(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSummaries();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = Ui.screenRoot(this);
        root.addView(Ui.toolbar(this, getString(R.string.settings), true));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 24));
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        addCategory(list, R.string.settings_category_installation);

        shizukuRow = addRow(
                list,
                R.string.settings_backend_title,
                "",
                view -> openShizuku());

        SettingRow completionRow = addSwitchRow(
                list,
                R.string.settings_completion_toast_title,
                R.string.settings_completion_toast_summary,
                installerSettings.isCompletionToastEnabled(),
                enabled -> installerSettings.setCompletionToastEnabled(enabled));
        completionToastSwitch = completionRow.toggle;

        SettingRow silentRow = addSwitchRow(
                list,
                R.string.settings_silent_install_title,
                R.string.settings_silent_install_summary,
                installerSettings.isSilentInstallEnabled(),
                enabled -> installerSettings.setSilentInstallEnabled(enabled));
        silentInstallSwitch = silentRow.toggle;

        addRow(
                list,
                R.string.settings_trusted_sources_title,
                R.string.settings_trusted_sources_summary,
                view -> startActivity(new Intent(this, TrustedSourcesActivity.class)));

        SettingRow downloadMonitorRow = addSwitchRow(
                list,
                R.string.settings_download_monitor_title,
                R.string.settings_download_monitor_summary,
                installerSettings.isDownloadMonitorEnabled(),
                this::setDownloadMonitoring);
        downloadMonitorSwitch = downloadMonitorRow.toggle;

        routingRow = addRow(
                list,
                R.string.settings_default_installer_title,
                R.string.settings_default_installer_summary,
                view -> showInstallerRoutingSetup());

        addCategory(list, R.string.settings_category_language);

        languageRow = addRow(
                list,
                R.string.language_settings_row,
                "",
                view -> showLanguageDialog());

        addCategory(list, R.string.settings_category_appearance);

        appearanceRow = addRow(
                list,
                R.string.settings_appearance_title,
                "",
                view -> showAppearanceDialog());

        addCategory(list, R.string.settings_category_storage);

        addRow(
                list,
                R.string.settings_clear_cache_title,
                R.string.settings_clear_cache_summary,
                view -> clearPayloadCache());

        addCategory(list, R.string.settings_category_about);

        addRow(
                list,
                R.string.about,
                getString(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
                view -> showAbout());

        addRow(
                list,
                R.string.settings_support_title,
                R.string.settings_support_summary,
                view -> openWebPage(getString(R.string.settings_support_url)));
    }

    private void refreshSummaries() {
        if (shizukuRow == null) {
            return;
        }
        shizukuRow.summary.setText(ShizukuBridge.isReady()
                ? R.string.settings_backend_ready
                : R.string.settings_backend_not_ready);
        routingRow.summary.setText(installerRoutingSummary());
        languageRow.summary.setText(languageSummary(AppLanguage.selectedIndex(this)));
        appearanceRow.summary.setText(appearanceSummary(AppAppearance.selectedIndex(this)));
        completionToastSwitch.setChecked(installerSettings.isCompletionToastEnabled());
        silentInstallSwitch.setChecked(installerSettings.isSilentInstallEnabled());
        setDownloadMonitorSwitch(installerSettings.isDownloadMonitorEnabled());
        if (installerSettings.isDownloadMonitorEnabled()
                && notificationsAllowed()
                && ShizukuBridge.isReady()) {
            StoreDownloadMonitorService.start(this);
        }
    }

    private void setDownloadMonitoring(boolean enabled) {
        if (updatingDownloadMonitorSwitch) {
            return;
        }
        if (!enabled) {
            installerSettings.setDownloadMonitorEnabled(false);
            StoreDownloadMonitorService.stop(this);
            Toast.makeText(
                    this,
                    R.string.settings_download_monitor_stopped,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!ShizukuBridge.isReady()) {
            setDownloadMonitorSwitch(false);
            Toast.makeText(
                    this,
                    R.string.settings_download_monitor_requires_shizuku,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!notificationsAllowed()) {
            setDownloadMonitorSwitch(false);
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationRequestPending = true;
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATIONS);
            } else {
                Toast.makeText(
                        this,
                        R.string.settings_download_monitor_requires_notifications,
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        startDownloadMonitoring();
    }

    private void startDownloadMonitoring() {
        try {
            installerSettings.setDownloadMonitorEnabled(true);
            StoreDownloadMonitorService.start(this);
            setDownloadMonitorSwitch(true);
        } catch (RuntimeException exception) {
            installerSettings.setDownloadMonitorEnabled(false);
            setDownloadMonitorSwitch(false);
            Toast.makeText(
                    this,
                    R.string.settings_download_monitor_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean notificationsAllowed() {
        return StoreDownloadMonitorService.notificationsAllowed(this);
    }

    private void setDownloadMonitorSwitch(boolean checked) {
        if (downloadMonitorSwitch == null || downloadMonitorSwitch.isChecked() == checked) {
            return;
        }
        updatingDownloadMonitorSwitch = true;
        downloadMonitorSwitch.setChecked(checked);
        updatingDownloadMonitorSwitch = false;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS || !notificationRequestPending) {
            return;
        }
        notificationRequestPending = false;
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && notificationsAllowed()
                && ShizukuBridge.isReady()) {
            startDownloadMonitoring();
        } else {
            Toast.makeText(
                    this,
                    R.string.settings_download_monitor_requires_notifications,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showLanguageDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.language_settings_title)
                .setSingleChoiceItems(
                        R.array.language_options,
                        AppLanguage.selectedIndex(this),
                        (dialog, which) -> {
                            dialog.dismiss();
                            AppLanguage.select(this, which);
                        })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void showAppearanceDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_appearance_title)
                .setSingleChoiceItems(
                        R.array.appearance_options,
                        AppAppearance.selectedIndex(this),
                        (dialog, which) -> {
                            dialog.dismiss();
                            AppAppearance.select(this, which);
                        })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void showInstallerRoutingSetup() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_default_installer_title)
                .setMessage(R.string.settings_default_installer_guidance)
                .setPositiveButton(R.string.settings_test_installer_routing, (dialog, which) ->
                        testInstallerRouting())
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private CharSequence installerRoutingSummary() {
        RouteResolution standard = resolveInstallerRoute(
                Intent.ACTION_VIEW,
                STANDARD_APK_MIME);
        RouteResolution vendor = resolveInstallerRoute(
                Intent.ACTION_VIEW,
                VENDOR_APK_MIME);
        RouteResolution noMimeInstall = resolveInstallerRoute(
                Intent.ACTION_INSTALL_PACKAGE,
                null);
        if (standard.ours && vendor.ours && noMimeInstall.ours) {
            return getText(R.string.settings_installer_routing_ready);
        }
        if (standard.ours) {
            return getText(R.string.settings_installer_standard_ready);
        }
        if (vendor.ours || noMimeInstall.ours) {
            return getText(R.string.settings_installer_vendor_only);
        }
        if (standard.handlerLabel != null) {
            return getString(
                    R.string.settings_installer_other_handler,
                    standard.handlerLabel);
        }
        return getText(R.string.settings_installer_needs_setup);
    }

    private RouteResolution resolveInstallerRoute(String action, String mimeType) {
        Intent intent = new Intent(action).setData(ROUTING_PROBE_URI);
        if (mimeType != null) {
            intent.setDataAndType(ROUTING_PROBE_URI, mimeType);
        }
        ResolveInfo resolved = getPackageManager().resolveActivity(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved == null || resolved.activityInfo == null) {
            return new RouteResolution(false, null);
        }
        ComponentName component = new ComponentName(
                resolved.activityInfo.packageName,
                resolved.activityInfo.name);
        ComponentName ours = new ComponentName(this, InstallActivity.class);
        if (ours.equals(component)) {
            return new RouteResolution(true, getString(R.string.app_name));
        }
        List<ResolveInfo> candidates = getPackageManager().queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        boolean resolvedIsCandidate = false;
        for (ResolveInfo candidate : candidates) {
            if (candidate.activityInfo != null
                    && component.equals(new ComponentName(
                            candidate.activityInfo.packageName,
                            candidate.activityInfo.name))) {
                resolvedIsCandidate = true;
                break;
            }
        }
        if (!resolvedIsCandidate) {
            return new RouteResolution(false, null);
        }
        CharSequence label = resolved.loadLabel(getPackageManager());
        return new RouteResolution(
                false,
                label == null ? component.getPackageName() : label.toString());
    }

    private void testInstallerRouting() {
        String token = RoutingProbeStore.issue(this);
        Intent route = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(ROUTING_PROBE_URI, STANDARD_APK_MIME)
                .putExtra(InstallActivity.EXTRA_ROUTING_PROBE, token);
        try {
            startActivity(route);
        } catch (RuntimeException exception) {
            RoutingProbeStore.revoke(this, token);
            Toast.makeText(
                    this,
                    R.string.settings_routing_handler_unavailable,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about)
                .setMessage(getString(
                        R.string.settings_about_message,
                        BuildConfig.VERSION_NAME))
                .setPositiveButton(R.string.settings_support_title, (dialog, which) ->
                        openWebPage(getString(R.string.settings_support_url)))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void openShizuku() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
        if (launch == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.settings_backend_title)
                    .setMessage(R.string.settings_shizuku_missing)
                    .setPositiveButton(R.string.close, null)
                    .show();
            return;
        }
        startActivity(launch);
    }

    private void openAppInfo() {
        Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.settings_unable_to_open, Toast.LENGTH_LONG).show();
        }
    }

    private void openWebPage(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.settings_unable_to_open, Toast.LENGTH_LONG).show();
        }
    }

    private void clearPayloadCache() {
        Toast.makeText(this, R.string.settings_clearing_cache, Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            int removed = 0;
            File[] cacheEntries = getCacheDir().listFiles();
            if (cacheEntries != null) {
                for (File entry : cacheEntries) {
                    if (entry.isDirectory()
                            && entry.getName().startsWith("payload-")
                            && deleteTree(entry)) {
                        removed++;
                    }
                }
            }
            int finalRemoved = removed;
            runOnUiThread(() -> Toast.makeText(
                    this,
                    getResources().getQuantityString(
                            R.plurals.settings_cache_removed,
                            finalRemoved,
                            finalRemoved),
                    Toast.LENGTH_LONG).show());
        });
    }

    private boolean deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return false;
            }
            for (File child : children) {
                if (!deleteTree(child)) {
                    return false;
                }
            }
        }
        return file.delete();
    }

    private CharSequence languageSummary(int index) {
        return getText(switch (index) {
            case 1 -> R.string.language_english;
            case 2 -> R.string.language_chinese;
            case 3 -> R.string.language_traditional_chinese;
            default -> R.string.language_system;
        });
    }

    private CharSequence appearanceSummary(int index) {
        return getText(switch (index) {
            case 1 -> R.string.appearance_light;
            case 2 -> R.string.appearance_dark;
            default -> R.string.appearance_system;
        });
    }

    private void addCategory(LinearLayout list, int titleResource) {
        TextView category = Ui.text(this, getString(titleResource), 14);
        category.setTextColor(getColor(R.color.text_secondary));
        category.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        category.setAllCaps(true);
        category.setPadding(
                Ui.dp(this, 24),
                Ui.dp(this, 24),
                Ui.dp(this, 24),
                Ui.dp(this, 8));
        list.addView(category);
    }

    private SettingRow addRow(
            LinearLayout list,
            int titleResource,
            int summaryResource,
            View.OnClickListener listener
    ) {
        return addRow(
                list,
                titleResource,
                summaryResource == 0 ? "" : getString(summaryResource),
                listener);
    }

    private SettingRow addRow(
            LinearLayout list,
            int titleResource,
            CharSequence summary,
            View.OnClickListener listener
    ) {
        SettingRow row = new SettingRow(getString(titleResource), summary);
        row.setOnClickListener(listener);
        list.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private SettingRow addSwitchRow(
            LinearLayout list,
            int titleResource,
            int summaryResource,
            boolean checked,
            SwitchChangeListener listener
    ) {
        SettingRow row = new SettingRow(
                getString(titleResource),
                getString(summaryResource));
        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((button, enabled) -> listener.onChange(enabled));
        row.toggle = toggle;
        row.addView(toggle);
        row.setOnClickListener(view -> toggle.toggle());
        list.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private interface SwitchChangeListener {
        void onChange(boolean enabled);
    }

    private static final class RouteResolution {
        final boolean ours;
        final String handlerLabel;

        RouteResolution(boolean ours, String handlerLabel) {
            this.ours = ours;
            this.handlerLabel = handlerLabel;
        }
    }

    private final class SettingRow extends LinearLayout {
        final TextView summary;
        Switch toggle;

        SettingRow(CharSequence titleText, CharSequence summaryText) {
            super(SettingsActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(Ui.dp(SettingsActivity.this, 76));
            setPadding(
                    Ui.dp(SettingsActivity.this, 24),
                    Ui.dp(SettingsActivity.this, 12),
                    Ui.dp(SettingsActivity.this, 20),
                    Ui.dp(SettingsActivity.this, 12));
            setBackground(Ui.selectableBackground(SettingsActivity.this));
            setClickable(true);
            setFocusable(true);

            LinearLayout copy = new LinearLayout(SettingsActivity.this);
            copy.setOrientation(VERTICAL);
            addView(copy, new LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1));

            TextView title = Ui.text(SettingsActivity.this, titleText.toString(), 17);
            title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            copy.addView(title);

            summary = Ui.text(SettingsActivity.this, summaryText.toString(), 14);
            summary.setTextColor(getColor(R.color.text_secondary));
            summary.setPadding(0, Ui.dp(SettingsActivity.this, 3), 0, 0);
            copy.addView(summary);
        }
    }
}
