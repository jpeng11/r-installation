package dev.jpeng.rinstaller;

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

public final class InstallActivity extends LocalizedActivity {
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

        page.addView(Ui.heading(this, getString(R.string.source)));
        sourceView = Ui.text(this, getString(R.string.resolving_source), 15);
        page.addView(sourceView);

        page.addView(Ui.heading(this, getString(R.string.payload)));
        payloadView = Ui.text(this, getString(R.string.preparing_payload), 15);
        page.addView(payloadView);

        downgrade = new CheckBox(this);
        downgrade.setText(R.string.allow_downgrade);
        page.addView(downgrade);

        statusView = Ui.text(this, "", 15);
        statusView.setPadding(0, Ui.dp(this, 14), 0, 0);
        page.addView(statusView);

        installButton = Ui.button(
                this, getString(R.string.install_with_shizuku), view -> beginInstall(false));
        installButton.setEnabled(false);
        page.addView(installButton);
        page.addView(Ui.button(this, getString(R.string.cancel), view -> {
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

        sourceView.setText(getString(
                R.string.source_identity_summary,
                identityDescription(identity),
                getString(identity.verified()
                        ? R.string.identity_verified
                        : R.string.identity_not_verified),
                getString(trusted ? R.string.source_trusted : R.string.source_not_trusted)));
        statusView.setText(silentEligible
                ? R.string.trusted_request_automatic
                : R.string.confirmation_required);

        executor.execute(() -> {
            try {
                PayloadPreparer.PreparedPayload prepared = PayloadPreparer.prepare(this, uris);
                runOnUiThread(() -> {
                    payload = prepared;
                    StringBuilder description = new StringBuilder();
                    long total = 0;
                    for (PayloadPreparer.Part part : prepared.parts) {
                        total += part.size();
                        description.append(getString(
                                R.string.payload_part, part.name(), part.size()));
                    }
                    description.append(getResources().getQuantityString(
                            R.plurals.payload_summary,
                            prepared.parts.size(),
                            prepared.parts.size(),
                            total));
                    payloadView.setText(description.toString());
                    installButton.setEnabled(ShizukuBridge.isReady());
                    if (!ShizukuBridge.isReady()) {
                        statusView.setText(R.string.shizuku_not_ready);
                    } else if (silentEligible) {
                        beginInstall(true);
                    }
                });
            } catch (IOException exception) {
                runOnUiThread(() -> {
                    payloadView.setText(R.string.unable_to_prepare_payload);
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
                ? R.string.installing_silently
                : R.string.installing_with_shizuku);

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
            statusView.setText(success ? getString(R.string.installation_completed) : result);
            closePayload();
            Intent response = new Intent().putExtra(Intent.EXTRA_TEXT, result);
            setResult(success ? RESULT_OK : RESULT_CANCELED, response);
            if (success) {
                Toast.makeText(this, R.string.installation_completed, Toast.LENGTH_LONG).show();
                finish();
            } else {
                installButton.setEnabled(false);
                downgrade.setEnabled(true);
            }
        });
    }

    private String identityDescription(CallerVerifier.Identity value) {
        if (value.packageName() == null) {
            return getString(R.string.unknown_source);
        }
        int method = switch (value.method()) {
            case OS_CALLER -> R.string.method_os_caller;
            case RESULT_CALLER -> R.string.method_result_caller;
            case CONTENT_PROVIDER -> R.string.method_content_provider;
            case REFERRER_ONLY -> R.string.method_referrer;
            case UNKNOWN -> R.string.method_unknown;
        };
        return getString(R.string.source_description, value.packageName(), getString(method));
    }

    private void closePayload() {
        if (payload != null) {
            payload.close();
            payload = null;
        }
    }
}
