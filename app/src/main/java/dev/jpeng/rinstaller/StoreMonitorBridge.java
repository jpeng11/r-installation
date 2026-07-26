package dev.jpeng.rinstaller;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import dev.jpeng.rinstaller.service.PrivilegedFileMonitorService;
import rikka.shizuku.Shizuku;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

final class StoreMonitorBridge {
    private static final String TAG = "RInstallerNext";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Queue<PendingOperation> PENDING = new ArrayDeque<>();
    private static final Pattern OPAQUE_ID = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    private static IPrivilegedFileMonitor service;
    private static boolean binding;
    private static long bindingGeneration;
    private static Shizuku.UserServiceArgs activeArguments;
    private static MonitorConnection activeConnection;

    private StoreMonitorBridge() {}

    record RegisteredCandidate(String candidateId, String sha256) {
        RegisteredCandidate {
            if (candidateId == null || !OPAQUE_ID.matcher(candidateId).matches()) {
                throw new IllegalArgumentException("invalid candidate ID");
            }
            if (sha256 == null || !SHA_256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("invalid candidate digest");
            }
        }
    }

    interface SnapshotCallback {
        void onComplete(List<DetectedApk> entries, String error);
    }

    interface RegistrationCallback {
        void onComplete(RegisteredCandidate candidate, String error);
    }

    interface OpenCallback {
        void onComplete(ParcelFileDescriptor descriptor, String error);
    }

    static void snapshot(
            Context context,
            String sourcePackage,
            SnapshotCallback callback
    ) {
        Objects.requireNonNull(callback, "callback");
        final String validatedPackage;
        try {
            validatedPackage = DetectedApk.requireSourcePackage(sourcePackage);
        } catch (RuntimeException exception) {
            MAIN.post(() -> callback.onComplete(List.of(), "Invalid source package."));
            return;
        }

        submit(context, new PendingOperation() {
            @Override
            public void execute(IPrivilegedFileMonitor monitor) {
                try {
                    List<DetectedApk> entries = DetectedApk.decodeSnapshot(
                            validatedPackage,
                            monitor.snapshot(validatedPackage));
                    MAIN.post(() -> callback.onComplete(entries, null));
                } catch (Exception exception) {
                    Log.w(TAG, "Shizuku file-monitor snapshot call failed", exception);
                    MAIN.post(() -> callback.onComplete(
                            List.of(),
                            "Unable to read the app-store folder."));
                }
            }

            @Override
            public void fail(String error) {
                MAIN.post(() -> callback.onComplete(List.of(), error));
            }
        });
    }

    static void register(
            Context context,
            DetectedApk detected,
            RegistrationCallback callback
    ) {
        Objects.requireNonNull(callback, "callback");
        if (detected == null) {
            MAIN.post(() -> callback.onComplete(null, "Invalid package candidate."));
            return;
        }

        submit(context, new PendingOperation() {
            @Override
            public void execute(IPrivilegedFileMonitor monitor) {
                try {
                    String[] response = monitor.registerCandidate(
                            detected.sourcePackage(),
                            detected.relativePath(),
                            detected.size(),
                            detected.modifiedMillis());
                    if (response == null || response.length != 2) {
                        throw new IllegalStateException("malformed registration response");
                    }
                    RegisteredCandidate candidate =
                            new RegisteredCandidate(response[0], response[1]);
                    MAIN.post(() -> callback.onComplete(candidate, null));
                } catch (Exception exception) {
                    Log.w(TAG, "Shizuku file-monitor registration call failed", exception);
                    MAIN.post(() -> callback.onComplete(
                            null,
                            "The package changed before it could be registered."));
                }
            }

            @Override
            public void fail(String error) {
                MAIN.post(() -> callback.onComplete(null, error));
            }
        });
    }

    static void open(
            Context context,
            String candidateId,
            OpenCallback callback
    ) {
        Objects.requireNonNull(callback, "callback");
        if (candidateId == null || !OPAQUE_ID.matcher(candidateId).matches()) {
            MAIN.post(() -> callback.onComplete(null, "Unknown or expired package candidate."));
            return;
        }

        submit(context, new PendingOperation() {
            @Override
            public void execute(IPrivilegedFileMonitor monitor) {
                ParcelFileDescriptor descriptor = null;
                try {
                    descriptor = monitor.openCandidate(candidateId);
                    if (descriptor == null) {
                        throw new IllegalStateException("missing descriptor");
                    }
                    ParcelFileDescriptor opened = descriptor;
                    MAIN.post(() -> deliverOpen(callback, opened));
                } catch (Exception exception) {
                    Log.w(TAG, "Shizuku file-monitor open call failed", exception);
                    closeQuietly(descriptor);
                    MAIN.post(() -> callback.onComplete(
                            null,
                            "The package changed or the candidate expired."));
                }
            }

            @Override
            public void fail(String error) {
                MAIN.post(() -> callback.onComplete(null, error));
            }
        });
    }

