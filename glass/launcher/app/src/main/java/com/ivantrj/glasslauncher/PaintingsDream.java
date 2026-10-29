package com.ivantrj.glasslauncher;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.service.dreams.DreamService;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Paintings screensaver, like an art-mode TV: the whole screen becomes a framed painting
 * (PaintingWall). Customize switches between it and Aerial Views.
 */
public class PaintingsDream extends DreamService {

    private static final String AERIAL = "com.neilturner.aerialviews/.ui.screensaver.DreamActivity";

    private PaintingWall wall;

    // ---------------------------------------------------------------- Customize

    /** Paintings are on this TV, and Glass may switch the screensaver (granted over ADB by tv-kit). */
    static boolean available(Context context) {
        return !names(context).isEmpty()
                && context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean active(Context context) {
        String d = Settings.Secure.getString(context.getContentResolver(), "screensaver_components");
        return d != null && d.startsWith(context.getPackageName() + "/");
    }

    /** Makes Paintings the screensaver, or puts back the one it replaced (Aerial Views). */
    static void use(Context context, boolean on) {
        if (on == active(context)) return;
        SharedPreferences p = context.getSharedPreferences("paintings", MODE_PRIVATE);
        String component;
        if (on) {
            String old = Settings.Secure.getString(context.getContentResolver(), "screensaver_components");
            if (old != null && !old.isEmpty()) p.edit().putString("previous", old).apply();
            component = new ComponentName(context, PaintingsDream.class).flattenToShortString();
        } else {
            component = p.getString("previous", AERIAL);
        }
        Settings.Secure.putString(context.getContentResolver(), "screensaver_components", component);
        Settings.Secure.putString(context.getContentResolver(), "screensaver_default_component", component);
    }

    static File dir(Context context) {
        return new File(context.getFilesDir(), "paintings");
    }

    static List<String> names(Context context) {
        String[] files = dir(context).list();
        List<String> out = new ArrayList<>();
        if (files != null) {
            for (String f : files) if (f.endsWith(".jpg")) out.add(f);
        }
        Collections.sort(out);
        return out;
    }

    // ---------------------------------------------------------------- screensaver

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); // any key wakes the TV
        setFullscreen(true);
        wall = new PaintingWall(this);
        setContentView(wall.view(getWindowManager().getDefaultDisplay()));
    }

    @Override
    public void onDetachedFromWindow() {
        if (wall != null) wall.release();
        wall = null;
        super.onDetachedFromWindow();
    }
}
