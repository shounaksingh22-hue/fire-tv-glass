package com.ivantrj.glasslauncher;

import android.content.Intent;
import android.graphics.drawable.Drawable;

final class AppInfo {
    final String packageName;
    final String label;
    final Drawable banner;   // 16:9 TV banner, or null when the app only ships an icon
    final Drawable icon;
    final int glowColor;     // sampled from the banner (or icon)
    final Intent launchIntent;

    AppInfo(String packageName, String label, Drawable banner, Drawable icon, int glowColor, Intent launchIntent) {
        this.packageName = packageName;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.glowColor = glowColor;
        this.launchIntent = launchIntent;
    }
}
