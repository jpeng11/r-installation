package dev.jpeng.rinstaller;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import dev.jpeng.rinstaller.service.PrivilegedInstallerService;
import rikka.shizuku.Shizuku;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class InstallActivity extends LocalizedActivity {
    public static final String EXTRA_ROUTING_PROBE =
            "dev.jpeng.rinstaller.extra.ROUTING_PROBE";

    private static final int REQUEST_SHIZUKU = 4201;
    private static final String STATE_ROUTING_PROBE = "routing_probe";
    private static final String ROUTING_PROBE_MIME =
            "application/vnd.android.package-archive";
    private static final Uri ROUTING_PROBE_URI =
            Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".routing/probe.apk");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView sourceView;
    private TextView payloadView;
    private TextView statusView;
    private Button installButton;
    private CheckBox downgrade;

    private PayloadPreparer.PreparedPayload payload;
    private CallerVerifier.Identity identity;
    private List<Uri> requestUris = List.of();
    private boolean silentEligible;
    private boolean installing;
    private boolean preparing;
    private boolean retryInstallAvailable;
    private boolean permissionRequestPending;
    private boolean permissionDenied;
    private boolean automaticInstallAttempted;
    private boolean routingProbe;
    private AlertDialog routingProbeDialog;
    private CharSequence operationMessage;
    private int requestGeneration;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            () -> runOnUiThread(this::onShizukuStateChanged);
    private final Shizuku.OnBinderDeadListener binderDeadListener =
            () -> runOnUiThread(this::onShizukuStateChanged);
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQUEST_SHIZUKU) {
                    return;
                }
                runOnUiThread(() -> {
                    permissionRequestPending = false;
                    permissionDenied = grantResult != PackageManager.PERMISSION_GRANTED;
                    onShizukuStateChanged();
                });
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        Shizuku.addBinderReceivedListener(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        if (savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_ROUTING_PROBE, false)) {
            showRoutingProbe();
        } else {
            process(getIntent());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_ROUTING_PROBE, routingProbe);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!routingProbe) {
            onShizukuStateChanged();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!installing) {
            closePayload();
            dismissRoutingProbeDialog();
            process(intent);
        }
    }

    @Override
    protected void onDestroy() {
        requestGeneration++;
        dismissRoutingProbeDialog();
        executor.shutdownNow();
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
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
                this, getString(R.string.install_with_shizuku), view -> handlePrimaryAction());
        installButton.setEnabled(false);
        page.addView(installButton);
        page.addView(Ui.button(this, getString(R.string.cancel), view -> {
            setResult(RESULT_CANCELED);
            finish();
        }));
    }

    private void process(Intent intent) {
        int generation = ++requestGeneration;
        Bundle extras = intent.getExtras();
        Object probeValue = extras == null ? null : extras.get(EXTRA_ROUTING_PROBE);
        String probeToken = probeValue instanceof String ? (String) probeValue : null;
        boolean exactProbeIntent = Intent.ACTION_VIEW.equals(intent.getAction())
                && ROUTING_PROBE_MIME.equals(intent.getType())
                && ROUTING_PROBE_URI.equals(intent.getData());
        if (exactProbeIntent && RoutingProbeStore.consume(this, probeToken)) {
            showRoutingProbe();
            return;
        }
        routingProbe = false;
        downgrade.setVisibility(View.VISIBLE);
        installButton.setVisibility(View.VISIBLE);
        requestUris = PayloadPreparer.extractUris(intent);
        preparing = false;
        retryInstallAvailable = false;
        permissionDenied = false;
        automaticInstallAttempted = false;
        operationMessage = null;

        identity = CallerVerifier.resolve(this, requestUris);
        boolean trusted = new TrustedStore(this).isTrusted(identity.packageName());
        updateSilentEligibility();

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
        preparePayload(generation, false);
    }

    private void showRoutingProbe() {
        dismissRoutingProbeDialog();
        routingProbe = true;
        requestUris = List.of();
        closePayload();
        identity = null;
        silentEligible = false;
        installing = false;
        preparing = false;
        operationMessage = null;

        sourceView.setText(R.string.routing_probe_source);
        payloadView.setText(R.string.routing_probe_payload);
        statusView.setText(R.string.routing_probe_status);
        downgrade.setVisibility(View.GONE);
        installButton.setVisibility(View.GONE);

        routingProbeDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.routing_probe_title)
                .setMessage(R.string.routing_probe_message)
                .setPositiveButton(R.string.close, (ignored, which) -> {
                    routingProbeDialog = null;
                    setResult(RESULT_OK);
                    finish();
                })
                .create();
        routingProbeDialog.setOnCancelListener(ignored -> {
            routingProbeDialog = null;
            setResult(RESULT_OK);
            finish();
        });
        routingProbeDialog.show();
    }

    private void dismissRoutingProbeDialog() {
        if (routingProbeDialog != null) {
            routingProbeDialog.dismiss();
            routingProbeDialog = null;
        }
    }

    private void preparePayload(int generation, boolean installAfterPreparation) {
        if (preparing || installing) {
            return;
        }
        preparing = true;
        operationMessage = null;
        payloadView.setText(R.string.preparing_payload);
        refreshPrimaryAction();
        executor.execute(() -> {
            try {
                PayloadPreparer.PreparedPayload prepared =
                        PayloadPreparer.prepare(this, requestUris);
                runOnUiThread(() -> {
                    if (generation != requestGeneration || isFinishing() || isDestroyed()) {
                        prepared.close();
                        return;
                    }
                    preparing = false;
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
                    retryInstallAvailable = false;
                    operationMessage = null;
                    updateSilentEligibility();
                    refreshPrimaryAction();
                    if (installAfterPreparation && ShizukuBridge.isReady()) {
                        beginInstall(false);
                    } else if (silentEligible && !automaticInstallAttempted) {
                        beginInstall(true);
                    }
                });
            } catch (IOException exception) {
                runOnUiThread(() -> {
                    if (generation != requestGeneration || isFinishing() || isDestroyed()) {
                        return;
                    }
                    preparing = false;
                    retryInstallAvailable = installAfterPreparation;
                    payloadView.setText(R.string.unable_to_prepare_payload);
                    operationMessage = getString(
                            R.string.payload_preparation_failed,
                            safeMessage(exception.getMessage()));
                    refreshPrimaryAction();
                });
            }
        });
    }

    private void handlePrimaryAction() {
        if (installing) {
            return;
        }
        if (!ShizukuBridge.isRunning()) {
            openShizuku();
            return;
        }
        if (!ShizukuBridge.hasPermission()) {
            requestShizukuPermission();
            return;
        }
        if (preparing) {
            return;
        }
        if (payload == null) {
            preparePayload(requestGeneration, retryInstallAvailable);
            return;
        }
        beginInstall(false);
    }

    private void requestShizukuPermission() {
        try {
            permissionRequestPending = true;
            permissionDenied = false;
            operationMessage = null;
            refreshPrimaryAction();
            Shizuku.requestPermission(REQUEST_SHIZUKU);
        } catch (RuntimeException exception) {
            permissionRequestPending = false;
            operationMessage = getString(
                    R.string.shizuku_error, safeMessage(exception.getMessage()));
            refreshPrimaryAction();
        }
    }

    private void openShizuku() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(
                "moe.shizuku.privileged.api");
        if (launch == null) {
            operationMessage = getString(R.string.shizuku_not_installed);
            refreshPrimaryAction();
            return;
        }
        try {
            startActivity(launch);
        } catch (RuntimeException exception) {
            operationMessage = getString(R.string.shizuku_unable_to_open);
            refreshPrimaryAction();
        }
    }

    private void onShizukuStateChanged() {
        if (routingProbe || isFinishing() || isDestroyed()) {
            return;
        }
        Intent shizukuLaunch = getPackageManager().getLaunchIntentForPackage(
                "moe.shizuku.privileged.api");
        if (operationMessage != null
                && (operationMessage.toString().equals(getString(R.string.shizuku_unable_to_open))
                || (shizukuLaunch != null
                && operationMessage.toString().equals(getString(R.string.shizuku_not_installed))))) {
            operationMessage = null;
        }
        if (ShizukuBridge.hasPermission()) {
            permissionRequestPending = false;
            permissionDenied = false;
            if (operationMessage != null
                    && operationMessage.toString().startsWith(getString(R.string.shizuku_error_prefix))) {
                operationMessage = null;
            }
        }
        updateSilentEligibility();
        refreshPrimaryAction();
        if (payload != null
                && !preparing
                && !installing
                && silentEligible
                && !automaticInstallAttempted
                && operationMessage == null) {
            beginInstall(true);
        }
    }

    private void refreshPrimaryAction() {
        if (installButton == null || statusView == null) {
            return;
        }
        if (installing) {
            installButton.setEnabled(false);
            return;
        }
        if (!ShizukuBridge.isRunning()) {
            installButton.setText(R.string.open_shizuku);
            installButton.setEnabled(true);
            statusView.setText(operationMessage != null
                    ? operationMessage
                    : getText(R.string.shizuku_start_and_return));
            return;
        }
        if (!ShizukuBridge.hasPermission()) {
            installButton.setText(permissionRequestPending
                    ? R.string.authorizing_shizuku
                    : R.string.authorize_shizuku);
            installButton.setEnabled(!permissionRequestPending);
            if (operationMessage != null) {
                statusView.setText(operationMessage);
            } else if (permissionRequestPending) {
                statusView.setText(R.string.shizuku_waiting_for_permission);
            } else {
                statusView.setText(permissionDenied
                        ? R.string.shizuku_permission_not_granted
                        : R.string.shizuku_permission_required_here);
            }
            return;
        }
        if (preparing) {
            installButton.setText(R.string.preparing_payload_button);
            installButton.setEnabled(false);
            statusView.setText(R.string.preparing_payload);
            return;
        }
        if (payload == null) {
            installButton.setText(retryInstallAvailable
                    ? R.string.retry_installation
                    : R.string.retry_payload);
            installButton.setEnabled(!requestUris.isEmpty());
            statusView.setText(operationMessage != null
                    ? operationMessage
                    : getText(R.string.unable_to_prepare_payload));
            return;
        }
        installButton.setText(R.string.install_with_shizuku);
        installButton.setEnabled(true);
        statusView.setText(operationMessage != null
                ? operationMessage
                : getText(silentEligible
                        ? R.string.trusted_request_automatic
                        : R.string.confirmation_required));
    }

    private void updateSilentEligibility() {
        if (identity == null) {
            silentEligible = false;
            return;
        }
        TrustedStore store = new TrustedStore(this);
        boolean trusted = store.isTrusted(identity.packageName());
        silentEligible = InstallPolicy.mayInstallSilently(
                identity.verified(),
                store.packages().contains(identity.packageName()),
                trusted,
                ShizukuBridge.isReady(),
                new InstallerSettings(this).isSilentInstallEnabled());
    }

    private void beginInstall(boolean automatic) {
        if (payload == null || installing || !ShizukuBridge.isReady()) {
            refreshPrimaryAction();
            return;
        }
        automaticInstallAttempted = true;
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
            statusView.setText(success
                    ? getString(R.string.installation_completed)
                    : safeMessage(result));
            Intent response = new Intent().putExtra(Intent.EXTRA_TEXT, result);
            setResult(success ? RESULT_OK : RESULT_CANCELED, response);
            if (success) {
                closePayload();
                if (new InstallerSettings(this).isCompletionToastEnabled()) {
                    Toast.makeText(
                            this,
                            R.string.installation_completed,
                            Toast.LENGTH_LONG).show();
                }
                finish();
            } else {
                closePayload();
                retryInstallAvailable = true;
                operationMessage = getString(
                        R.string.installation_failed_retry,
                        safeMessage(result));
                downgrade.setEnabled(true);
                refreshPrimaryAction();
            }
        });
    }

    private String safeMessage(String value) {
        return value == null || value.isBlank()
                ? getString(R.string.no_error_details)
                : value;
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
