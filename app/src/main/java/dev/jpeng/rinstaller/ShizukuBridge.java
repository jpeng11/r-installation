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

import dev.jpeng.rinstaller.service.PrivilegedInstallerService;
import rikka.shizuku.Shizuku;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ShizukuBridge {
    private static final String TAG = "RInstallerNext";
    interface Callback {
        void onComplete(String result);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static IPrivilegedInstaller service;
    private static boolean binding;
    private static Pending pending;

    private ShizukuBridge() {}

    static boolean isRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean hasPermission() {
        try {
            return isRunning()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean isReady() {
        return isRunning() && hasPermission();
    }

    static String status() {
        try {
            if (!Shizuku.pingBinder()) {
                return "Shizuku is not running";
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return "Shizuku permission is required";
            }
            return "Ready · Shizuku UID " + Shizuku.getUid();
        } catch (RuntimeException exception) {
            return "Shizuku is unavailable";
        }
    }

    static void install(
            Context context,
            PayloadPreparer.PreparedPayload payload,
            String sourcePackage,
            int flags,
            Callback callback
    ) {
        if (!isReady()) {
            callback.onComplete("Failure [Shizuku is not running or permission was not granted]");
            return;
        }
        Pending request = new Pending(
                payload.descriptors(),
                payload.names(),
                payload.sizes(),
                sourcePackage,
                flags,
                callback);
        synchronized (ShizukuBridge.class) {
            if (pending != null) {
                callback.onComplete("Failure [another installation is already active]");
                return;
            }
            pending = request;
            if (service != null) {
                executePending();
                return;
            }
            if (binding) {
                return;
            }
            binding = true;
        }

        ComponentName component = new ComponentName(
                context.getPackageName(), PrivilegedInstallerService.class.getName());
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(component)
                .processNameSuffix("installer")
                .daemon(false)
                .debuggable(BuildConfig.DEBUG)
                .version(3);
        try {
            Shizuku.bindUserService(args, CONNECTION);
        } catch (RuntimeException exception) {
            synchronized (ShizukuBridge.class) {
                binding = false;
                Pending failed = pending;
                pending = null;
                if (failed != null) {
                    failed.callback.onComplete("Failure [cannot bind Shizuku service: "
                            + exception.getMessage() + "]");
                }
            }
        }
    }

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "Shizuku installer service connected");
            synchronized (ShizukuBridge.class) {
                service = IPrivilegedInstaller.Stub.asInterface(binder);
                binding = false;
            }
            executePending();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (ShizukuBridge.class) {
                service = null;
                binding = false;
            }
        }
    };

    private static void executePending() {
        Pending request;
        IPrivilegedInstaller installer;
        synchronized (ShizukuBridge.class) {
            request = pending;
            installer = service;
            if (request == null || installer == null) {
                return;
            }
        }
        IO.execute(() -> {
            String result;
            try {
                Log.i(TAG, "Sending " + request.descriptors.length
                        + " APK part(s) to the Shizuku installer service");
                result = installer.install(
                        request.descriptors,
                        request.names,
                        request.sizes,
                        request.sourcePackage,
                        request.flags);
            } catch (Exception exception) {
                Log.e(TAG, "Privileged install call failed", exception);
                result = "Failure [" + exception.getClass().getSimpleName() + ": "
                        + exception.getMessage() + "]";
            }
            String finalResult = result;
            Log.i(TAG, "Privileged install completed: " + finalResult);
            synchronized (ShizukuBridge.class) {
                pending = null;
            }
            MAIN.post(() -> request.callback.onComplete(finalResult));
        });
    }

    private record Pending(
            ParcelFileDescriptor[] descriptors,
            String[] names,
            long[] sizes,
            String sourcePackage,
            int flags,
            Callback callback
    ) {}
}