    static void disconnect(Context context) {
        Objects.requireNonNull(context, "context");
        IPrivilegedFileMonitor connected;
        Shizuku.UserServiceArgs arguments;
        MonitorConnection connection;
        Queue<PendingOperation> failed = new ArrayDeque<>();
        synchronized (StoreMonitorBridge.class) {
            connected = service;
            arguments = activeArguments;
            connection = activeConnection;
            bindingGeneration++;
            service = null;
            binding = false;
            activeArguments = null;
            activeConnection = null;
            while (!PENDING.isEmpty()) {
                failed.add(PENDING.remove());
            }
        }
        for (PendingOperation operation : failed) {
            operation.fail("The Shizuku file monitor was stopped.");
        }
        if (connected != null) {
            try {
                connected.destroy();
            } catch (Exception ignored) {
                // The remove=true unbind below is the authoritative cleanup.
            }
        }
        if (arguments != null && connection != null) {
            try {
                Shizuku.unbindUserService(arguments, connection, true);
            } catch (RuntimeException ignored) {
                // The binder may already have died.
            }
        }
    }

    private static void submit(Context context, PendingOperation operation) {
        Context applicationContext = context.getApplicationContext();
        if (!isShizukuReady()) {
            operation.fail("Shizuku is not running or permission was not granted.");
            return;
        }

        boolean shouldBind = false;
        long generation = 0;
        IPrivilegedFileMonitor connected;
        synchronized (StoreMonitorBridge.class) {
            connected = service;
            if (connected == null) {
                PENDING.add(operation);
                if (!binding) {
                    binding = true;
                    shouldBind = true;
                    generation = ++bindingGeneration;
                }
            }
        }
        if (connected != null) {
            execute(operation, connected);
            return;
        }
        if (!shouldBind) {
            return;
        }

        ComponentName component = new ComponentName(
                applicationContext.getPackageName(),
                PrivilegedFileMonitorService.class.getName());
        Shizuku.UserServiceArgs arguments = new Shizuku.UserServiceArgs(component)
                .processNameSuffix("file_monitor")
                .daemon(false)
                .debuggable(BuildConfig.DEBUG)
                .version(4);
        MonitorConnection connection = new MonitorConnection(
                generation,
                arguments);
        synchronized (StoreMonitorBridge.class) {
            if (!binding || generation != bindingGeneration) {
                return;
            }
            activeArguments = arguments;
            activeConnection = connection;
        }
        try {
            Shizuku.bindUserService(arguments, connection);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to bind Shizuku file monitor", exception);
            failPending(
                    connection,
                    "Unable to start the Shizuku file monitor.");
        }
    }

    private static boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void execute(
            PendingOperation operation,
            IPrivilegedFileMonitor connected
    ) {
        IO.execute(() -> operation.execute(connected));
    }

    private static void drainPending(
            MonitorConnection connection,
            IPrivilegedFileMonitor connected
    ) {
        while (true) {
            PendingOperation operation;
            synchronized (StoreMonitorBridge.class) {
                if (connection != activeConnection
                        || connection.generation != bindingGeneration
                        || service != connected) {
                    return;
                }
                operation = PENDING.poll();
            }
            if (operation == null) {
                return;
            }
            execute(operation, connected);
        }
    }

    private static void failPending(
            MonitorConnection connection,
            String error
    ) {
        Queue<PendingOperation> failed = new ArrayDeque<>();
        synchronized (StoreMonitorBridge.class) {
            if (connection != activeConnection
                    || connection.generation != bindingGeneration) {
                return;
            }
            bindingGeneration++;
            binding = false;
            service = null;
            activeArguments = null;
            activeConnection = null;
            while (!PENDING.isEmpty()) {
                failed.add(PENDING.remove());
            }
        }
        for (PendingOperation operation : failed) {
            operation.fail(error);
        }
    }

    private static void deliverOpen(
            OpenCallback callback,
            ParcelFileDescriptor descriptor
    ) {
        try {
            callback.onComplete(descriptor, null);
        } catch (RuntimeException exception) {
            closeQuietly(descriptor);
            throw exception;
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

    private static final class MonitorConnection implements ServiceConnection {
        private final long generation;
        private final Shizuku.UserServiceArgs arguments;

        MonitorConnection(
                long generation,
                Shizuku.UserServiceArgs arguments
        ) {
            this.generation = generation;
            this.arguments = arguments;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "Shizuku file monitor connected");
            IPrivilegedFileMonitor connected =
                    IPrivilegedFileMonitor.Stub.asInterface(binder);
            boolean stale;
            synchronized (StoreMonitorBridge.class) {
                stale = this != activeConnection
                        || generation != bindingGeneration;
                if (!stale) {
                    service = connected;
                    binding = false;
                }
            }
            if (stale) {
                try {
                    Shizuku.unbindUserService(arguments, this, false);
                } catch (RuntimeException ignored) {
                    // This stale callback no longer owns bridge state.
                }
                return;
            }
            drainPending(this, connected);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "Shizuku file monitor disconnected");
            synchronized (StoreMonitorBridge.class) {
                if (this == activeConnection
                        && generation == bindingGeneration) {
                    service = null;
                    binding = false;
                }
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "Shizuku file monitor binding died");
            failPending(this, "The Shizuku file monitor stopped.");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.w(TAG, "Shizuku file monitor returned a null binding");
            failPending(this, "The Shizuku file monitor did not start.");
        }
    }

    private interface PendingOperation {
        void execute(IPrivilegedFileMonitor monitor);
        void fail(String error);
    }
}
