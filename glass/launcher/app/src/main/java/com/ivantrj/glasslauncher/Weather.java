package com.ivantrj.glasslauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Small always-on weather: "☀ 18°C | 64°F". Open-Meteo needs no key; the location comes from
 * the connection's public IP (ipapi.co), looked up at most once a day. Cached in prefs so the
 * line shows instantly on start and survives being offline. Network calls run off the main thread.
 */
final class Weather {

    private static final long REFRESH_MS = 30 * 60_000L;
    private static final long LOCATION_MS = 24 * 3_600_000L;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    /** The cached line, or null when there's nothing yet. */
    static String cachedText(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("tempC")) return null;
        float t = p.getFloat("tempC", 0);
        return symbol(p.getInt("code", 0)) + "  " + Math.round(t) + "°C | " + Math.round(t * 9 / 5 + 32) + "°F";
    }

    static boolean due(Context c) {
        return System.currentTimeMillis() - prefs(c).getLong("fetchedAt", 0) > REFRESH_MS;
    }

    /** Blocking fetch; returns true if fresh data was stored. Call from a background thread. */
    static boolean fetch(Context c) {
        SharedPreferences p = prefs(c);
        try {
            long now = System.currentTimeMillis();
            if (!p.contains("lat") || now - p.getLong("locatedAt", 0) > LOCATION_MS) {
                JSONObject loc = new JSONObject(get("https://ipapi.co/json/"));
                p.edit().putFloat("lat", (float) loc.getDouble("latitude"))
                        .putFloat("lon", (float) loc.getDouble("longitude"))
                        .putString("country", loc.optString("country_code", "US"))
                        .putLong("locatedAt", now).apply();
            }
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current_weather=true",
                    p.getFloat("lat", 0), p.getFloat("lon", 0));
            JSONObject cur = new JSONObject(get(url)).getJSONObject("current_weather");
            p.edit().putFloat("tempC", (float) cur.getDouble("temperature"))
                    .putInt("code", cur.getInt("weathercode"))
                    .putLong("fetchedAt", now).apply();
            return true;
        } catch (Exception e) {
            return false; // offline or service down: keep showing the last reading
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        con.setRequestProperty("User-Agent", "Glass-launcher");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            con.disconnect();
        }
    }

    /** WMO weather code -> one quiet text symbol (U+FE0E asks for the plain, non-emoji form). */
    private static String symbol(int code) {
        String s;
        if (code == 0) s = "☀";                                  // clear
        else if (code <= 3) s = "⛅";                             // partly cloudy
        else if (code == 45 || code == 48) s = "☁";              // fog
        else if (code >= 71 && code <= 77 || code == 85 || code == 86) s = "❄"; // snow
        else if (code >= 95) s = "⚡";                            // thunder
        else if (code >= 51) s = "☂";                            // drizzle / rain / showers
        else s = "☁";
        return s + "︎";
    }

    private Weather() {}
}
