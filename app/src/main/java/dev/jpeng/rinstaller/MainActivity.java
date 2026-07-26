package dev.jpeng.rinstaller;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int REQUEST_SHIZUKU = 4101;
    private static final int REQUEST_DOCUMENT = 4102;
    private static final int MENU_SETTINGS = 1;
    private static final int MENU_ABOUT = 2;

    private TextView status;
    private TextView statusDetail;
    private TextView trustedCount;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::refreshStatus;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::refreshStatus;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> {
                if (requestCode == REQUEST_SHIZUKU) {
                    refreshStatus();
                    if (grantResult != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "Shizuku permission was not granted.", Toast.LENGTH_LONG).show();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        Shizuku.addBinderReceivedListener(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = Ui.screenRoot(this);
        Toolbar toolbar = Ui.toolbar(this, getString(R.string.app_name), false);
        toolbar.getMenu().add(0, MENU_SETTINGS, 0, R.string.settings)
                .setIcon(R.drawable.ic_settings)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        toolbar.getMenu().add(0, MENU_ABOUT, 1, R.string.about)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        toolbar.setOnMenuItemClickListener(item -> {
            showAbout(item.getItemId() == MENU_SETTINGS
                    ? R.string.settings
                    : R.string.about);
            return true;
        });
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 8), Ui.dp(this, 8),
                Ui.dp(this, 8), Ui.dp(this, 24));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout statusCard = Ui.homeCard(
                this,
                R.drawable.bg_status_card,
                R.drawable.bg_icon_blue,
                R.drawable.ic_check_circle,
                "",
                "",
                view -> requestShizuku());
        LinearLayout statusCopy = (LinearLayout) statusCard.getChildAt(1);
        status = (TextView) statusCopy.getChildAt(0);
        statusDetail = (TextView) statusCopy.getChildAt(1);
        status.setTextColor(Color.WHITE);
        statusDetail.setTextColor(Color.rgb(212, 240, 255));
        page.addView(statusCard, cardParams(0));

        LinearLayout trustedCard = Ui.homeCard(
                this,
                R.drawable.bg_home_card,
                R.drawable.bg_icon_indigo,
                R.drawable.ic_apps,
                "",
                getString(R.string.manage_authorized_apps),
                view -> startActivity(new Intent(this, TrustedSourcesActivity.class)));
        LinearLayout trustedCopy = (LinearLayout) trustedCard.getChildAt(1);
        trustedCount = (TextView) trustedCopy.getChildAt(0);
        page.addView(trustedCard, cardParams(8));

        LinearLayout installCard = Ui.homeCard(
                this,
                R.drawable.bg_home_card,
                R.drawable.bg_icon_teal,
                R.drawable.ic_install,
                getString(R.string.choose_package_title),
                getString(R.string.choose_package_summary),
                view -> chooseDocument());
        page.addView(installCard, cardParams(8));
    }

    private LinearLayout.LayoutParams cardParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, topMargin);
        return params;
    }

    private void showAbout(int titleResource) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(titleResource)
                .setMessage(R.string.about_message)
                .setPositiveButton(R.string.manage, (dialog, which) ->
                        startActivity(new Intent(this, TrustedSourcesActivity.class)))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void refreshStatus() {
        runOnUiThread(() -> {
            boolean ready = ShizukuBridge.isReady();
            status.setText(ready
                    ? R.string.shizuku_authorized
                    : R.string.shizuku_not_authorized);
            statusDetail.setText(ready
                    ? getString(R.string.shizuku_type, Shizuku.getUid())
                    : getString(R.string.shizuku_tap));
            int count = new TrustedStore(this).packages().size();
            trustedCount.setText(getResources().getQuantityString(
                    R.plurals.authorized_apps, count, count));
        });
    }

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, R.string.start_shizuku_first, Toast.LENGTH_LONG).show();
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refreshStatus();
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                Toast.makeText(this,
                        "Permission was denied permanently. Enable this app in Shizuku’s authorized apps.",
                        Toast.LENGTH_LONG).show();
            } else {
                Shizuku.requestPermission(REQUEST_SHIZUKU);
            }
        } catch (RuntimeException exception) {
            Toast.makeText(this, "Shizuku error: " + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void chooseDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive",
                "application/zip",
                "application/octet-stream"
        });
        startActivityForResult(intent, REQUEST_DOCUMENT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DOCUMENT || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
                && (data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {
                // The short-lived grant is enough for the immediate installation.
            }
        }
        Intent install = new Intent(this, InstallActivity.class)
                .setData(uri)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(install);
    }
}
