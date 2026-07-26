package dev.jpeng.rinstaller;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class TrustedStore {
    private static final String PREFS = "trusted_sources";
    private static final String PACKAGES = "packages";
    private static final String SIGNATURE_PREFIX = "signature.";

    private final Context context;
    private final SharedPreferences preferences;

    TrustedStore(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Set<String> packages() {
        Set<String> stored = preferences.getStringSet(PACKAGES, Collections.emptySet());
        return new HashSet<>(stored == null ? Collections.emptySet() : stored);
    }

    boolean trust(String packageName) {
        String digest = signingDigest(context.getPackageManager(), packageName);
        if (digest == null) {
            return false;
        }
        Set<String> updated = packages();
        updated.add(packageName);
        preferences.edit()
                .putStringSet(PACKAGES, updated)
                .putString(SIGNATURE_PREFIX + packageName, digest)
                .apply();
        return true;
    }

    void untrust(String packageName) {
        Set<String> updated = packages();
        updated.remove(packageName);
        preferences.edit()
                .putStringSet(PACKAGES, updated)
                .remove(SIGNATURE_PREFIX + packageName)
                .apply();
    }

    boolean isTrusted(String packageName) {
        if (packageName == null || !packages().contains(packageName)) {
            return false;
        }
        String pinned = preferences.getString(SIGNATURE_PREFIX + packageName, null);
        String current = signingDigest(context.getPackageManager(), packageName);
        return pinned != null && pinned.equals(current);
    }

    String pinnedDigest(String packageName) {
        return preferences.getString(SIGNATURE_PREFIX + packageName, null);
    }

    static String signingDigest(PackageManager packageManager, String packageName) {
        try {
            PackageInfo info = packageManager.getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo signingInfo = info.signingInfo;
            if (signingInfo == null) {
                return null;
            }
            Signature[] signatures = signingInfo.hasMultipleSigners()
                    ? signingInfo.getApkContentsSigners()
                    : signingInfo.getSigningCertificateHistory();
            if (signatures == null || signatures.length == 0) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(signatures[0].toByteArray());
            StringBuilder output = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return output.toString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
