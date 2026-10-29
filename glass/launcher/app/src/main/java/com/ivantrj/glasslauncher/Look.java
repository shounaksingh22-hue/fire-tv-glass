package com.ivantrj.glasslauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Everything the Customize panel can change. Each option is a short list of named
 * choices; the chosen index is kept in SharedPreferences ("look") and read by HomeActivity.
 */
final class Look {

    static final class Option {
        final String key, title;
        final String[] choices;
        final int fallback;

        Option(String key, String title, int fallback, String... choices) {
            this.key = key;
            this.title = title;
            this.fallback = fallback;
            this.choices = choices;
        }
    }

    static final Option CLOCK_FORMAT = new Option("clockFormat", "Clock", 0, "12-hour", "24-hour");
    static final Option CLOCK_SIZE = new Option("clockSize", "Clock size", 0, "Large", "Medium", "Small", "Hidden");
    static final Option CLOCK_WEIGHT = new Option("clockWeight", "Clock style", 0, "Thin", "Light", "Regular", "Bold");
    static final Option DATE = new Option("date", "Date", 0, "Show", "Hide");
    static final Option GREETING = new Option("greeting", "Greeting", 0, "Show", "Hide");
    static final Option WEATHER = new Option("weather", "Weather", 0, "Show", "Hide");
    static final Option SUGGEST = new Option("suggest", "Suggestions (TMDB)", 0, "Show", "Hide");
    static final Option DIM = new Option("dim", "Wallpaper dimming", 1, "Light", "Medium", "Strong");
    static final Option GLOW = new Option("glow", "Ambient glow", 0, "On", "Off");
    static final Option LABELS = new Option("labels", "App names", 1, "On focus", "Always");
    static final Option ARRANGE = new Option("arrange", "Arrange apps", 0, "Most used", "A\u2013Z");
    /** Recent + For you: four recently used apps, then the TMDB row; the rest live in the left dock. */
    static final Option TILES = new Option("tiles", "Home rows", 0, "Recent + For you", "All apps");
    static final Option ROTATE = new Option("rotate", "Change wallpaper", 2, "Off", "Every hour", "Nightly at 12 AM");
    static final Option BEDTIME = new Option("bedtime", "Bedtime lock", 0, "Off", "10 PM \u2013 6 AM", "11 PM \u2013 6 AM", "12 AM \u2013 7 AM");
    /** Shown only where the Paintings screensaver is set up (PaintingsDream.available). */
    static final Option SCREENSAVER = new Option("screensaver", "Screensaver", 0, "Aerial Views", "Paintings");
    static final Option PAINT_TIME = new Option("paintTime", "Change painting", 1,
            "Every 10 min", "Every 30 min", "Every hour", "Every 3 hours", "Once a day");
    static final long[] PAINT_MS = {10 * 60_000L, 30 * 60_000L, 60 * 60_000L, 3 * 60 * 60_000L, 24 * 60 * 60_000L};
    static final int[][] BEDTIME_HOURS = {null, {22, 6}, {23, 6}, {0, 7}};

    static final int[] CLOCK_SP = {100, 76, 56, 0};
    static final int[] CLOCK_WEIGHTS = {120, 250, 400, 600};
    static final int[] DIM_ALPHA = {70, 115, 165};

    /** Fixed options; the wallpaper option is built per call from the files on disk. */
    static final List<Option> FIXED = Arrays.asList(TILES, ROTATE, DIM, CLOCK_FORMAT, CLOCK_SIZE, CLOCK_WEIGHT, DATE, GREETING, WEATHER, GLOW, LABELS, ARRANGE, SUGGEST, BEDTIME);

    static final String WALLPAPER_KEY = "wallpaperName";
    static final String NO_WALLPAPER = "None";

    private final SharedPreferences prefs;

    Look(Context context) {
        prefs = context.getSharedPreferences("look", Context.MODE_PRIVATE);
    }

    int get(Option o) {
        int i = prefs.getInt(o.key, o.fallback);
        return i >= 0 && i < o.choices.length ? i : o.fallback;
    }

    void set(Option o, int i) { prefs.edit().putInt(o.key, i).apply(); }

    // ---------------------------------------------------------------- wallpapers

