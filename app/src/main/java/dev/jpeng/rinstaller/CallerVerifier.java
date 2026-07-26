package dev.jpeng.rinstaller;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Build;

import java.util.List;
import java.util.Locale;

final class CallerVerifier {
    private static final int INVALID_UID = -1;
    enum Method {
        OS_CALLER,
        RESULT_CALLER,
        CONTENT_PROVIDER,
        REFERRER_ONLY,
        UNKNOWN
    }

    record Identity(String packageName, int uid, Method method, boolean verified) {
        String description() {
            if (packageName == null) {
                return "Unknown source";
            }
            return packageName + " · "
                    + method.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    private CallerVerifier() {}

    static Identity resolve(Activity activity, List<Uri> uris) {
        PackageManager pm = activity.getPackageManager();

        if (Build.VERSION.SDK_INT >= 34) {
            int uid = activity.getLaunchedFromUid();
            String packageName = activity.getLaunchedFromPackage();
            if (uid != INVALID_UID && packageMatchesUid(pm, packageName, uid)) {
                return new Identity(packageName, uid, Method.OS_CALLER, true);
            }
        }

        String resultCaller = activity.getCallingPackage();
        if (resultCaller != null) {
            try {
                int uid = pm.getPackageUid(resultCaller, 0);
                return new Identity(resultCaller, uid, Method.RESULT_CALLER, true);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Continue to URI ownership.
            }
        }

        Identity provider = resolveProviderOwner(pm, uris);
        if (provider != null) {
            return provider;
        }

        Uri referrer = activity.getReferrer();
        if (referrer != null && "android-app".equals(referrer.getScheme())) {
            String packageName = referrer.getHost();
            if (packageName != null) {
                try {
                    int uid = pm.getPackageUid(packageName, 0);
                    return new Identity(packageName, uid, Method.REFERRER_ONLY, false);
                } catch (PackageManager.NameNotFoundException ignored) {
                    // Fall through.
                }
            }
        }
        return new Identity(null, INVALID_UID, Method.UNKNOWN, false);
    }

    private static Identity resolveProviderOwner(PackageManager pm, List<Uri> uris) {
        String owner = null;
        int ownerUid = INVALID_UID;
        boolean foundContent = false;
        for (Uri uri : uris) {
            if (uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null) {
                continue;
            }
            foundContent = true;
            ProviderInfo provider = pm.resolveContentProvider(uri.getAuthority(), 0);
            if (provider == null || provider.applicationInfo == null) {
                return null;
            }
            String candidate = provider.packageName;
            int candidateUid = provider.applicationInfo.uid;
            if (owner == null) {
                owner = candidate;
                ownerUid = candidateUid;
            } else if (ownerUid != candidateUid) {
                return null;
            }
        }
        return foundContent && owner != null
                ? new Identity(owner, ownerUid, Method.CONTENT_PROVIDER, true)
                : null;
    }

    private static boolean packageMatchesUid(PackageManager pm, String packageName, int uid) {
        if (packageName == null) {
            return false;
        }
        try {
            return pm.getPackageUid(packageName, 0) == uid;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }
}
