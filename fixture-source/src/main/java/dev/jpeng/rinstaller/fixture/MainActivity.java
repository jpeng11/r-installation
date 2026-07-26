package dev.jpeng.rinstaller.fixture;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(48, 48, 48, 48);

        TextView explanation = new TextView(this);
        explanation.setText("This debug-only fixture shares its own APK through a content provider "
                + "and launches R Installer Next with Android caller identity sharing enabled.");
        explanation.setTextSize(18);
        page.addView(explanation);

        addButton(page, "Run trusted explicit silent-install test", this::runExplicitTest);
        addButton(page, "Route implicit INSTALL_PACKAGE without MIME",
                this::runImplicitInstallPackageTest);
        addButton(page, "Route implicit VIEW with application/apk.1",
                this::runImplicitApkOneTest);
        setContentView(page);
    }

    private void addButton(LinearLayout page, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        page.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void runExplicitTest() {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(
                        "dev.jpeng.rinstaller",
                        "dev.jpeng.rinstaller.InstallActivity"))
                .setDataAndType(payload(), "application/vnd.android.package-archive");
        launch(intent);
    }

    private void runImplicitInstallPackageTest() {
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(payload());
        launch(intent);
    }

    private void runImplicitApkOneTest() {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(payload(), "application/apk.1");
        launch(intent);
    }

    private Uri payload() {
        return Uri.parse("content://dev.jpeng.rinstaller.fixture.payload/self.apk");
    }

    private void launch(Intent intent) {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setShareIdentityEnabled(true);
        startActivity(intent, options.toBundle());
    }
}
