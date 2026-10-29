package com.ivantrj.glasslauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Settings button: a compact drop-down under the button, Control Center style: round
 * glass buttons with mini icons and small names, each opening a settings page directly.
 * Works on any Android TV: each button lists the links to try (Fire OS first, then
 * standard Android) and shows only if this device lets Glass open one of them; pages a
 * device keeps to itself stay reachable through "All settings".
 * Up/Down/Left/Right move, OK opens, Back closes.
 */
final class SettingsSheet {

    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.8f);

    private static final class Tile {
        final String label;
        final int icon;
        final Intent intent;

        Tile(String label, int icon, Intent intent) {
            this.label = label;
            this.icon = icon;
            this.intent = intent;
        }
    }

    private final Context context;
    private final FrameLayout root;
    private final float density;
    private final Consumer<Intent> launch;

    private View sheet;
    private final List<Tile> tiles = new ArrayList<>();
    private final List<View> cells = new ArrayList<>();
    private int idx, columns;

    SettingsSheet(Context context, FrameLayout root, Consumer<Intent> launch) {
        this.context = context;
        this.root = root;
        this.density = context.getResources().getDisplayMetrics().density;
        this.launch = launch;
    }

    boolean isOpen() { return sheet != null; }

    /** right/top: the drop-down's top-right corner, just under the Settings button. */
    void open(int right, int top) {
        if (sheet != null) return;
        findTiles();
        idx = 0;
        cells.clear();

        // Balanced and compact, never a lone button: at most 3 across, as few rows as that
        // allows, then as few columns as fill them (6 -> 3x2, 4 -> 2x2); a short last row is centred
        int n = tiles.size();
        int rows = (n + 2) / 3;
        columns = (n + rows - 1) / rows;

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackgroundResource(R.drawable.menu_bg);
        box.setPadding(px(12), px(14), px(12), px(10));
        box.setClipChildren(false);

        LinearLayout row = null;
        for (int i = 0; i < n; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(context);
                row.setClipChildren(false);
                box.addView(row);
            }
            View cell = cell(tiles.get(i));
            row.addView(cell, new LinearLayout.LayoutParams(px(96), px(92)));
            cells.add(cell);
        }

        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = Math.max(px(24), right - box.getMeasuredWidth());
        lp.topMargin = top;
        root.addView(box, lp);
        sheet = box;
        focus(true, false);
        // Drops down out of the button
        box.setPivotX(right - lp.leftMargin);
        box.setPivotY(0);
        box.setAlpha(0f);
        box.setScaleX(0.94f);
        box.setScaleY(0.94f);
        box.setTranslationY(-px(8));
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(180).setInterpolator(EASE).start();
    }

    void close() {
        if (sheet == null) return;
        View v = sheet;
        sheet = null;
        v.animate().alpha(0f).translationY(-px(6)).setDuration(130).withEndAction(() -> root.removeView(v)).start();
    }

    void key(int key) {
        int n = cells.size(), next = idx;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (idx % columns > 0) next--; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (idx % columns < columns - 1 && idx + 1 < n) next++; break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (idx < columns) { close(); return; } // up from the top row: back to the button
                next -= columns;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (idx / columns < (n - 1) / columns) next = Math.min(idx + columns, n - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                Intent intent = tiles.get(idx).intent;
                close();
                launch.accept(intent);
                return;
            default: // Back, Menu
                close();
                return;
        }
        if (next == idx) return;
        focus(false, true);
        idx = next;
        focus(true, true);
    }

    /** Focused: the circle turns solid white with a dark icon, like Control Center. */
    private void focus(boolean on, boolean animate) {
        View cell = cells.get(idx);
        View circle = cell.findViewWithTag("circle");
        ImageView icon = cell.findViewWithTag("icon");
        View label = cell.findViewWithTag("label");
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(on ? 0xF2FFFFFF : 0x24FFFFFF);
        circle.setBackground(d);
        icon.setColorFilter(on ? 0xFF1C1C1E : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        float s = on ? 1.08f : 1f, a = on ? 1f : 0.7f;
        circle.animate().scaleX(s).scaleY(s).setDuration(animate ? 160 : 0).setInterpolator(EASE).start();
        label.animate().alpha(a).setDuration(animate ? 160 : 0).start();
    }

    private View cell(Tile t) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false);
        FrameLayout circle = new FrameLayout(context);
        circle.setTag("circle");
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0x24FFFFFF);
        circle.setBackground(d);
        ImageView icon = new ImageView(context);
        icon.setTag("icon");
        icon.setImageResource(t.icon);
        circle.addView(icon, new FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER));
        cell.addView(circle, new LinearLayout.LayoutParams(px(52), px(52)));
        TextView label = new TextView(context);
        label.setTag("label");
        label.setText(t.label);
        label.setTextColor(0xFFFFFFFF);
        label.setAlpha(0.7f);
        label.setTextSize(12);
        label.setTypeface(Fonts.outfit(context, 400));
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setPadding(0, px(7), 0, 0);
        cell.addView(label);
        return cell;
    }

    // ---------------------------------------------------------------- which pages this TV allows

    private void findTiles() {
        tiles.clear();
        add("Network", R.drawable.ic_wifi, action(Settings.ACTION_WIFI_SETTINGS), action(Settings.ACTION_WIRELESS_SETTINGS));
        add("Display", R.drawable.ic_set_display, fire("DISPLAY_AND_SOUNDS"),
                action(Settings.ACTION_DISPLAY_SETTINGS), action(Settings.ACTION_SOUND_SETTINGS));
        add("Apps", R.drawable.ic_apps, fire("APPLICATIONS"), action(Settings.ACTION_APPLICATION_SETTINGS));
        add("Remotes", R.drawable.ic_set_remote, fire("CONTROLLERS"), action(Settings.ACTION_BLUETOOTH_SETTINGS));
        add("Accessibility", R.drawable.ic_set_access, action(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        add("Preferences", R.drawable.ic_set_globe, fire("PREFERENCES"), action(Settings.ACTION_LOCALE_SETTINGS));
        add("Date & Time", R.drawable.ic_set_clock, fire("DATE_TIME"), action(Settings.ACTION_DATE_SETTINGS));
        add("Account", R.drawable.ic_set_account, fire("MY_ACCOUNT"), action(Settings.ACTION_SYNC_SETTINGS));
        add("Device", R.drawable.ic_set_device, fire("DEVICE"), action(Settings.ACTION_DEVICE_INFO_SETTINGS));
        // Fire OS's full settings grid is its own screen only via this launcher activity
        Intent fireAll = new Intent().setComponent(new ComponentName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity"));
        add("All settings", R.drawable.ic_settings, fireAll, action(Settings.ACTION_SETTINGS));
    }

    private static Intent action(String a) { return new Intent(a); }

    private static Intent fire(String page) { return new Intent("com.amazon.device.settings.action." + page); }

    private void add(String label, int icon, Intent... tries) {
        for (Intent t : tries) {
            Intent ok = openable(t);
            if (ok != null) {
                tiles.add(new Tile(label, icon, ok));
                return;
            }
        }
    }

    /**
     * The first activity for this intent that Glass may start: exported, and not behind a
     * permission Glass lacks (Fire OS keeps some pages for its own launcher). Pinned as an
     * explicit component so the TV never shows an app chooser.
     */
    private Intent openable(Intent intent) {
        PackageManager pm = context.getPackageManager();
        for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (!ai.exported) continue;
            if (ai.permission != null && context.checkSelfPermission(ai.permission) != PackageManager.PERMISSION_GRANTED) continue;
            // A fresh task each time: Fire OS settings pages share one, and would resurface the last page opened
            return new Intent(intent).setComponent(new ComponentName(ai.packageName, ai.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        }
        return null;
    }

    private int px(float dp) { return Math.round(dp * density); }
}
