package com.ivantrj.glasslauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.Collections;

/**
 * QR codes anyone can scan with their phone's camera, served by Glass (see Remote): the
 * phone remote (/) and this TV's guide (/guide), which explains everything from the
 * remote to the dock. Any key closes it.
 */
final class GuideSheet {

    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.8f);

    private final Context context;
    private final FrameLayout root;
    private final float density;
    private View sheet;

    GuideSheet(Context context, FrameLayout root) {
        this.context = context;
        this.root = root;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    boolean isOpen() { return sheet != null; }

    /**
     * remoteOnly (Tools > Phone remote): one QR straight to the phone remote. Otherwise
     * (Customize > About) two: the phone remote first, the TV guide beside it.
     */
    void open(boolean remoteOnly) {
        if (sheet != null) return;
        String address = Remote.address(context);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackgroundResource(R.drawable.panel_bg);
        box.setPadding(px(40), px(28), px(40), px(28));
        box.setMinimumWidth(px(480)); // the title and hint set the width, not the QR tiles

        box.addView(text(remoteOnly ? "Use your phone as the remote" : "Scan with your phone", 22, 400, 0xFFFFFFFF));
        TextView sub = text(address == null ? "Connect the TV to Wi-Fi first"
                : "Open the camera and point it here  |  same Wi-Fi as the TV", 14, 300, 0x99FFFFFF);
        sub.setPadding(0, px(6), 0, px(22));
        box.addView(sub);

        if (address != null) {
            LinearLayout codes = new LinearLayout(context);
            codes.addView(code(address + "/", remoteOnly ? null : "Phone remote"));
            if (!remoteOnly) {
                View gap = new View(context);
                codes.addView(gap, new LinearLayout.LayoutParams(px(36), 1));
                codes.addView(code(address + "/guide", "Learn this TV"));
            }
            box.addView(codes, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            if (remoteOnly) {
                TextView then = text("Then type the 4-digit code the TV shows", 15, 400, 0xE6FFFFFF);
                then.setPadding(0, px(16), 0, 0);
                box.addView(then);
            }
        }

        root.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        sheet = box;
        box.setAlpha(0f);
        box.setScaleX(0.96f);
        box.setScaleY(0.96f);
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(EASE).start();
    }

    void close() {
        if (sheet == null) return;
        View v = sheet;
        sheet = null;
        v.animate().alpha(0f).setDuration(140).withEndAction(() -> root.removeView(v)).start();
    }

    /** A QR on a white tile (dark on light: what phone cameras read best), with its name under it if given. */
    private View code(String url, String label) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout tile = new FrameLayout(context);
        GradientDrawable white = new GradientDrawable();
        white.setColor(Color.WHITE);
        white.setCornerRadius(px(18));
        tile.setBackground(white);
        tile.setPadding(px(14), px(14), px(14), px(14));
        ImageView image = new ImageView(context);
        image.setImageBitmap(qr(url, px(180)));
        tile.addView(image, new FrameLayout.LayoutParams(px(180), px(180)));
        col.addView(tile, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (label != null) {
            TextView name = text(label, 15, 400, 0xE6FFFFFF);
            name.setPadding(0, px(14), 0, 0);
            col.addView(name);
        }
        return col;
    }

    private static Bitmap qr(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Collections.singletonMap(EncodeHintType.MARGIN, 0));
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF111114 : Color.WHITE;
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (WriterException e) {
            return null;
        }
    }

    private TextView text(String s, int sp, int weight, int color) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Fonts.outfit(context, weight));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int px(float dp) { return Math.round(dp * density); }
}
