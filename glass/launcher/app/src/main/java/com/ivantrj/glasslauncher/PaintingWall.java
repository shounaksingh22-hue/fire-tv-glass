package com.ivantrj.glasslauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The framed painting that fills the whole screen, shared by the Paintings screensaver and the
 * Paintings view (Tools): a thick wooden frame along the screen's edges and one public-domain
 * painting (files/paintings, pushed by tv-kit) filling everything inside it. Wide paintings only,
 * so filling trims just a little off the top and bottom. Each is dimmed to the same gentle
 * brightness whatever its colours, and stays for the time picked in Customize, counted across
 * sessions. Against burn-in it also drifts a few pixels, too slowly to see. Drawn into a surface
 * at the panel's own resolution (4K on the living room TV): Fire OS draws app windows at 1080p
 * and stretches them, which looks soft on a big screen.
 */
final class PaintingWall {

    /** Extra size for the drift: under 1% of the screen, crossed over tens of minutes. */
    private static final float DRIFT = 0.008f;
    /** Dark walnut, a tone per side for light from the top left; muted gold fillet. */
    private static final int FRAME_LIT = 0xFF3A2B1F, FRAME_SIDE_LIT = 0xFF35271C, FRAME_SIDE_DARK = 0xFF2C2118, FRAME_DARK = 0xFF291F16;
    private static final int FILLET = 0xFF8A7448, FILLET_LIGHT = 0xFFB09660;
    private static final long DRIFT_X_MS = 47 * 60_000L, DRIFT_Y_MS = 31 * 60_000L, DRIFT_STEP_MS = 20_000;
    private static final long FADE_MS = 4000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SurfaceHolder surface;
    private int width, height;
    private Bitmap showing, incoming;
    private long fadeStart;
    private SharedPreferences prefs;

    private final Context context;

    PaintingWall(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("paintings", Context.MODE_PRIVATE);
    }

