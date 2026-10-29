package com.ivantrj.glasslauncher;

import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/** Shared binding and focus visuals for dock and grid tiles (both use item_tile.xml). */
final class Tiles {
    static final float FOCUS_SCALE = 1.08f;
    /** Customize > App names: "Always" keeps every label visible, dimmed when unfocused. */
    static boolean alwaysLabels;
    /** Tag on dock cells: their names show only on focus. */
    static final String DOCK = "dock";
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);
    private static final ViewOutlineProvider ROUNDED = new ViewOutlineProvider() {
        @Override public void getOutline(View v, Outline o) {
            o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 14 * v.getResources().getDisplayMetrics().density);
        }
    };

    static void bind(View cell, AppInfo app) {
        View tile = cell.findViewById(R.id.tile);
        ImageView banner = cell.findViewById(R.id.banner);
        ImageView icon = cell.findViewById(R.id.icon);
        TextView label = cell.findViewById(R.id.label);
        tile.setOutlineProvider(ROUNDED); // corners must not depend on the background (glass tiles have none)
        tile.setClipToOutline(true);
        label.setText(app.label);
        label.setTypeface(Fonts.outfit(cell.getContext(), 400));
        float d = cell.getResources().getDisplayMetrics().density;
        label.setShadowLayer(6 * d, 0, d, 0x99000000); // readable on bright wallpapers
        // Banners (opaque or our see-through glass ones) bring their own background
        tile.setBackgroundResource(app.banner != null ? 0 : R.drawable.tile_bg);
        if (app.banner != null) {
            banner.setImageDrawable(app.banner);
            banner.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
        } else {
            banner.setImageDrawable(null);
            banner.setVisibility(View.GONE);
            icon.setImageDrawable(app.icon);
            icon.setVisibility(View.VISIBLE);
        }
    }

    static void setFocused(View cell, boolean focused, boolean animate) {
        View tile = cell.findViewById(R.id.tile);
        View label = cell.findViewById(R.id.label);
        float scale = focused ? FOCUS_SCALE : 1f;
        float alpha = focused ? 1f : alwaysLabels && !DOCK.equals(cell.getTag()) ? 0.6f : 0f;
        tile.animate().cancel();
        label.animate().cancel();
        if (animate) {
            tile.animate().scaleX(scale).scaleY(scale).setDuration(200).setInterpolator(EASE).start();
            label.animate().alpha(alpha).setDuration(focused ? 220 : 120).start();
        } else {
            tile.setScaleX(scale);
            tile.setScaleY(scale);
            label.setAlpha(alpha);
        }
    }

    private Tiles() {}
}
