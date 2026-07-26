package dev.jpeng.rinstaller;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import dev.jpeng.rinstaller.service.PrivilegedInstallerService;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DetectedApkActivity extends LocalizedActivity {
    private static final String EXTRA_CANDIDATE_ID =
            "dev.jpeng.rinstaller.detected.CANDIDATE_ID";
    private static final String EXTRA_SOURCE_PACKAGE =
            "dev.jpeng.rinstaller.detected.SOURCE_PACKAGE";
    private static final String EXTRA_RELATIVE_PATH =
            "dev.jpeng.rinstaller.detected.RELATIVE_PATH";
    private static final String EXTRA_SIZE =
            "dev.jpeng.rinstaller.detected.SIZE";
    private static final String EXTRA_MODIFIED =
            "dev.jpeng.rinstaller.detected.MODIFIED";
    private static final String EXTRA_SHA256 =
            "dev.jpeng.rinstaller.detected.SHA256";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView fileView;
    private TextView statusView;
    private Button installButton;
    private DetectedApk detected;
    private String candidateId;
    private String expectedSha256;
    private DetectedApkStager.Result staged;
    private boolean installing;
    private boolean destroyed;

    static Intent createIntent(
            Context context,
            DetectedApk detected,
            StoreMonitorBridge.RegisteredCandidate candidate
    ) {
        return new Intent(context, DetectedApkActivity.class)
                .setData(Uri.parse(
                        "rinstaller://detected/" + candidate.candidateId()))
                .putExtra(EXTRA_CANDIDATE_ID, candidate.candidateId())
                .putExtra(EXTRA_SOURCE_PACKAGE, detected.sourcePackage())
                .putExtra(EXTRA_RELATIVE_PATH, detected.relativePath())
                .putExtra(EXTRA_SIZE, detected.size())
                .putExtra(EXTRA_MODIFIED, detected.modifiedMillis())
                .putExtra(EXTRA_SHA256, candidate.sha256());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        if (!readRequest(getIntent())) {
            showFailure(R.string.detected_apk_changed);
            return;
        }
        showSource();
        if (!new TrustedStore(this).isTrusted(detected.sourcePackage())) {
            showFailure(R.string.detected_apk_not_approved);
            return;
        }
        openAndStageCandidate();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        if (!installing && staged != null) {
            staged.payload().close();
            staged = null;
        }
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout page = Ui.page(this);
        page.addView(Ui.heading(this, getString(R.string.detected_apk_source)));
        TextView sourceView = Ui.text(this, "", 15);
        sourceView.setTag("source");
        page.addView(sourceView);

        TextView notice = Ui.text(
                this,
                getString(R.string.detected_apk_source_notice),
                14);
        notice.setTextColor(getColor(R.color.text_secondary));
        notice.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 8));
        page.addView(notice);

        page.addView(Ui.heading(this, getString(R.string.detected_apk_file)));
        fileView = Ui.text(this, "", 15);
        page.addView(fileView);

        statusView = Ui.text(this, getString(R.string.detected_apk_preparing), 15);
        statusView.setPadding(0, Ui.dp(this, 16), 0, 0);
        page.addView(statusView);

        installButton = Ui.button(
                this,
                getString(R.string.detected_apk_install),
                view -> installReviewedApk());
        installButton.setEnabled(false);
        page.addView(installButton);
        page.addView(Ui.button(this, getString(R.string.cancel), view -> finish()));
    }

    private boolean readRequest(Intent intent) {
        try {
            candidateId = intent.getStringExtra(EXTRA_CANDIDATE_ID);
            expectedSha256 = intent.getStringExtra(EXTRA_SHA256);
            detected = new DetectedApk(
                    intent.getStringExtra(EXTRA_SOURCE_PACKAGE),
                    intent.getStringExtra(EXTRA_RELATIVE_PATH),
                    intent.getLongExtra(EXTRA_SIZE, -1),
                    intent.getLongExtra(EXTRA_MODIFIED, -1));
            return candidateId != null
                    && !candidateId.isBlank()
                    && expectedSha256 != null
                    && expectedSha256.length() == 64;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void showSource() {
        TextView sourceView = findTaggedSourceView();
        sourceView.setText(getString(
                R.string.detected_apk_source_value,
                sourceLabel(detected.sourcePackage()),
                detected.sourcePackage()));
        fileView.setText(new File(detected.relativePath()).getName()
                + "\n" + detected.size() + " B");
    }

    private TextView findTaggedSourceView() {
        return getWindow().getDecorView().findViewWithTag("source");
    }

    private void openAndStageCandidate() {
        statusView.setText(R.string.detected_apk_preparing);
        StoreMonitorBridge.open(this, candidateId, (descriptor, error) -> {
            if (destroyed) {
                closeQuietly(descriptor);
                return;
            }
            if (error != null || descriptor == null) {
                showFailure(R.string.detected_apk_changed);
                return;
            }
            executor.execute(() -> {
                try {
                    DetectedApkStager.Result result = DetectedApkStager.stage(
                            this,
                            descriptor,
                            new File(detected.relativePath()).getName(),
                            detected.size(),
                            expectedSha256);
                    runOnUiThread(() -> onStaged(result));
                } catch (IOException exception) {
                    runOnUiThread(() -> showFailure(
                            exception.getMessage() != null
                                    && exception.getMessage().contains("valid")
                                    ? R.string.detected_apk_invalid
                                    : R.string.detected_apk_changed));
                }
            });
        });
    }

    private void onStaged(DetectedApkStager.Result result) {
        if (destroyed || isFinishing()) {
            result.payload().close();
            return;
        }
        if (!new TrustedStore(this).isTrusted(detected.sourcePackage())) {
            result.payload().close();
            showFailure(R.string.detected_apk_not_approved);
            return;
        }
        staged = result;
        fileView.setText(getString(
                R.string.detected_apk_ready,
                result.fileName(),
                result.packageName(),
                result.version(),
                result.size(),
                result.fileSha256()));
        statusView.setText("");
        installButton.setEnabled(ShizukuBridge.isReady());
    }

    private void installReviewedApk() {
        if (staged == null || installing) {
            return;
        }
        if (!new TrustedStore(this).isTrusted(detected.sourcePackage())) {
            showFailure(R.string.detected_apk_not_approved);
            return;
        }
        if (!ShizukuBridge.isReady()) {
            statusView.setText(R.string.shizuku_not_ready);
            return;
        }
        installing = true;
        installButton.setEnabled(false);
        statusView.setText(R.string.detected_apk_installing);
        int flags = PrivilegedInstallerService.FLAG_REPLACE
                | PrivilegedInstallerService.FLAG_ALLOW_TEST_ONLY;
        ShizukuBridge.install(
                this,
                staged.payload(),
                getPackageName(),
                flags,
                result -> {
                    installing = false;
                    boolean success = result != null && result.startsWith("Success");
                    if (success) {
                        staged.payload().close();
                        staged = null;
                        if (!destroyed) {
                            if (new InstallerSettings(this)
                                    .isCompletionToastEnabled()) {
                                Toast.makeText(
                                        this,
                                        R.string.installation_completed,
                                        Toast.LENGTH_LONG).show();
                            }
                            finish();
                        }
                        return;
                    }
                    if (destroyed) {
                        staged.payload().close();
                        staged = null;
                        return;
                    }
                    try {
                        staged = DetectedApkStager.reopen(staged);
                        installButton.setEnabled(true);
                    } catch (IOException exception) {
                        staged.payload().close();
                        staged = null;
                    }
                    statusView.setText(result == null || result.isBlank()
                            ? getString(R.string.no_error_details)
                            : result);
                });
    }

    private void showFailure(int message) {
        statusView.setText(message);
        installButton.setEnabled(false);
    }

    private String sourceLabel(String packageName) {
        try {
            CharSequence label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0));
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException exception) {
            return packageName;
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Best effort.
        }
    }
}
