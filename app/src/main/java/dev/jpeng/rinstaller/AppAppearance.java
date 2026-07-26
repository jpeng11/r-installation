package dev.jpeng.rinstaller;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

final class AppAppearance {
    static final String SYSTEM = "system";
    static final String LIGHT = "light";
    static final String DARK = "dark";

    private static final String PREFERENCES = "app_settings";
    private static final String APPEARANCE = "appearance";

    private AppAppearance() {}

    static Context wrap(Context base) {
        String appearance = selected(base);
        if (SYSTEM.equals(appearance)) {
            return base;
        }

        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        int nightMode = DARK.equals(appearance)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | nightMode;
        return base.createConfigurationContext(configuration);
    }

    static int selectedIndex(Context context) {
        return indexForAppearance(selected(context));
    }

    static void select(Activity activity, int index) {
        preferences(activity)
                .edit()
                .putString(APPEARANCE, appearanceForIndex(index))
                .apply();
        activity.recreate();
    }

    static int indexForAppearance(String appearance) {
        if (LIGHT.equals(appearance)) {
            return 1;
        }
        if (DARK.equals(appearance)) {
            return 2;
        }
        return 0;
    }

    static String appearanceForIndex(int index) {
        return switch (index) {
            case 1 -> LIGHT;
            case 2 -> DARK;
            default -> SYSTEM;
        };
    }

    private static String selected(Context context) {
        return preferences(context).getString(APPEARANCE, SYSTEM);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