    /** JPG/PNG files in files/wallpapers, sorted, plus "None". Names are shown without extension. */
    static List<String> wallpaperNames(Context context) {
        List<String> names = new ArrayList<>();
        File[] files = new File(context.getFilesDir(), "wallpapers").listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                String n = f.getName();
                if (n.matches("(?i).+\\.(jpe?g|png)")) names.add(n.substring(0, n.lastIndexOf('.')));
            }
        }
        names.add(NO_WALLPAPER);
        return names;
    }

    String wallpaperName(Context context) {
        List<String> names = wallpaperNames(context);
        String chosen = prefs.getString(WALLPAPER_KEY, null);
        return chosen != null && names.contains(chosen) ? chosen : names.get(0);
    }

    /** Caption for the current wallpaper from files/wallpapers/captions.txt ("Name|Caption" lines), or null. */
    String caption(Context context) {
        String name = wallpaperName(context);
        File f = new File(new File(context.getFilesDir(), "wallpapers"), "captions.txt");
        if (!f.isFile()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                int bar = line.indexOf('|');
                if (bar > 0 && line.substring(0, bar).equals(name)) return line.substring(bar + 1).trim();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** Also marks the photo as shown, so rotation won't bring it back until every other one has had a turn. */
    void setWallpaperName(String name) {
        setWallpaperName(name, true);
    }

    /** shown false while browsing in Customize: passing a photo there shouldn't use up its turn. */
    void setWallpaperName(String name, boolean shown) {
        SharedPreferences.Editor e = prefs.edit().putString(WALLPAPER_KEY, name).putLong("rotatedAt", System.currentTimeMillis());
        if (shown) e.putStringSet("shown", withShown(name));
        e.apply();
    }

    /** The photo left on screen when Customize closes has had its turn. */
    void markShown(String name) {
        prefs.edit().putStringSet("shown", withShown(name)).apply();
    }

    private Set<String> withShown(String name) {
        Set<String> shown = new HashSet<>(prefs.getStringSet("shown", Collections.emptySet()));
        shown.add(name);
        return shown;
    }

    /**
     * Change wallpaper: hourly, or at the first minute past midnight (the clock tick and
     * onStart both call this, so a TV that was off catches up when Home next shows).
     * Returns true if it changed.
     */
    boolean rotateIfDue(Context context) {
        int mode = get(ROTATE);
        if (mode == 0) return false;
        long now = System.currentTimeMillis();
        long last = prefs.getLong("rotatedAt", 0);
        if (last == 0) { prefs.edit().putLong("rotatedAt", now).apply(); return false; }
        if (mode == 1 ? now - last < 3_600_000L : last >= midnight(now)) return false;
        String next = nextWallpaper(context, last);
        if (next == null) return false;
        setWallpaperName(next);
        return true;
    }

    /**
     * No repeats: a photo added since the last change (tonight's Picture of the Day) goes
     * first, otherwise a random one not yet shown. Once all have been shown, a new round starts.
     */
    private String nextWallpaper(Context context, long lastChange) {
        List<String> names = wallpaperNames(context);
        names.remove(NO_WALLPAPER);
        String current = wallpaperName(context);
        names.remove(current);
        if (names.isEmpty()) return null;
        Set<String> shown = prefs.getStringSet("shown", Collections.emptySet());
        List<String> fresh = new ArrayList<>();
        for (String n : names) if (!shown.contains(n)) fresh.add(n);
        if (fresh.isEmpty()) { // new round: forget everything but the photo on screen now
            prefs.edit().putStringSet("shown", new HashSet<>(Collections.singleton(current))).apply();
            fresh = names;
        }
        File dir = new File(context.getFilesDir(), "wallpapers");
        String newest = null;
        long newestAt = lastChange;
        for (String n : fresh) {
            long at = Math.max(new File(dir, n + ".jpg").lastModified(), new File(dir, n + ".png").lastModified());
            if (at > newestAt) { newest = n; newestAt = at; }
        }
        return newest != null ? newest : fresh.get(new Random().nextInt(fresh.size()));
    }

    static long midnight(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** The chosen wallpaper file, or null for none. Falls back to the legacy files/wallpaper.jpg. */
    File wallpaperFile(Context context) {
        String name = wallpaperName(context);
        if (!NO_WALLPAPER.equals(name)) {
            for (String ext : new String[] {".jpg", ".jpeg", ".png"}) {
                File f = new File(new File(context.getFilesDir(), "wallpapers"), name + ext);
                if (f.isFile()) return f;
            }
        }
        if (prefs.contains(WALLPAPER_KEY)) return null; // "None" chosen explicitly
        for (String legacy : new String[] {"wallpaper.jpg", "wallpaper.png"}) {
            File f = new File(context.getFilesDir(), legacy);
            if (f.isFile()) return f;
        }
        return null;
    }
}
