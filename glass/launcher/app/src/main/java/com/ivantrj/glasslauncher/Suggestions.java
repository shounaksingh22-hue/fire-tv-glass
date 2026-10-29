package com.ivantrj.glasslauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "For you" cards: well-rated, popular movies and shows on the streaming apps installed here,
 * from TMDB (key in files/tmdb.key, pushed by the kit — never in the source). Fetched at most
 * every 12 h off the main thread; cards are pre-rendered to files/suggest so home loads instantly.
 * TMDB has no deep links into Netflix/Prime/etc., so a card opens its app.
 */
final class Suggestions {

    private static final long REFRESH_MS = 12 * 3_600_000L;
    private static final int[] PROVIDER = {8, 9, 350, 1899};
    private static final String[] PACKAGE = {"com.netflix.ninja", "com.amazon.firebat",
            "com.apple.atve.amazon.appletv", "com.hbo.hbonow"};
    private static final String[] NAME = {"NETFLIX", "PRIME VIDEO", "APPLE TV+", "MAX"};
    private static final int PER_KIND = 1; // one movie + one show per service -> 8 cards for 4 services

    private static File dir(Context c) { return new File(c.getFilesDir(), "suggest"); }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("suggest", Context.MODE_PRIVATE);
    }

    static boolean due(Context c) {
        return new File(c.getFilesDir(), "tmdb.key").isFile()
                && System.currentTimeMillis() - prefs(c).getLong("fetchedAt", 0) > REFRESH_MS;
    }

    /** Cards ready to show, in order. Fast: reads the pre-rendered files only. */
    static List<AppInfo> load(Context c) {
        List<AppInfo> out = new ArrayList<>();
        File list = new File(dir(c), "list.txt");
        if (!list.isFile()) return out;
        PackageManager pm = c.getPackageManager();
        try (BufferedReader r = new BufferedReader(new FileReader(list))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\\|", -1);
                if (f.length < 4) continue;
                Intent launch = pm.getLeanbackLaunchIntentForPackage(f[2]);
                if (launch == null) launch = pm.getLaunchIntentForPackage(f[2]);
                if (launch == null) continue;
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeFile(new File(dir(c), f[0] + ".jpg").getPath(), o);
                if (bmp == null) continue;
                Drawable banner = new BitmapDrawable(c.getResources(), bmp);
                out.add(new AppInfo("tmdb:" + f[3], f[1], banner, null, AppLoader.sampleColor(banner), launch));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static boolean isSuggestion(AppInfo app) { return app.packageName.startsWith("tmdb:"); }

    /** Blocking refresh; true if new cards were written. Call from a background thread. */
    static boolean fetch(Context c) {
        try {
            String key = readFirstLine(new File(c.getFilesDir(), "tmdb.key"));
            if (key == null || key.isEmpty()) return false;
            String region = c.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("country", "US");
            PackageManager pm = c.getPackageManager();
            List<List<String[]>> perService = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (int s = 0; s < PROVIDER.length; s++) {
                if (pm.getLaunchIntentForPackage(PACKAGE[s]) == null && pm.getLeanbackLaunchIntentForPackage(PACKAGE[s]) == null) continue;
                List<String[]> picks = new ArrayList<>();
                for (String kind : new String[] {"movie", "tv"}) {
                    String url = String.format(Locale.US, "https://api.themoviedb.org/3/discover/%s?api_key=%s&watch_region=%s"
                            + "&with_watch_providers=%d&with_watch_monetization_types=flatrate&sort_by=popularity.desc"
                            + "&vote_count.gte=400&vote_average.gte=6.5", kind, key, region, PROVIDER[s]);
                    JSONArray results = new JSONObject(get(url)).getJSONArray("results");
                    int taken = 0;
                    for (int i = 0; i < results.length() && taken < PER_KIND; i++) {
                        JSONObject r = results.getJSONObject(i);
                        int id = r.getInt("id");
                        String backdrop = r.optString("backdrop_path", "");
                        if (backdrop.isEmpty() || backdrop.equals("null") || !seen.add(id)) continue;
                        String title = r.optString("title", r.optString("name", "")).replace("|", " ");
                        picks.add(new String[] {title, PACKAGE[s], kind + id, backdrop, NAME[s]});
                        taken++;
                    }
                }
                perService.add(picks);
            }
            // Alternate services so one app never fills a whole row
            List<String[]> cards = new ArrayList<>();
            for (int round = 0; round < 2 * PER_KIND; round++) {
                for (List<String[]> p : perService) if (round < p.size()) cards.add(p.get(round));
            }
            if (cards.isEmpty()) return false;

            File d = dir(c);
            File tmp = new File(c.getFilesDir(), "suggest.tmp");
            deleteTree(tmp);
            if (!tmp.mkdirs()) return false;
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < cards.size(); i++) {
                String[] card = cards.get(i);
                Bitmap src = download("https://image.tmdb.org/t/p/w780" + card[3]);
                if (src == null) continue;
                Bitmap out = render(c, src, card[4]);
                src.recycle();
                try (FileOutputStream fo = new FileOutputStream(new File(tmp, i + ".jpg"))) {
                    out.compress(Bitmap.CompressFormat.JPEG, 88, fo);
                }
                out.recycle();
                list.append(i).append('|').append(card[0]).append('|').append(card[1]).append('|').append(card[2]).append('\n');
            }
            try (FileWriter w = new FileWriter(new File(tmp, "list.txt"))) { w.write(list.toString()); }
            deleteTree(d);
            if (!tmp.renameTo(d)) return false;
            prefs(c).edit().putLong("fetchedAt", System.currentTimeMillis()).apply();
            return true;
        } catch (Exception e) {
            return false; // offline / TMDB down: keep yesterday's cards
        }
    }

    /** 640x360 card: the still, a soft bottom shade, and the service name small in the corner. */
    private static Bitmap render(Context c, Bitmap src, String service) {
        int w = 640, h = 360;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(Bitmap.createScaledBitmap(src, w, h, true), 0, 0, p);
        Paint shade = new Paint();
        shade.setShader(new LinearGradient(0, h * 0.55f, 0, h, 0x00000000, 0xA6000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, h * 0.55f, w, h, shade);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(Fonts.outfit(c, 600));
        text.setTextSize(22);
        text.setLetterSpacing(0.12f);
        text.setColor(0xE6FFFFFF);
        canvas.drawText(service, 28, h - 26, text);
        return out;
    }

    private static Bitmap download(String url) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(10000);
            try (InputStream in = con.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                con.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            con.disconnect();
        }
    }

    private static String readFirstLine(File f) {
        if (!f.isFile()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s = r.readLine();
            return s == null ? null : s.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private Suggestions() {}
}
