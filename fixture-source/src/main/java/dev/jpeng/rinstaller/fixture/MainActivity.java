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

        Button launch = new Button(this);
        launch.setText("Run trusted silent-install test");
        launch.setAllCaps(false);
        launch.setOnClickListener(view -> runTest());
        page.addView(launch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(page);
    }

    private void runTest() {
        Uri payload = Uri.parse("content://dev.jpeng.rinstaller.fixture.payload/self.apk");
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(
                        "dev.jpeng.rinstaller",
                        "dev.jpeng.rinstaller.InstallActivity"))
                .setDataAndType(payload, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setShareIdentityEnabled(true);
        startActivity(intent, options.toBundle());
    }
}
