package com.ivantrj.glasslauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Customize as a dock, the Apps dock's twin hanging from the Customize button on the right:
 * each setting is an iPhone Settings-style icon (white glyph on its own colour), the focused
 * one floats out magnified with its name and current choice in a tooltip beside it.
 * Up/Down move, Left/Right/OK change the choice, Back closes.
 */
final class LookDrawer {

    /** A setting in the drawer: its tile, and how to read and change its choice. */
    static final class Item {
        final String title;
        final int color, glyph;

        Item(String title, int color, int glyph) {
            this.title = title;
            this.color = color;
            this.glyph = glyph;
        }
    }

    interface Host {
        /** The current choice of item i, as shown in the tooltip; null for none (About). */
        String value(int i);
        /** Steps item i's choice by dir (-1 or +1); for About, OK only (dir 0). */
        void change(int i, int dir);
        void closed();
    }

    // Same feel as AppDrawer: same window, magnification and float
    private static final int WINDOW = 5;
    private static final float[] MAGNIFY = {1.35f, 1.12f};
    private static final float FLOAT = 1.7f;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.8f);

    private final Context context;
    private final FrameLayout root;
    private final float density;
    private final Host host;

    private FrameLayout dock, bar;
    private TextView tooltip;
    private final List<ImageView> icons = new ArrayList<>();
    private List<Item> items;
    private int idx, first, visible, size, gap, inner, barTop, iconRight;

    LookDrawer(Context context, FrameLayout root, Host host) {
        this.context = context;
        this.root = root;
        this.density = context.getResources().getDisplayMetrics().density;
        this.host = host;
    }

    boolean isOpen() { return dock != null; }

    /** centreX/top: where the bar hangs from, just under the Customize button. */
    void open(List<Item> items, int centreX, int top) {
        if (dock != null || items.isEmpty()) return;
        this.items = items;
        idx = 0;
        first = 0;
        icons.clear();

        size = px(34);
        gap = px(10);
        int pad = px(10);
        visible = Math.min(WINDOW, items.size());
        float tallest = 0;
        for (int f = 0; f < visible; f++) tallest = Math.max(tallest, stackHeight(f));
        inner = Math.round(tallest);
        int barW = Math.round(size * MAGNIFY[1]) + 2 * pad;
        int barH = inner + 2 * pad;
        barTop = top;

        dock = new FrameLayout(context);
        dock.setClipChildren(false);

        bar = new FrameLayout(context);
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0x8C1C1C22);
        glass.setCornerRadius(px(22));
        glass.setStroke(Math.max(1, px(1) / 2), 0x38FFFFFF);
        bar.setBackground(glass);
        bar.setPadding(pad, pad, pad, pad);
        bar.setClipChildren(false); // nothing is cut: the focused icon floats out of the bar
        bar.setClipToPadding(false);
        bar.setElevation(px(8));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(barW, barH);
        bp.leftMargin = centreX - barW / 2;
        bp.topMargin = barTop;
        dock.addView(bar, bp);

        ViewOutlineProvider rounded = new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getWidth() * 0.22f); }
        };
        int iconLeft = (barW - 2 * pad - size) / 2;
        iconRight = bp.leftMargin + pad + iconLeft + size;
        for (Item item : items) {
            ImageView icon = new ImageView(context);
            icon.setImageBitmap(tile(item));
            icon.setOutlineProvider(rounded);
            icon.setClipToOutline(true);
            icon.setPivotX(size); // grows out towards the screen: leftwards, as the bar hangs on the right
            icon.setPivotY(size / 2f);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(size, size);
            ip.leftMargin = iconLeft;
            bar.addView(icon, ip);
            icons.add(icon);
        }

        tooltip = new TextView(context);
        tooltip.setTextSize(15);
        tooltip.setTextColor(0xF2FFFFFF);
        tooltip.setTypeface(Fonts.outfit(context, 400));
        tooltip.setSingleLine(true);
        GradientDrawable tip = new GradientDrawable();
        tip.setColor(0xE61C1C22);
        tip.setCornerRadius(px(8));
        tip.setStroke(Math.max(1, px(1) / 2), 0x2EFFFFFF);
        tooltip.setBackground(tip);
        tooltip.setPadding(px(12), px(5), px(12), px(6));
        tooltip.setElevation(px(10));
        dock.addView(tooltip, new FrameLayout.LayoutParams(-2, -2));

        root.addView(dock, new FrameLayout.LayoutParams(-1, -1));
        layoutIcons(false);
        // Unfolds down out of the button
        dock.setAlpha(0f);
        dock.setTranslationY(-px(24));
        dock.animate().alpha(1f).translationY(0).setDuration(220).setInterpolator(EASE).start();
    }

    void close() {
        if (dock == null) return;
        View v = dock;
        dock = null;
        v.animate().alpha(0f).translationY(-px(16)).setDuration(160).withEndAction(() -> root.removeView(v)).start();
        host.closed();
    }

    void key(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (idx > 0) { idx--; layoutIcons(true); }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (idx < items.size() - 1) { idx++; layoutIcons(true); }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                boolean ok = key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT;
                if (host.value(idx) == null) { // About: OK only
                    if (ok) host.change(idx, 0);
                    break;
                }
                host.change(idx, key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                if (dock == null) break;
                showTooltip(true);
                pulse(icons.get(idx));
                break;
            default: // Back, Menu
                close();
        }
    }

    /** Re-reads the focused choice, e.g. after something else changed it. */
    void refresh() {
        if (dock != null) showTooltip(false);
    }

    private float scaleAt(int i) {
        int d = Math.abs(i - idx);
        return d < MAGNIFY.length ? MAGNIFY[d] : 1f;
    }

    private float stackHeight(int f) {
        float h = gap * (visible - 1);
        for (int i = 0; i < visible; i++) {
            int d = Math.abs(i - f);
            h += size * (d < MAGNIFY.length ? MAGNIFY[d] : 1f);
        }
        return h;
    }

    /** As in AppDrawer: the focus stays mid-window, the window's icons stack at their magnified sizes. */
    private void layoutIcons(boolean animate) {
        int n = icons.size();
        first = Math.max(0, Math.min(n - visible, idx - visible / 2));
        int last = first + visible - 1;
        float total = gap * (visible - 1);
        for (int i = first; i <= last; i++) total += size * scaleAt(i);
        float y = (inner - total) / 2f;
        for (int i = 0; i < n; i++) {
            float s = scaleAt(i), ty, alpha = 1f;
            float look = i == idx ? FLOAT : s;
            if (i < first) {
                ty = 0;
                alpha = 0f;
            } else if (i > last) {
                ty = inner - size;
                alpha = 0f;
            } else {
                ty = y + (size * s - size) / 2f;
                y += size * s + gap;
                if (i != idx && ((i == first && first > 0) || (i == last && last < n - 1))) alpha = 0.4f;
            }
            if (i == idx) focusCentre = ty + size / 2f;
            ImageView icon = icons.get(i);
            if (animate) {
                icon.animate().scaleX(look).scaleY(look).translationY(ty).translationZ(i == idx ? px(14) : 0)
                        .alpha(alpha).setDuration(200).setInterpolator(EASE).start();
            } else {
                icon.setScaleX(look);
                icon.setScaleY(look);
                icon.setTranslationZ(i == idx ? px(14) : 0);
                icon.setTranslationY(ty);
                icon.setAlpha(alpha);
            }
        }
        showTooltip(animate);
    }

    private float focusCentre;

    /** "Title  ‹ choice ›" beside the focused icon, clear of it as it floats out to the left. */
    private void showTooltip(boolean animate) {
        Item item = items.get(idx);
        String value = host.value(idx);
        SpannableStringBuilder t = new SpannableStringBuilder(item.title);
        if (value != null) {
            int at = t.length();
            t.append("    ‹  ").append(value).append("  ›");
            t.setSpan(new ForegroundColorSpan(0xB3FFFFFF), at, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tooltip.setText(t);
        tooltip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float tipX = iconRight - size * FLOAT - px(14) - tooltip.getMeasuredWidth();
        float tipY = barTop + px(10) + focusCentre - tooltip.getMeasuredHeight() / 2f;
        if (animate) tooltip.animate().translationX(tipX).translationY(tipY).setDuration(200).setInterpolator(EASE).start();
        else {
            tooltip.setTranslationX(tipX);
            tooltip.setTranslationY(tipY);
        }
    }

    /** A small press: the icon dips and springs back when its choice changes. */
    private void pulse(ImageView icon) {
        icon.animate().cancel();
        icon.animate().scaleX(FLOAT * 0.9f).scaleY(FLOAT * 0.9f).setDuration(80).withEndAction(() ->
                icon.animate().scaleX(FLOAT).scaleY(FLOAT).setDuration(160).setInterpolator(EASE).start()).start();
    }

    /** iPhone Settings-style tile: the item's colour, a touch lighter at the top, white glyph. */
    private Bitmap tile(Item item) {
        int n = 128;
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, n, lighter(item.color), item.color, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, n, n, p);
        Drawable g = context.getDrawable(item.glyph);
        if (g != null) {
            int inset = Math.round(n * 0.2f);
            g.setBounds(inset, inset, n - inset, n - inset);
            g.draw(c);
        }
        return out;
    }

    private static int lighter(int color) {
        int r = Math.min(255, ((color >> 16) & 0xff) + 28);
        int g = Math.min(255, ((color >> 8) & 0xff) + 28);
        int b = Math.min(255, (color & 0xff) + 28);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private int px(float dp) { return Math.round(dp * density); }
}
