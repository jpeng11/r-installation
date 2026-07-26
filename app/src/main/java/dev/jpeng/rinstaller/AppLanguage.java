package dev.jpeng.rinstaller;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

final class AppLanguage {
    static final String SYSTEM = "";
    static final String ENGLISH = "en";
    static final String SIMPLIFIED_CHINESE = "zh-CN";

    private static final String PREFERENCES = "app_settings";
    private static final String LANGUAGE = "language";

    private AppLanguage() {}

    static Context wrap(Context base) {
        if (Build.VERSION.SDK_INT >= 33) {
            return base;
        }
        String tag = preferences(base).getString(LANGUAGE, SYSTEM);
        if (tag == null || tag.isEmpty()) {
            return base;
        }
        Locale locale = Locale.forLanguageTag(tag);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(locale));
        configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }

    static int selectedIndex(Context context) {
        return indexForTag(selectedTag(context));
    }

    static void select(Activity activity, int index) {
        String tag = tagForIndex(index);
        preferences(activity).edit().putString(LANGUAGE, tag).apply();
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = activity.getSystemService(LocaleManager.class);
            if (manager != null) {
                manager.setApplicationLocales(LocaleList.forLanguageTags(tag));
                return;
            }
        }
        activity.recreate();
    }

    static int indexForTag(String tag) {
        if (tag != null && tag.toLowerCase(Locale.ROOT).startsWith("zh")) {
            return 2;
        }
        if (tag != null && tag.toLowerCase(Locale.ROOT).startsWith("en")) {
            return 1;
        }
        return 0;
    }

    static String tagForIndex(int index) {
        return switch (index) {
            case 1 -> ENGLISH;
            case 2 -> SIMPLIFIED_CHINESE;
            default -> SYSTEM;
        };
    }

    private static String selectedTag(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager != null) {
                return manager.getApplicationLocales().toLanguageTags();
            }
        }
        return preferences(context).getString(LANGUAGE, SYSTEM);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
