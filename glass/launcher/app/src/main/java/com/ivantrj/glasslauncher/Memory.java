package com.ivantrj.glasslauncher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Frees RAM by closing cached background apps. killBackgroundProcesses only touches
 * processes Android already considers background, so the app on screen, music playing
 * in a foreground service and the screensaver are never affected. Run off the main thread.
 */
final class Memory {

    /** Never closed: Glass itself, and the Amazon launcher (a cold start makes Home slower). */
    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";

    /** Returns the megabytes of available memory gained. */
    static long freeBackground(Context context) {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        for (ApplicationInfo ai : context.getPackageManager().getInstalledApplications(0)) {
            String pkg = ai.packageName;
            if (pkg.equals(context.getPackageName()) || pkg.equals(AMAZON_LAUNCHER)) continue;
            am.killBackgroundProcesses(pkg);
        }
        SystemClock.sleep(600); // let the kernel reclaim the pages before measuring
        ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(after);
        return Math.max(0, (after.availMem - before.availMem) / (1024 * 1024));
    }

    /** "1.1 GB free of 1.7 GB" — what apps can still use right now. */
    static String summary(Context context) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        context.getSystemService(ActivityManager.class).getMemoryInfo(info);
        return size(info.availMem) + " free of " + size(info.totalMem);
    }

    private static String size(long bytes) {
        long mb = bytes / (1024 * 1024);
        return mb < 1000 ? mb + " MB" : String.format(Locale.US, "%.1f GB", mb / 1024f);
    }

    private Memory() {}
}
