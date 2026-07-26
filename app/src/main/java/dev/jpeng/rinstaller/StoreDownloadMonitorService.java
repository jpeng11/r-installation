package dev.jpeng.rinstaller;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class StoreDownloadMonitorService extends Service {
    private static final String TAG = "RInstallerNext";
    private static final String STATUS_CHANNEL = "download-monitor-status";
    private static final String ALERT_CHANNEL = "download-monitor-alerts";
    private static final int STATUS_NOTIFICATION = 7100;
    private static final long SCAN_INTERVAL_MILLIS = 5_000;
    private static final long PAUSED_INTERVAL_MILLIS = 15_000;
    private static final long REVIEW_CAPABILITY_MILLIS = TimeUnit.MINUTES.toMillis(15);
    private static final int MAX_REGISTRATIONS_IN_FLIGHT = 4;
    private static final int MAX_AUTOMATIC_ATTEMPTS = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanRunnable = this::scan;
    private final ExecutorService stagingExecutor = Executors.newSingleThreadExecutor();
    private final ArrayDeque<AutomaticCandidate> automaticCandidates = new ArrayDeque<>();
    private final Map<String, Fingerprint> known = new HashMap<>();
    private final Map<String, Fingerprint> pendingStability = new HashMap<>();
    private final Map<String, Integer> automaticFailures = new HashMap<>();
    private final Set<String> processingKeys = new HashSet<>();
    private final Set<String> forceReviewKeys = new HashSet<>();
    private final Set<String> initializedSources = new HashSet<>();

    private boolean scanInFlight;
    private int registrationsInFlight;
    private boolean automaticInstallActive;
    private boolean destroyed;

    public static void start(Context context) {
        context.startForegroundService(new Intent(
                context,
                StoreDownloadMonitorService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(
                context,
                StoreDownloadMonitorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Approved-store monitor created");
        createNotificationChannels();
        Notification notification = statusNotification(false);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    STATUS_NOTIFICATION,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(STATUS_NOTIFICATION, notification);
        }
        handler.post(scanRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!new InstallerSettings(this).isDownloadMonitorEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(scanRunnable);
        handler.post(scanRunnable);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        Log.i(TAG, "Approved-store monitor destroyed");
        handler.removeCallbacksAndMessages(null);
        stagingExecutor.shutdownNow();
        StoreMonitorBridge.disconnect(this);
        super.onDestroy();
    }

    private void scan() {
        InstallerSettings settings = new InstallerSettings(this);
        if (!settings.isDownloadMonitorEnabled()) {
            stopSelf();
            return;
        }
        if (!notificationsAllowed(this)) {
            settings.setDownloadMonitorEnabled(false);
            stopSelf();
            return;
        }
        if (!ShizukuBridge.isReady()) {
            Log.w(TAG, "Approved-store scan paused: Shizuku is not ready");
            updateStatus(true);
            scheduleNext(PAUSED_INTERVAL_MILLIS);
            return;
        }
        updateStatus(false);
        processNextAutomaticCandidate();
        if (scanInFlight) {
            scheduleNext(SCAN_INTERVAL_MILLIS);
            return;
        }
        List<String> sources = new ArrayList<>(
                new TrustedStore(this).trustedPackages());
        removeUntrustedState(new HashSet<>(sources));
        scanInFlight = true;
        snapshotNext(sources, 0);
    }

    private void snapshotNext(List<String> sources, int index) {
        if (index >= sources.size()) {
            scanInFlight = false;
            scheduleNext(SCAN_INTERVAL_MILLIS);
            return;
        }
        String sourcePackage = sources.get(index);
        StoreMonitorBridge.snapshot(this, sourcePackage, (entries, error) -> {
            if (error == null
                    && new TrustedStore(this).isTrusted(sourcePackage)) {
                processSnapshot(sourcePackage, entries);
            } else if (error != null) {
                Log.w(TAG, "Approved-store snapshot failed for "
                        + sourcePackage + ": " + error);
            }
            snapshotNext(sources, index + 1);
        });
    }

    private void processSnapshot(String sourcePackage, List<DetectedApk> entries) {
        Map<String, Fingerprint> current = new HashMap<>();
        for (DetectedApk entry : entries) {
            if (!isDirectApk(entry.relativePath())) {
                continue;
            }
            current.put(key(entry), new Fingerprint(
                    entry.size(),
                    entry.modifiedMillis()));
        }

        if (initializedSources.add(sourcePackage)) {
            known.putAll(current);
            Log.i(TAG, "Approved-store baseline recorded " + current.size()
                    + " candidate file(s) for " + sourcePackage);
            return;
        }

        String prefix = sourcePackage + "\n";
        known.keySet().removeIf(key ->
                key.startsWith(prefix) && !current.containsKey(key));
        pendingStability.keySet().removeIf(key ->
                key.startsWith(prefix) && !current.containsKey(key));
        automaticFailures.keySet().removeIf(key ->
                key.startsWith(prefix) && !current.containsKey(key));
        forceReviewKeys.removeIf(key ->
                key.startsWith(prefix) && !current.containsKey(key));

        for (DetectedApk entry : entries) {
            if (!isDirectApk(entry.relativePath())) {
                continue;
            }
            String key = key(entry);
            Fingerprint fingerprint = new Fingerprint(
                    entry.size(),
                    entry.modifiedMillis());
            if (fingerprint.equals(known.get(key))) {
                pendingStability.remove(key);
                continue;
            }
            if (processingKeys.contains(key)) {
                continue;
            }
            if (!fingerprint.equals(pendingStability.get(key))) {
                pendingStability.put(key, fingerprint);
                continue;
            }
            if (registrationsInFlight >= MAX_REGISTRATIONS_IN_FLIGHT) {
                continue;
            }
            registrationsInFlight++;
            processingKeys.add(key);
            pendingStability.remove(key);
            Log.i(TAG, "Approved-store file became stable for "
                    + sourcePackage + " (" + entry.size() + " bytes)");
            StoreMonitorBridge.register(this, entry, (candidate, error) -> {
                registrationsInFlight--;
                if (error == null
                        && candidate != null
                        && new InstallerSettings(this).isDownloadMonitorEnabled()
                    && new TrustedStore(this).isTrusted(entry.sourcePackage())) {
                    Log.i(TAG, "Approved-store candidate registered for "
                            + entry.sourcePackage());
                    AutomaticCandidate automatic = new AutomaticCandidate(
                            key,
                            fingerprint,
                            entry,
                            candidate);
                    if (new InstallerSettings(this).isSilentInstallEnabled()
                            && !forceReviewKeys.contains(key)) {
                        automaticCandidates.addLast(automatic);
                        processNextAutomaticCandidate();
                    } else {
                        completeWithReview(automatic);
                    }
                } else {
                    processingKeys.remove(key);
                    Log.w(TAG, "Approved-store registration failed for "
                            + entry.sourcePackage() + ": " + error);
                }
            });
        }
    }

    private void processNextAutomaticCandidate() {
        if (automaticInstallActive || automaticCandidates.isEmpty() || destroyed) {
            return;
        }
        AutomaticCandidate automatic = automaticCandidates.removeFirst();
        InstallerSettings settings = new InstallerSettings(this);
        if (!settings.isDownloadMonitorEnabled()) {
            releaseAutomaticCandidate(automatic);
            processNextAutomaticCandidate();
            return;
        }
        if (!new TrustedStore(this).isTrusted(
                automatic.detected.sourcePackage())) {
            releaseAutomaticCandidate(automatic);
            processNextAutomaticCandidate();
            return;
        }
        if (!settings.isSilentInstallEnabled()) {
            completeWithReview(automatic);
            processNextAutomaticCandidate();
            return;
        }
        if (!ShizukuBridge.isReady()) {
            automaticCandidates.addFirst(automatic);
            return;
        }
        automaticInstallActive = true;
        Log.i(TAG, "Approved-store automatic install staging started for "
                + automatic.detected.sourcePackage());
        StoreMonitorBridge.open(
                this,
                automatic.candidate.candidateId(),
                (descriptor, error) -> {
                    if (destroyed) {
                        closeQuietly(descriptor);
                        automaticInstallActive = false;
                        return;
                    }
                    if (error != null || descriptor == null) {
                        Log.w(TAG, "Approved-store candidate open failed: " + error);
                        retryAutomaticCandidate(
                                automatic,
                                getString(R.string.detected_apk_changed));
                        return;
                    }
                    try {
                        stagingExecutor.execute(() -> stageAutomaticCandidate(
                                automatic,
                                descriptor));
                    } catch (RejectedExecutionException exception) {
                        closeQuietly(descriptor);
                        if (!destroyed) {
                            retryAutomaticCandidate(
                                    automatic,
                                    getString(R.string.no_error_details));
                        } else {
                            automaticInstallActive = false;
                        }
                    }
                });
    }

    private void stageAutomaticCandidate(
            AutomaticCandidate automatic,
            android.os.ParcelFileDescriptor descriptor
    ) {
        try {
            DetectedApkStager.Result result = DetectedApkStager.stage(
                    this,
                    descriptor,
                    new File(automatic.detected.relativePath()).getName(),
                    automatic.detected.size(),
                    automatic.candidate.sha256());
            handler.post(() -> installStagedAutomatically(automatic, result));
        } catch (IOException exception) {
            Log.e(TAG, "Approved-store candidate staging failed", exception);
            handler.post(() -> {
                if (destroyed) {
                    automaticInstallActive = false;
                    return;
                }
                retryAutomaticCandidate(
                        automatic,
                        exception.getMessage() == null
                                ? getString(R.string.detected_apk_invalid)
                                : exception.getMessage());
            });
        }
    }

    private void installStagedAutomatically(
            AutomaticCandidate automatic,
            DetectedApkStager.Result staged
    ) {
        InstallerSettings settings = new InstallerSettings(this);
        if (destroyed) {
            staged.payload().close();
            automaticInstallActive = false;
            return;
        }
        if (!settings.isDownloadMonitorEnabled()
                || !new TrustedStore(this).isTrusted(
                        automatic.detected.sourcePackage())) {
            staged.payload().close();
            automaticInstallActive = false;
            releaseAutomaticCandidate(automatic);
            processNextAutomaticCandidate();
            return;
        }
        if (!settings.isSilentInstallEnabled()) {
            staged.payload().close();
            automaticInstallActive = false;
            releaseAutomaticCandidate(automatic);
            processNextAutomaticCandidate();
            return;
        }
        if (!ShizukuBridge.isReady()) {
            staged.payload().close();
            retryAutomaticCandidate(
                    automatic,
                    getString(R.string.shizuku_not_ready));
            return;
        }
        showStatusToast(getString(
                R.string.detected_apk_silent_start,
                staged.fileName(),
                sourceLabel(automatic.detected.sourcePackage())));
        Log.i(TAG, "Approved-store silent install requested for "
                + staged.packageName());
        int flags = dev.jpeng.rinstaller.service.PrivilegedInstallerService.FLAG_REPLACE
                | dev.jpeng.rinstaller.service.PrivilegedInstallerService.FLAG_ALLOW_TEST_ONLY;
        ShizukuBridge.install(
                this,
                staged.payload(),
                getPackageName(),
                flags,
                result -> {
                    staged.payload().close();
                    if (destroyed) {
                        automaticInstallActive = false;
                        return;
                    }
                    boolean success = result != null && result.startsWith("Success");
                    Log.i(TAG, "Approved-store silent install completed for "
                            + staged.packageName() + ": "
                            + (success ? "success" : "failure"));
                    showStatusToast(success
                            ? getString(
                                    R.string.detected_apk_silent_success,
                                    staged.packageName())
                            : getString(
                                    R.string.detected_apk_silent_failure,
                                    result == null || result.isBlank()
                                            ? getString(R.string.no_error_details)
                                            : result));
                    if (success) {
                        completeAutomaticSuccess(automatic);
                    } else {
                        retryAutomaticCandidate(
                                automatic,
                                result == null || result.isBlank()
                                        ? getString(R.string.no_error_details)
                                        : result,
                                false);
                    }
                });
    }

    private boolean postCandidateNotification(
            DetectedApk detected,
            StoreMonitorBridge.RegisteredCandidate candidate
    ) {
        if (!notificationsAllowed(this)) {
            return false;
        }
        Intent review = DetectedApkActivity.createIntent(
                this,
                detected,
                candidate);
        int requestCode = candidate.candidateId().hashCode() & 0x7fffffff;
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                requestCode,
                review,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String sourceLabel = sourceLabel(detected.sourcePackage());
        String fileName = new File(detected.relativePath()).getName();
        Notification notification = new Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_install)
                .setContentTitle(getString(R.string.detected_apk_notification_title))
                .setContentText(getString(
                        R.string.detected_apk_notification_text,
                        fileName,
                        sourceLabel))
                .setStyle(new Notification.BigTextStyle().bigText(getString(
                        R.string.detected_apk_notification_text,
                        fileName,
                        sourceLabel)))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setTimeoutAfter(REVIEW_CAPABILITY_MILLIS)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .build();
        try {
            getSystemService(NotificationManager.class).notify(
                    7200 + requestCode,
                    notification);
            return true;
        } catch (RuntimeException ignored) {
            // Settings checks permission before starting, but fail closed if it changes.
            return false;
        }
    }

    private void completeWithReview(AutomaticCandidate automatic) {
        if (postCandidateNotification(
                automatic.detected,
                automatic.candidate)) {
            known.put(automatic.key, automatic.fingerprint);
            automaticFailures.remove(automatic.key);
            forceReviewKeys.remove(automatic.key);
        } else {
            forceReviewKeys.add(automatic.key);
        }
        processingKeys.remove(automatic.key);
    }

    private void completeAutomaticSuccess(AutomaticCandidate automatic) {
        known.put(automatic.key, automatic.fingerprint);
        processingKeys.remove(automatic.key);
        automaticFailures.remove(automatic.key);
        forceReviewKeys.remove(automatic.key);
        automaticInstallActive = false;
        processNextAutomaticCandidate();
    }

    private void retryAutomaticCandidate(
            AutomaticCandidate automatic,
            String reason
    ) {
        retryAutomaticCandidate(automatic, reason, true);
    }

    private void retryAutomaticCandidate(
            AutomaticCandidate automatic,
            String reason,
            boolean showFailureToast
    ) {
        int failures = automaticFailures.merge(
                automatic.key,
                1,
                Integer::sum);
        if (failures >= MAX_AUTOMATIC_ATTEMPTS) {
            forceReviewKeys.add(automatic.key);
        }
        processingKeys.remove(automatic.key);
        automaticInstallActive = false;
        if (showFailureToast) {
            showStatusToast(getString(
                    R.string.detected_apk_silent_failure,
                    reason));
        }
        processNextAutomaticCandidate();
    }

    private void releaseAutomaticCandidate(AutomaticCandidate automatic) {
        processingKeys.remove(automatic.key);
        automaticFailures.remove(automatic.key);
        forceReviewKeys.remove(automatic.key);
    }

    private Notification statusNotification(boolean paused) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                STATUS_NOTIFICATION,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, STATUS_CHANNEL)
                .setSmallIcon(R.drawable.ic_install)
                .setContentTitle(getString(R.string.download_monitor_running_title))
                .setContentText(getString(paused
                        ? R.string.download_monitor_paused_text
                        : R.string.download_monitor_running_text))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateStatus(boolean paused) {
        getSystemService(NotificationManager.class).notify(
                STATUS_NOTIFICATION,
                statusNotification(paused));
    }

    private void createNotificationChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel status = new NotificationChannel(
                STATUS_CHANNEL,
                getString(R.string.download_monitor_running_title),
                NotificationManager.IMPORTANCE_LOW);
        status.setDescription(getString(R.string.download_monitor_channel_description));
        NotificationChannel alerts = new NotificationChannel(
                ALERT_CHANNEL,
                getString(R.string.download_monitor_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
        alerts.setDescription(getString(R.string.download_monitor_channel_description));
        manager.createNotificationChannel(status);
        manager.createNotificationChannel(alerts);
    }

    private void removeUntrustedState(Set<String> sources) {
        known.keySet().removeIf(key -> !sources.contains(sourceFromKey(key)));
        pendingStability.keySet().removeIf(key -> !sources.contains(sourceFromKey(key)));
        automaticFailures.keySet().removeIf(key ->
                !sources.contains(sourceFromKey(key)));
        processingKeys.removeIf(key -> !sources.contains(sourceFromKey(key)));
        forceReviewKeys.removeIf(key -> !sources.contains(sourceFromKey(key)));
        automaticCandidates.removeIf(automatic ->
                !sources.contains(automatic.detected.sourcePackage()));
        initializedSources.removeIf(source -> !sources.contains(source));
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

    static boolean notificationsAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager =
                context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) {
            return false;
        }
        NotificationChannel status = manager.getNotificationChannel(STATUS_CHANNEL);
        NotificationChannel alerts = manager.getNotificationChannel(ALERT_CHANNEL);
        return (status == null
                || status.getImportance() != NotificationManager.IMPORTANCE_NONE)
                && (alerts == null
                || alerts.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    private void scheduleNext(long delayMillis) {
        handler.postDelayed(scanRunnable, delayMillis);
    }

    private static String key(DetectedApk detected) {
        return detected.sourcePackage() + "\n" + detected.relativePath();
    }

    private static String sourceFromKey(String key) {
        int separator = key.indexOf('\n');
        return separator < 0 ? "" : key.substring(0, separator);
    }

    private static boolean isDirectApk(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".apk") || lower.endsWith(".apk.1");
    }

    private void showStatusToast(String message) {
        if (!new InstallerSettings(this).isCompletionToastEnabled()) {
            return;
        }
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }

    private static void closeQuietly(android.os.ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    private record Fingerprint(long size, long modifiedMillis) {}

    private record AutomaticCandidate(
            String key,
            Fingerprint fingerprint,
            DetectedApk detected,
            StoreMonitorBridge.RegisteredCandidate candidate
    ) {}
}
