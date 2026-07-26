package dev.jpeng.rinstaller;

import java.util.Locale;

final class AppSearch {
    private AppSearch() {}

    static boolean matches(String appName, String packageName, String rawQuery) {
        String query = normalize(rawQuery).trim();
        if (query.isEmpty()) {
            return true;
        }
        return normalize(appName).contains(query) || normalize(packageName).contains(query);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
