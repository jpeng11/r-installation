package dev.jpeng.rinstaller;

import android.content.Context;
import android.content.SharedPreferences;

final class InstallerSettings {
    private static final String PREFERENCES = "installer_settings";
    private static final String SILENT_INSTALL_ENABLED = "silent_install_enabled";
    private static final String COMPLETION_TOAST_ENABLED = "completion_toast_enabled";

    private final SharedPreferences preferences;

    InstallerSettings(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    boolean isSilentInstallEnabled() {
        return preferences.getBoolean(SILENT_INSTALL_ENABLED, true);
    }

    void setSilentInstallEnabled(boolean enabled) {
        preferences.edit().putBoolean(SILENT_INSTALL_ENABLED, enabled).apply();
    }

    boolean isCompletionToastEnabled() {
        return preferences.getBoolean(COMPLETION_TOAST_ENABLED, true);
    }

    void setCompletionToastEnabled(boolean enabled) {
        preferences.edit().putBoolean(COMPLETION_TOAST_ENABLED, enabled).apply();
    }
}
