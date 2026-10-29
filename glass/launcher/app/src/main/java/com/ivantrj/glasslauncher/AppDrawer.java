package com.ivantrj.glasslauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Mac-style Dock hanging down the left edge from the Apps button: a short bar of mini
 * icons that scrolls with the focus, so only a few show at once and the rest slide out
 * of view. The focused icon magnifies and its neighbours swell, all inside the bar,
 * with the app's name in a tooltip beside it. Removed on close, so it costs nothing
 * while hidden. Up/Down move, OK opens, Menu opens app info, Back/Left/Right close.
 */
final class AppDrawer {

    /** Icons shown at once; the bar scrolls through the rest. */
    private static final int WINDOW = 5;
    /** Room each icon takes in the bar by distance from the focus (0, 1 away), like the Mac Dock. */
    private static final float[] MAGNIFY = {1.35f, 1.12f};
    /** The focused icon floats bigger than its room, out past the bar's edge and above its neighbours. */
    private static final float FLOAT = 1.7f;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.8f);

    private final Context context;
    private final FrameLayout root;
    private final float density;
    private final Consumer<AppInfo> onOpen, onInfo;

    private FrameLayout dock, bar;
    private TextView tooltip;
    private final List<ImageView> icons = new ArrayList<>();
    private List<AppInfo> apps;
    private int idx, first, visible, size, gap, inner, barTop;

    AppDrawer(Context context, FrameLayout root, Consumer<AppInfo> onOpen, Consumer<AppInfo> onInfo) {
        this.context = context;
        this.root = root;
        this.density = context.getResources().getDisplayMetrics().density;
        this.onOpen = onOpen;
        this.onInfo = onInfo;
    }

    boolean isOpen() { return dock != null; }

    /** centreX/top: where the bar hangs from, just under the Apps button. */
    void open(List<AppInfo> apps, int centreX, int top) {
        if (dock != null || apps.isEmpty()) return;
        this.apps = apps;
        idx = 0;
        first = 0;
        icons.clear();

        size = px(34);
        gap = px(10);
        int pad = px(10);
        visible = Math.min(WINDOW, apps.size());
        // Tall enough for the window at its most magnified (focus mid-bar), wide enough for the focused icon
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
        for (AppInfo app : apps) {
            ImageView icon = new ImageView(context);
            icon.setImageBitmap(iosIcon(context, app));
            icon.setOutlineProvider(rounded); // rounded square like an iPhone icon, whatever shape the app ships
            icon.setClipToOutline(true);
            icon.setPivotX(0); // grows out towards the screen, like the Dock off the screen edge
            icon.setPivotY(size / 2f);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(size, size);
            ip.leftMargin = (barW - 2 * pad - size) / 2;
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
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2);
        tp.leftMargin = bp.leftMargin + pad + (barW - 2 * pad - size) / 2 + Math.round(size * FLOAT) + px(14);
        dock.addView(tooltip, tp);

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
    }

    void key(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (idx > 0) { idx--; layoutIcons(true); }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (idx < apps.size() - 1) { idx++; layoutIcons(true); }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                AppInfo app = apps.get(idx);
                close();
                onOpen.accept(app);
                break;
            case KeyEvent.KEYCODE_MENU:
                AppInfo info = apps.get(idx);
                close();
                onInfo.accept(info);
                break;
            default: // Back, Left, Right
                close();
        }
    }

    private float scaleAt(int i) {
        int d = Math.abs(i - idx);
        return d < MAGNIFY.length ? MAGNIFY[d] : 1f;
    }

    /** Height of a full window whose focus sits at window position f. */
    private float stackHeight(int f) {
        float h = gap * (visible - 1);
        for (int i = 0; i < visible; i++) {
            int d = Math.abs(i - f);
            h += size * (d < MAGNIFY.length ? MAGNIFY[d] : 1f);
        }
        return h;
    }

    /**
     * Keeps the focus mid-window (the bar scrolls past the rest), then stacks the
     * window's icons at their magnified sizes, centred in the bar. Icons before and
     * after the window wait just outside its ends, hidden, ready to slide in.
     */
    private void layoutIcons(boolean animate) {
        int n = icons.size();
        first = Math.max(0, Math.min(n - visible, idx - visible / 2));
        int last = first + visible - 1;
        float total = gap * (visible - 1);
        for (int i = first; i <= last; i++) total += size * scaleAt(i);
        float y = (inner - total) / 2f;
        float focusCentre = 0;
        for (int i = 0; i < n; i++) {
            float s = scaleAt(i), ty, alpha = 1f;
            float look = i == idx ? FLOAT : s; // drawn size; s is the room it takes in the stack
            if (i < first) {
                ty = 0; // fades out at the top end
                alpha = 0f;
            } else if (i > last) {
                ty = inner - size; // fades out at the bottom end
                alpha = 0f;
            } else {
                ty = y + (size * s - size) / 2f; // centred in its room (pivot is mid-height)
                y += size * s + gap;
                // More apps beyond this end: hint at them with a faded icon
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

        tooltip.setText(apps.get(idx).label);
        tooltip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float tipY = barTop + px(10) + focusCentre - tooltip.getMeasuredHeight() / 2f;
        if (animate) tooltip.animate().translationY(tipY).setDuration(200).setInterpolator(EASE).start();
        else tooltip.setTranslationY(tipY);
    }

    /**
     * The app's art as a filled square, like an iPhone icon: full-bleed icons as they are,
     * icons with see-through edges on a white (or, for white art, dark) tile. Apps that
     * only ship a wide TV banner (a wordmark like "prime video") get a single bold initial
     * on the banner's own brand colour, so new installs are handled automatically.
     */
    static Bitmap iosIcon(Context context, AppInfo app) {
        int n = 128;
        Drawable d = app.icon != null ? app.icon : app.banner;
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        if (d == null) return out;
        Canvas c = new Canvas(out);
        Rect old = new Rect(d.getBounds());
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        if (w > 0 && h > 0 && w > h * 1.2f) { // banner only: brand colour + initial
            Bitmap probe = render(d, 64, Math.round(64f * h / w));
            int bg = probe.getPixel(1, probe.getHeight() / 2) | 0xFF000000;
            probe.recycle();
            c.drawColor(bg);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            boolean light = Color.red(bg) * 0.3f + Color.green(bg) * 0.59f + Color.blue(bg) * 0.11f > 170;
            p.setColor(light ? 0xFF1C1C1E : 0xFFFFFFFF);
            p.setTypeface(Fonts.outfit(context, 600));
            p.setTextSize(n * 0.56f);
            p.setTextAlign(Paint.Align.CENTER);
            String initial = app.label.trim().isEmpty() ? "?" : app.label.trim().substring(0, 1).toUpperCase();
            Rect b = new Rect();
            p.getTextBounds(initial, 0, initial.length(), b);
            c.drawText(initial, n / 2f, n / 2f - b.exactCenterY(), p);
        } else {
            Bitmap probe = render(d, n, n);
            int m = n / 16;
            boolean fullBleed = true;
            for (int[] p : new int[][] {{m, m}, {n - 1 - m, m}, {m, n - 1 - m}, {n - 1 - m, n - 1 - m}}) {
                fullBleed &= Color.alpha(probe.getPixel(p[0], p[1])) > 200;
            }
            if (fullBleed) {
                c.drawBitmap(probe, 0, 0, null);
            } else {
                c.drawColor(mostlyWhite(probe) ? 0xFF1C1C1E : 0xFFFFFFFF);
                int inset = Math.round(n * 0.14f);
                d.setBounds(inset, inset, n - inset, n - inset);
                d.draw(c);
            }
            probe.recycle();
        }
        d.setBounds(old);
        return out;
    }

    private static Bitmap render(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        return b;
    }

    /** True when the art's visible pixels are nearly all white, so a white tile would hide it. */
    private static boolean mostlyWhite(Bitmap b) {
        int seen = 0, white = 0;
        for (int y = 0; y < b.getHeight(); y += 4) {
            for (int x = 0; x < b.getWidth(); x += 4) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                seen++;
                if (Color.red(p) > 225 && Color.green(p) > 225 && Color.blue(p) > 225) white++;
            }
        }
        return seen > 0 && white > seen * 0.7f;
    }

    private int px(float dp) { return Math.round(dp * density); }
}