    /** Puts the wall in this view; call from the host's window being attached. */
    SurfaceView view(Display display) {
        Display.Mode mode = display.getMode();
        width = mode.getPhysicalWidth();
        height = mode.getPhysicalHeight();
        SurfaceView stage = new SurfaceView(context);
        stage.getHolder().setFixedSize(width, height);
        stage.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                surface = h;
                show(pick(false));
                main.postDelayed(drift, DRIFT_STEP_MS);
            }
            @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int ht) { draw(); }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                surface = null;
                main.removeCallbacksAndMessages(null);
            }
        });
        return stage;
    }

    /** The host is going away: stop, and free the big bitmaps. */
    void release() {
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        surface = null;
        if (showing != null) showing.recycle();
        if (incoming != null) incoming.recycle();
        showing = incoming = null;
    }

    private long interval() {
        return Look.PAINT_MS[new Look(context).get(Look.PAINT_TIME)];
    }

    /** The current painting while its time lasts, else a random unseen one: no repeats until all were shown. */
    private String pick(boolean next) {
        List<String> all = PaintingsDream.names(context);
        if (all.isEmpty()) return null;
        String current = prefs.getString("current", null);
        long since = prefs.getLong("since", 0);
        long now = System.currentTimeMillis();
        if (!next && current != null && all.contains(current) && now - since < interval()) {
            schedule(interval() - (now - since));
            return current;
        }
        Set<String> shown = new HashSet<>(prefs.getStringSet("shown", new HashSet<>()));
        List<String> fresh = new ArrayList<>(all);
        fresh.removeAll(shown);
        fresh.remove(current);
        if (fresh.isEmpty()) {
            shown.clear();
            fresh = new ArrayList<>(all);
            fresh.remove(current);
            if (fresh.isEmpty()) fresh = all;
        }
        String pick = fresh.get(new Random().nextInt(fresh.size()));
        shown.add(pick);
        prefs.edit().putString("current", pick).putLong("since", now).putStringSet("shown", shown).apply();
        schedule(interval());
        return pick;
    }

    private void schedule(long delay) {
        main.removeCallbacks(next);
        main.postDelayed(next, delay);
    }

    private final Runnable next = () -> show(pick(true));

    private final Runnable drift = new Runnable() {
        @Override public void run() {
            if (incoming == null) draw(); // a running fade draws anyway
            main.postDelayed(this, DRIFT_STEP_MS);
        }
    };

    /** Paintings are prepared off the main thread: a 4K wall takes a second or two, and a wake-up press must not wait. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private void show(String name) {
        if (name == null || surface == null) return;
        File f = new File(PaintingsDream.dir(context), name);
        worker.execute(() -> {
            Bitmap scene = compose(f);
            if (scene != null) main.post(() -> fadeIn(scene));
        });
    }

    private void fadeIn(Bitmap scene) {
        if (surface == null) { // the screensaver ended while it was being prepared
            scene.recycle();
            return;
        }
        if (incoming != null) { // a fade still running: finish it at once
            if (showing != null) showing.recycle();
            showing = incoming;
        }
        incoming = scene;
        fadeStart = SystemClock.uptimeMillis();
        main.removeCallbacks(fade);
        fade.run();
    }

    /** Cross-fades the new painting in over the old one, then keeps only the new one. */
    private final Runnable fade = new Runnable() {
        @Override public void run() {
            if (incoming == null) return;
            if (SystemClock.uptimeMillis() - fadeStart >= FADE_MS) {
                if (showing != null) showing.recycle();
                showing = incoming;
                incoming = null;
                draw();
                return;
            }
            draw();
            main.postDelayed(this, 33);
        }
    };

    private void draw() {
        SurfaceHolder h = surface;
        if (h == null) return;
        Canvas c;
        try {
            c = h.lockHardwareCanvas();
        } catch (IllegalStateException e) {
            return;
        }
        if (c == null) return;
        try {
            // Where the drift is now: two slow waves, so the path doesn't repeat for hours
            long t = System.currentTimeMillis();
            float mx = width * DRIFT, my = height * DRIFT;
            float x = -mx * 0.5f * (1 + (float) Math.sin(2 * Math.PI * t / DRIFT_X_MS));
            float y = -my * 0.5f * (1 + (float) Math.sin(2 * Math.PI * t / DRIFT_Y_MS + 1.3));
            c.drawColor(0xFF000000);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            if (showing != null) c.drawBitmap(showing, x, y, p);
            if (incoming != null) {
                float k = Math.min(1f, (SystemClock.uptimeMillis() - fadeStart) / (float) FADE_MS);
                p.setAlpha(Math.round(255 * k * k * (3 - 2 * k))); // ease in and out
                c.drawBitmap(incoming, x, y, p);
            }
        } finally {
            h.unlockCanvasAndPost(c);
        }
    }

    /**
     * The whole screen as a framed painting: a thick wooden frame along the screen's edges, lit
     * from the top left, a muted gold fillet, and the painting filling everything inside it.
     * Built a little larger than the screen, for the drift.
     */
    private Bitmap compose(File f) {
        int sw = Math.round(width * (1 + DRIFT)), sh = Math.round(height * (1 + DRIFT));
        float margin = height * DRIFT / 2; // the part of the frame that drifts off screen
        Bitmap out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        float frame = height * 0.075f + margin, fillet = height * 0.006f;
        RectF inner = new RectF(frame, frame, sw - frame, sh - frame);
        // The painting fills everything inside the frame: scaled to cover it, centred, its
        // edges trimmed (wide paintings only, so that's a little off the top and bottom).
        // Only that part is decoded, which keeps memory down on a 1.7 GB TV.
        Bitmap art = decodeCover(f, inner.width() / inner.height());
        if (art == null) {
            out.recycle();
            return null;
        }
        p.setColorFilter(tone(art));
        c.drawBitmap(art, null, inner, p);
        p.setColorFilter(null);
        art.recycle();

        // The frame's shadow on the painting, deeper along the top and left (light from the top left)
        float shade = height * 0.03f;
        p.setShader(new LinearGradient(0, inner.top, 0, inner.top + shade, 0x99000000, 0, Shader.TileMode.CLAMP));
        c.drawRect(inner.left, inner.top, inner.right, inner.top + shade, p);
        p.setShader(new LinearGradient(inner.left, 0, inner.left + shade * 0.8f, 0, 0x80000000, 0, Shader.TileMode.CLAMP));
        c.drawRect(inner.left, inner.top, inner.left + shade * 0.8f, inner.bottom, p);
        p.setShader(new LinearGradient(0, inner.bottom, 0, inner.bottom - shade * 0.35f, 0x40000000, 0, Shader.TileMode.CLAMP));
        c.drawRect(inner.left, inner.bottom - shade * 0.35f, inner.right, inner.bottom, p);
        p.setShader(null);

        // Four mitred sides, each its own tone, with a rounded moulding across its width
        drawSide(c, p, new float[]{0, 0, sw, 0, inner.right, inner.top, inner.left, inner.top}, 0, FRAME_LIT, frame);
        drawSide(c, p, new float[]{0, 0, inner.left, inner.top, inner.left, inner.bottom, 0, sh}, 1, FRAME_SIDE_LIT, frame);
        drawSide(c, p, new float[]{sw, 0, sw, sh, inner.right, inner.bottom, inner.right, inner.top}, 2, FRAME_SIDE_DARK, frame);
        drawSide(c, p, new float[]{0, sh, inner.left, inner.bottom, inner.right, inner.bottom, sw, sh}, 3, FRAME_DARK, frame);
        // Mitre lines at the corners
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, height * 0.0008f));
        p.setColor(0x66000000);
        c.drawLine(0, 0, inner.left, inner.top, p);
        c.drawLine(sw, 0, inner.right, inner.top, p);
        c.drawLine(0, sh, inner.left, inner.bottom, p);
        c.drawLine(sw, sh, inner.right, inner.bottom, p);
        // Gold fillet where the frame meets the mat
        p.setStrokeWidth(fillet);
        p.setColor(FILLET);
        c.drawRect(inner.left - fillet / 2, inner.top - fillet / 2, inner.right + fillet / 2, inner.bottom + fillet / 2, p);
        p.setStrokeWidth(Math.max(1f, fillet * 0.25f));
        p.setColor(FILLET_LIGHT);
        c.drawLine(inner.left - fillet, inner.top - fillet * 0.75f, inner.right + fillet, inner.top - fillet * 0.75f, p);
        p.setStyle(Paint.Style.FILL);
        return out;
    }

    /**
     * One side of the frame: a trapezoid (outer edge first) in its base tone, shaded across its
     * width like a rounded moulding, with faint wood grain running along it.
     * side: 0 top, 1 left, 2 right, 3 bottom.
     */
    private static void drawSide(Canvas c, Paint p, float[] q, int side, int tone, float depth) {
        Path path = new Path();
        path.moveTo(q[0], q[1]);
        for (int i = 2; i < 8; i += 2) path.lineTo(q[i], q[i + 1]);
        path.close();
        c.save();
        c.clipPath(path);
        float x0, y0, x1, y1; // outer edge to inner edge, across the moulding
        switch (side) {
            case 0: x0 = 0; y0 = 0; x1 = 0; y1 = depth; break;
            case 1: x0 = 0; y0 = 0; x1 = depth; y1 = 0; break;
            case 2: x0 = q[0]; y0 = 0; x1 = q[0] - depth; y1 = 0; break;
            default: x0 = 0; y0 = q[1]; x1 = 0; y1 = q[1] - depth;
        }
        p.setColor(0xFF000000); // full strength: the last side's faint grain colour would carry over
        p.setShader(new LinearGradient(x0, y0, x1, y1,
                new int[]{shade(tone, 0.7f), shade(tone, 1.0f), shade(tone, 1.12f), shade(tone, 0.95f), shade(tone, 0.72f)},
                new float[]{0f, 0.25f, 0.5f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        c.drawPaint(p);
        p.setShader(null);
        // Grain: thin, faint streaks along the side
        Random r = new Random(side * 7919L);
        boolean across = side == 0 || side == 3;
        float len = across ? c.getWidth() : c.getHeight();
        p.setStrokeWidth(1.2f);
        for (int i = 0; i < 260; i++) {
            float at = (side == 0 || side == 1 ? 0 : (side == 2 ? q[0] - depth : q[1] - depth)) + r.nextFloat() * depth;
            float s0 = r.nextFloat() * len, s1 = s0 + len * (0.05f + r.nextFloat() * 0.25f);
            p.setColor(r.nextBoolean() ? 0x12000000 : 0x0CFFFFFF);
            if (across) c.drawLine(s0, at, s1, at + (r.nextFloat() - 0.5f) * 2, p);
            else c.drawLine(at, s0, at + (r.nextFloat() - 0.5f) * 2, s1, p);
        }
        c.restore();
    }

    private static int shade(int color, float k) {
        int r = Math.min(255, Math.round(((color >> 16) & 0xff) * k));
        int g = Math.min(255, Math.round(((color >> 8) & 0xff) * k));
        int b = Math.min(255, Math.round((color & 0xff) * k));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The centred part of the painting with the given shape (width / height), decoded alone. */
    private static Bitmap decodeCover(File f, float shape) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int w = o.outWidth, h = o.outHeight;
        if (w <= 0 || h <= 0) return null;
        int cw = w, ch = h;
        if (w / (float) h > shape) cw = Math.round(h * shape);
        else ch = Math.round(w / shape);
        Rect crop = new Rect((w - cw) / 2, (h - ch) / 2, (w - cw) / 2 + cw, (h - ch) / 2 + ch);
        try {
            BitmapRegionDecoder d = BitmapRegionDecoder.newInstance(f.getPath(), false);
            try {
                return d.decodeRegion(crop, null);
            } finally {
                d.recycle();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** Scaled so its average brightness lands near the same gentle level, colours barely softened. */
    private static ColorMatrixColorFilter tone(Bitmap art) {
        Bitmap s = Bitmap.createScaledBitmap(art, 32, 32, true);
        double sum = 0;
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int px = s.getPixel(x, y);
                sum += (0.2126 * ((px >> 16) & 0xff) + 0.7152 * ((px >> 8) & 0xff) + 0.0722 * (px & 0xff)) / 255.0;
            }
        }
        s.recycle();
        double mean = Math.max(0.05, sum / 1024);
        // Gentle, not murky: only bright paintings are toned down much, and colours stay true
        float k = (float) Math.max(0.62, Math.min(0.9, 0.42 / mean));
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0.96f);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(k, k, k, 1f);
        m.postConcat(dim);
        return new ColorMatrixColorFilter(m);
    }
}
