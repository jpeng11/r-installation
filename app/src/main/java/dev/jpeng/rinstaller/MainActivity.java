package dev.jpeng.rinstaller;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int REQUEST_SHIZUKU = 4101;
    private static final int REQUEST_DOCUMENT = 4102;

    private TextView status;
    private Button permissionButton;

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
        LinearLayout page = Ui.page(this);
        page.addView(Ui.title(this, "R Installer Next"));
        page.addView(Ui.text(this,
                "A clean-room Android 16 installer using Shizuku for privileged package sessions. "
                        + "Trusted source apps can hand off APK, APKS, APKM, or XAPK bundles for silent installation.",
                16));

        page.addView(Ui.heading(this, "Privilege status"));
        status = Ui.text(this, "", 16);
        page.addView(status);
        permissionButton = Ui.button(this, "Grant Shizuku permission", view -> requestShizuku());
        page.addView(permissionButton);

        page.addView(Ui.heading(this, "Install"));
        page.addView(Ui.button(this, "Choose APK or bundle", view -> chooseDocument()));
        page.addView(Ui.button(this, "Manage trusted source apps",
                view -> startActivity(new Intent(this, TrustedSourcesActivity.class))));

        page.addView(Ui.heading(this, "Security model"));
        page.addView(Ui.text(this,
                "Silent mode is enabled only when the source is on your allowlist, its signing "
                        + "certificate still matches the pinned certificate, and Android verifies "
                        + "the caller or ownership of the supplied content provider. Referrer strings "
                        + "and package-name extras are never trusted.",
                14));

    }

    private void refreshStatus() {
        runOnUiThread(() -> {
            String value = ShizukuBridge.status();
            status.setText(value);
            permissionButton.setEnabled(!ShizukuBridge.isReady());
        });
    }

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Start Shizuku first.", Toast.LENGTH_LONG).show();
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
