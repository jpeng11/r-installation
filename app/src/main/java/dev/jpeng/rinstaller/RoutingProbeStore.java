package dev.jpeng.rinstaller;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

final class RoutingProbeStore {
    private static final String PREFERENCES = "routing_probe";
    private static final String TOKEN = "token";
    private static final String ISSUED_AT = "issued_at";
    private static final long MAX_AGE_MILLIS = 10 * 60 * 1_000L;

    private RoutingProbeStore() {
    }

    static String issue(Context context) {
        String token = UUID.randomUUID().toString();
        preferences(context).edit()
                .putString(TOKEN, token)
                .putLong(ISSUED_AT, System.currentTimeMillis())
                .commit();
        return token;
    }

    static boolean consume(Context context, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        SharedPreferences preferences = preferences(context);
        String expected = preferences.getString(TOKEN, null);
        long issuedAt = preferences.getLong(ISSUED_AT, 0);
        if (!isValid(expected, issuedAt, token, System.currentTimeMillis())) {
            if (token.equals(expected)) {
                clear(preferences);
            }
            return false;
        }
        clear(preferences);
        return true;
    }

    static boolean isValid(
            String expected,
            long issuedAt,
            String candidate,
            long now
    ) {
        if (expected == null || candidate == null || !candidate.equals(expected)) {
            return false;
        }
        long age = now - issuedAt;
        if (age < 0 || age > MAX_AGE_MILLIS) {
            return false;
        }
        return true;
    }

    static void revoke(Context context, String token) {
        SharedPreferences preferences = preferences(context);
        if (token != null && token.equals(preferences.getString(TOKEN, null))) {
            clear(preferences);
        }
    }

    private static void clear(SharedPreferences preferences) {
        preferences.edit()
                .remove(TOKEN)
                .remove(ISSUED_AT)
                .commit();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
