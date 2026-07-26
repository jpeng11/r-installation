package dev.jpeng.rinstaller;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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

    Set<String> trustedPackages() {
        PackageManager packageManager = context.getPackageManager();
        Set<String> stored = packages();
        Set<String> trusted = new HashSet<>();
        Set<String> stale = new HashSet<>();
        for (String packageName : stored) {
            String pinned = preferences.getString(SIGNATURE_PREFIX + packageName, null);
            String current = signingDigest(packageManager, packageName);
            if (matchesPinnedDigest(pinned, current)) {
                trusted.add(packageName);
            } else if (!isInstalled(packageManager, packageName)) {
                stale.add(packageName);
            }
        }
        prune(stored, stale);
        return trusted;
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
        return matchesPinnedDigest(pinned, current);
    }

    String pinnedDigest(String packageName) {
        return preferences.getString(SIGNATURE_PREFIX + packageName, null);
    }

    static boolean matchesPinnedDigest(String pinned, String current) {
        return pinned != null && current != null && pinned.equals(current);
    }

    private void prune(Set<String> stored, Set<String> stale) {
        if (stale.isEmpty()) {
            return;
        }
        Set<String> updated = new HashSet<>(stored);
        updated.removeAll(stale);
        SharedPreferences.Editor editor = preferences.edit().putStringSet(PACKAGES, updated);
        for (String packageName : stale) {
            editor.remove(SIGNATURE_PREFIX + packageName);
        }
        editor.apply();
    }

    private static boolean isInstalled(PackageManager packageManager, String packageName) {
        try {
            packageManager.getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException exception) {
            return false;
        } catch (RuntimeException exception) {
            // Do not destroy a trust record when package inspection fails transiently.
            return true;
        }
    }

    static String signingDigest(PackageManager packageManager, String packageName) {
        try {
            PackageInfo info = packageManager.getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo signingInfo = info.signingInfo;
            if (signingInfo == null) {
                return null;
            }
            // Pin the signer on the currently installed APK. Using certificate history here
            // would keep trusting an app after it rotates away from the certificate the user
            // explicitly approved.
            Signature[] signatures = signingInfo.getApkContentsSigners();
            if (signatures == null || signatures.length == 0) {
                return null;
            }
            List<String> signerDigests = new ArrayList<>(signatures.length);
            for (Signature signature : signatures) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(signature.toByteArray());
                StringBuilder output = new StringBuilder(hash.length * 2);
                for (byte value : hash) {
                    output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
                }
                signerDigests.add(output.toString());
            }
            signerDigests.sort(Comparator.naturalOrder());
            return String.join(":", signerDigests);
        } catch (Exception ignored) {
            return null;
        }
    }
}
