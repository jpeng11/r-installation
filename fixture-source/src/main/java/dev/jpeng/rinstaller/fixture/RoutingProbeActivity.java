package dev.jpeng.rinstaller.fixture;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Command-line entry point for emulator intent-routing regression tests.
 *
 * <p>The target is package-scoped so another installed package handler cannot turn the test into
 * an interactive resolver chooser. Android still resolves the activity through its intent filters.
 */
public final class RoutingProbeActivity extends Activity {
    public static final String EXTRA_MODE = "mode";
    public static final String MODE_VENDOR_APK_ONE = "vendor-apk-one";
    public static final String MODE_INSTALL_PACKAGE = "install-package";

    private static final String TARGET_PACKAGE = "dev.jpeng.rinstaller";
    private static final Uri PAYLOAD =
            Uri.parse("content://dev.jpeng.rinstaller.fixture.delayed/self.apk");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String mode = getIntent().getStringExtra(EXTRA_MODE);
        Intent request;
        if (MODE_INSTALL_PACKAGE.equals(mode)) {
            request = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(PAYLOAD);
        } else {
            request = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(PAYLOAD, "application/apk.1");
        }

        request.setPackage(TARGET_PACKAGE);
        request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(request);
        finish();
    }
}
