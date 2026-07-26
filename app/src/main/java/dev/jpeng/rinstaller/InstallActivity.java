package dev.jpeng.rinstaller;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import dev.jpeng.rinstaller.service.PrivilegedInstallerService;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class InstallActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView sourceView;
    private TextView payloadView;
    private TextView statusView;
    private Button installButton;
    private CheckBox downgrade;

    private PayloadPreparer.PreparedPayload payload;
    private CallerVerifier.Identity identity;
    private boolean silentEligible;
    private boolean installing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        process(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!installing) {
            closePayload();
            process(intent);
        }
    }

    @Override
    protected void onDestroy() {
        if (!installing) {
            closePayload();
        }
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout page = Ui.page(this);
        page.addView(Ui.title(this, "Install package"));

        page.addView(Ui.heading(this, "Source"));
        sourceView = Ui.text(this, "Resolving source…", 15);
        page.addView(sourceView);

        page.addView(Ui.heading(this, "Payload"));
        payloadView = Ui.text(this, "Preparing APK payload…", 15);
        page.addView(payloadView);

        downgrade = new CheckBox(this);
        downgrade.setText("Allow version downgrade");
        page.addView(downgrade);

        statusView = Ui.text(this, "", 15);
        statusView.setPadding(0, Ui.dp(this, 14), 0, 0);
        page.addView(statusView);

        installButton = Ui.button(this, "Install with Shizuku", view -> beginInstall(false));
        installButton.setEnabled(false);
        page.addView(installButton);
        page.addView(Ui.button(this, "Cancel", view -> {
            setResult(RESULT_CANCELED);
            finish();
        }));
    }

    private void process(Intent intent) {
        List<Uri> uris = PayloadPreparer.extractUris(intent);
        identity = CallerVerifier.resolve(this, uris);
        TrustedStore store = new TrustedStore(this);
        boolean trusted = store.isTrusted(identity.packageName());
        silentEligible = InstallPolicy.mayInstallSilently(
                identity.verified(),
                store.packages().contains(identity.packageName()),
                trusted,
                ShizukuBridge.isReady());

        sourceView.setText(identity.description()
                + "\nIdentity: " + (identity.verified() ? "verified" : "not verified")
                + "\nAllowlist: " + (trusted ? "trusted" : "not trusted"));
        statusView.setText(silentEligible
                ? "Trusted request: installation will start automatically."
                : "Confirmation is required for this request.");

        executor.execute(() -> {
            try {
                PayloadPreparer.PreparedPayload prepared = PayloadPreparer.prepare(this, uris);
                runOnUiThread(() -> {
                    payload = prepared;
                    StringBuilder description = new StringBuilder();
                    long total = 0;
                    for (PayloadPreparer.Part part : prepared.parts) {
                        total += part.size();
                        description.append(part.name())
                                .append(" · ")
                                .append(part.size())
                                .append(" bytes\n");
                    }
                    description.append(prepared.parts.size())
                            .append(" APK part(s), ")
                            .append(total)
                            .append(" bytes total");
                    payloadView.setText(description.toString());
                    installButton.setEnabled(ShizukuBridge.isReady());
                    if (!ShizukuBridge.isReady()) {
                        statusView.setText("Shizuku is not ready. Open the main screen and grant permission.");
                    } else if (silentEligible) {
                        beginInstall(true);
                    }
                });
            } catch (IOException exception) {
                runOnUiThread(() -> {
                    payloadView.setText("Unable to prepare payload");
                    statusView.setText(exception.getMessage());
                });
            }
        });
    }

    private void beginInstall(boolean automatic) {
        if (payload == null || installing) {
            return;
        }
        installing = true;
        installButton.setEnabled(false);
        downgrade.setEnabled(false);
        statusView.setText(automatic
                ? "Trusted source verified. Installing silently…"
                : "Installing with Shizuku…");

        int flags = PrivilegedInstallerService.FLAG_REPLACE
                | PrivilegedInstallerService.FLAG_ALLOW_TEST_ONLY;
        if (downgrade.isChecked()) {
            flags |= PrivilegedInstallerService.FLAG_ALLOW_DOWNGRADE;
        }
        String sourcePackage = identity.verified() && identity.packageName() != null
                ? identity.packageName()
                : getPackageName();
        ShizukuBridge.install(this, payload, sourcePackage, flags, result -> {
            installing = false;
            boolean success = result != null && result.startsWith("Success");
            statusView.setText(result);
            closePayload();
            Intent response = new Intent().putExtra(Intent.EXTRA_TEXT, result);
            setResult(success ? RESULT_OK : RESULT_CANCELED, response);
            if (success) {
                Toast.makeText(this, "Installation completed.", Toast.LENGTH_LONG).show();
                finish();
            } else {
                installButton.setEnabled(false);
                downgrade.setEnabled(true);
            }
        });
    }

    private void closePayload() {
        if (payload != null) {
            payload.close();
            payload = null;
        }
    }
}
