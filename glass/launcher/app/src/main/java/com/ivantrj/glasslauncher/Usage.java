package com.ivantrj.glasslauncher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Counts launches from Glass so the grid can put the apps you actually use first.
 * Score = launches, halved for every two weeks since the last launch, so habits
 * change the order within days and old favourites drift down on their own.
 */
final class Usage {

    private static final double HALF_LIFE_DAYS = 14;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("usage", Context.MODE_PRIVATE);
    }

    static void record(Context context, String pkg) {
        SharedPreferences p = prefs(context);
        p.edit()
                .putFloat(pkg + ":n", (float) score(p, pkg, System.currentTimeMillis()) + 1f)
                .putLong(pkg + ":t", System.currentTimeMillis())
                .apply();
    }

    /** When the app was last opened from Glass, or 0 if never. */
    static long lastUsed(Context context, String pkg) { return prefs(context).getLong(pkg + ":t", 0); }

    /** Decayed launch count, read once per load for every app. */
    static double score(Context context, String pkg, long now) { return score(prefs(context), pkg, now); }

    private static double score(SharedPreferences p, String pkg, long now) {
        float n = p.getFloat(pkg + ":n", 0f);
        if (n == 0f) return 0;
        double days = Math.max(0, now - p.getLong(pkg + ":t", now)) / 86_400_000d;
        return n * Math.pow(0.5, days / HALF_LIFE_DAYS);
    }

    private Usage() {}
}
