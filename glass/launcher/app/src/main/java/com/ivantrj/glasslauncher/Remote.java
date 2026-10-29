package com.ivantrj.glasslauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.format.Formatter;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The iPhone remote, served by the TV itself (no computer needed): a small web app at
 * http://<tv-ip>:8765 that the phone adds to its Home Screen. A phone pairs once with a
 * 4-digit code shown on the TV and keeps a token; every other request needs that token.
 * Key presses go to the Injector (started through the TV's own ADB, see AdbClient).
 * Runs inside HomeButtonService, which is always on.
 */
final class Remote {

    static final int PORT = 8765;
    private static final String GLASS = "com.ivantrj.glasslauncher";

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final SecureRandom random = new SecureRandom();
    private ServerSocket server;
    private volatile boolean running;

    private String pin;
    private long pinUntil;

    private Socket injector;
    private Writer injectorOut;
    private BufferedReader injectorIn;

    Remote(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE);
    }

    void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                server = s;
                while (running) {
                    Socket c = s.accept();
                    pool.execute(() -> handle(c));
                }
            } catch (IOException ignored) { // stopped, or the port is taken
            }
        }, "glass-remote");
        t.start();
        pool.execute(this::ensureInjector); // ready (and the one-time TV prompt shown) before the first press
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        closeInjector();
        pool.shutdownNow();
    }

    /**
     * Where phones reach this TV, or null when it has no network. The Tailscale address
     * (100.64.0.0/10) when Tailscale runs: it works from any network, even Wi-Fi that keeps
     * devices apart. Otherwise the Wi-Fi address, which works on the same Wi-Fi only.
     */
    static String address(Context context) {
        String ip = tailscaleIp();
        if (ip == null) ip = ip(context);
        return ip == null ? null : "http://" + ip + ":" + PORT;
    }

    private static String tailscaleIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    byte[] b = a.getAddress();
                    if (b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) return a.getHostAddress();
                }
            }
        } catch (SocketException ignored) {
        }
        return null;
    }

    private static String ip(Context context) {
        WifiManager wm = context.getApplicationContext().getSystemService(WifiManager.class);
        int ip = wm == null ? 0 : wm.getConnectionInfo().getIpAddress();
        return ip == 0 ? null : Formatter.formatIpAddress(ip);
    }

    // ---------------------------------------------------------------- http

    private void handle(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(10_000);
            InputStream in = sock.getInputStream();
            String requestLine = line(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            Uri uri = Uri.parse(parts[1]);
            Map<String, String> headers = new HashMap<>();
            String h;
            while ((h = line(in)) != null && !h.isEmpty()) {
                int colon = h.indexOf(':');
                if (colon > 0) headers.put(h.substring(0, colon).trim().toLowerCase(Locale.US), h.substring(colon + 1).trim());
            }
            int length = 0;
            try {
                length = Math.min(65536, Integer.parseInt(headers.getOrDefault("content-length", "0")));
            } catch (NumberFormatException ignored) {
            }
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n < 0) break;
                read += n;
            }
            route(sock.getOutputStream(), method, uri, headers, new String(body, 0, read, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    /** One CRLF-terminated header line. */
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 8192) throw new IOException("header too long");
        }
        return ch == -1 && b.size() == 0 ? null : b.toString("UTF-8");
    }

    private void route(OutputStream out, String method, Uri uri, Map<String, String> headers, String body) throws IOException {
        String path = uri.getPath() == null ? "/" : uri.getPath();
        // The web app itself and pairing are open; everything else needs a paired phone's token
        switch (path) {
            case "/":
                asset(out, "remote/index.html", "text/html; charset=utf-8");
                return;
            case "/guide":
                asset(out, "remote/guide.html", "text/html; charset=utf-8");
                return;
            case "/manifest.webmanifest":
                asset(out, "remote/manifest.webmanifest", "application/manifest+json");
                return;
            case "/icon.png":
                asset(out, "remote/icon.png", "image/png");
                return;
            case "/pair/start":
                startPairing();
                json(out, 200, "{}");
                return;
            case "/pair":
                pair(out, body.trim());
                return;
        }
        if (!authorised(headers.get("x-glass-token"))) {
            json(out, 401, "{\"error\":\"pair\"}");
            return;
        }
        switch (path) {
            case "/ping":
                json(out, 200, "{\"ok\":true}");
                return;
            case "/key":
                int code = Integer.parseInt(uri.getQueryParameter("c"));
                // Sleep is a toggle, like the power button; any other press on a sleeping TV or
                // over the screensaver just wakes it, like a real remote
                if (code == KeyEvent.KEYCODE_SLEEP ? !interactive() : wakeFirst()) {
                    if (code == KeyEvent.KEYCODE_SLEEP) wake();
                    json(out, 200, "{}");
                    return;
                }
                // Glass in front, or Home: handled directly (instant, and Home must open Glass).
                // Sleep always goes to the system: Glass can't turn the screen off itself.
                if (code == 3 || (HomeButtonService.glassResumed && code != KeyEvent.KEYCODE_SLEEP) || !injectorSend("k " + code)) {
                    main.post(() -> HomeButtonService.remoteKey(code));
                }
                json(out, 200, "{}");
                return;
            case "/text":
                if (!body.isEmpty() && !wakeFirst() && !injectorSend("t " + body.replace('\n', ' '))) {
                    String text = body.replace('\n', ' ');
                    main.post(() -> HomeButtonService.remoteText(text));
                }
                json(out, 200, "{}");
                return;
            case "/apps":
                json(out, 200, apps());
                return;
            case "/icon":
                icon(out, uri.getQueryParameter("pkg"));
                return;
            case "/launch":
                if (!wakeFirst()) launch(uri.getQueryParameter("pkg"));
                json(out, 200, "{}");
                return;
            case "/do":
                if (!wakeFirst()) action(uri.getQueryParameter("a"));
                json(out, 200, "{}");
                return;
            default:
                json(out, 404, "{}");
        }
    }

    private void asset(OutputStream out, String name, String type) throws IOException {
        try (InputStream a = context.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = a.read(buf)) > 0) b.write(buf, 0, n);
            respond(out, 200, type, b.toByteArray());
        }
    }

    private static void json(OutputStream out, int status, String text) throws IOException {
        respond(out, status, "application/json", text.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + " OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    // ---------------------------------------------------------------- pairing

    /** A fresh code on the TV for two minutes. */
    private void startPairing() {
        pin = String.format(Locale.US, "%04d", random.nextInt(10000));
        pinUntil = System.currentTimeMillis() + 120_000;
        String code = pin;
        main.post(() -> HomeButtonService.showRemoteCode(code));
    }

    private void pair(OutputStream out, String code) throws IOException {
        if (pin == null || System.currentTimeMillis() > pinUntil || !pin.equals(code)) {
            json(out, 403, "{\"error\":\"code\"}");
            return;
        }
        pin = null;
        byte[] t = new byte[16];
        random.nextBytes(t);
        StringBuilder token = new StringBuilder();
        for (byte b : t) token.append(String.format(Locale.US, "%02x", b));
        Set<String> tokens = new HashSet<>(prefs.getStringSet("tokens", new HashSet<>()));
        tokens.add(token.toString());
        prefs.edit().putStringSet("tokens", tokens).apply();
        main.post(HomeButtonService::hideRemoteCode);
        json(out, 200, "{\"token\":\"" + token + "\"}");
    }

    private boolean authorised(String token) {
        return token != null && prefs.getStringSet("tokens", new HashSet<>()).contains(token);
    }

    // ---------------------------------------------------------------- waking

    private boolean interactive() {
        return context.getSystemService(PowerManager.class).isInteractive();
    }

    /** If the TV is asleep or showing the screensaver: wakes it and returns true (the press is used up). */
    private boolean wakeFirst() {
        if (interactive() && !HomeButtonService.dreaming) return false;
        wake();
        return true;
    }

    private void wake() {
        if (!injectorSend("k " + KeyEvent.KEYCODE_WAKEUP)) main.post(() -> HomeButtonService.remoteKey(KeyEvent.KEYCODE_WAKEUP));
    }

    // ---------------------------------------------------------------- injector

    /** Set once the TV refuses ADB from apps (Fire OS does): don't retry until the next boot. */
    private boolean adbBlocked;

    /**
     * Connected to the Injector. If it isn't running, tries to start it through the TV's own
     * ADB, which works on TVs that allow it; Fire OS doesn't, so there the computer starts it
     * (tv-kit setup.sh / maintain.sh) and the remote falls back to HomeButtonService until then.
     */
    private synchronized boolean ensureInjector() {
        if (injectorOut != null && ping()) return true;
        closeInjector();
        if (connectInjector() && ping()) return true;
        closeInjector();
        if (adbBlocked) return false;
        try {
            String apk = context.getApplicationInfo().sourceDir;
            String host = ip(context);
            AdbClient.shell(context, host != null ? host : "127.0.0.1", "pkill -f " + GLASS + ".Injector; CLASSPATH=" + apk
                    + " setsid app_process / " + GLASS + ".Injector " + secret() + " >/dev/null 2>&1 </dev/null &", 60_000);
        } catch (IOException e) {
            Log.w("GlassRemote", "couldn't start the key helper through ADB: " + e);
            adbBlocked = true; // debugging off, blocked for apps, or the TV's prompt was declined
            return false;
        }
        for (int i = 0; i < 20; i++) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return false;
            }
            if (connectInjector() && ping()) return true;
        }
        return false;
    }

    private boolean connectInjector() {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", Injector.PORT), 500);
            s.setSoTimeout(2000);
            s.setTcpNoDelay(true);
            injector = s;
            injectorOut = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
            injectorIn = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            injectorOut.write(secret() + "\n");
            injectorOut.flush();
            return true;
        } catch (IOException e) {
            closeInjector();
            return false;
        }
    }

    private boolean ping() {
        try {
            injectorOut.write("p\n");
            injectorOut.flush();
            return "ok".equals(injectorIn.readLine());
        } catch (IOException | NullPointerException e) {
            return false;
        }
    }

    private synchronized boolean injectorSend(String command) {
        // Check the helper is still there (a local round trip, ~1 ms): writing to a helper that
        // has been restarted or killed still "succeeds" once, which lost that press
        if (injectorOut != null && !ping()) closeInjector();
        for (int attempt = 0; attempt < 2; attempt++) {
            if (injectorOut == null && !ensureInjector()) return false;
            try {
                injectorOut.write(command + "\n");
                injectorOut.flush();
                return true;
            } catch (IOException e) {
                closeInjector(); // TV rebooted the helper away: start it again once
            }
        }
        return false;
    }

    private synchronized void closeInjector() {
        try {
            if (injector != null) injector.close();
        } catch (IOException ignored) {
        }
        injector = null;
        injectorOut = null;
        injectorIn = null;
    }

    /** Shared with the Injector at launch; kept so a running Injector still accepts Glass after it restarts. */
    private String secret() {
        String s = prefs.getString("secret", null);
        if (s == null) {
            byte[] b = new byte[16];
            random.nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
            s = sb.toString();
            prefs.edit().putString("secret", s).apply();
        }
        return s;
    }

    // ---------------------------------------------------------------- apps and actions

    private String apps() {
        AppLoader.Result r = AppLoader.load(context);
        JSONArray a = new JSONArray();
        try {
            for (List<AppInfo> list : java.util.Arrays.asList(r.dock, r.grid)) {
                for (AppInfo app : list) a.put(new JSONObject().put("pkg", app.packageName).put("label", app.label));
            }
        } catch (JSONException ignored) {
        }
        return a.toString();
    }

    private void icon(OutputStream out, String pkg) throws IOException {
        for (AppInfo app : appList()) {
            if (app.packageName.equals(pkg)) {
                Bitmap b = AppDrawer.iosIcon(context, app);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.PNG, 100, png);
                respond(out, 200, "image/png", png.toByteArray());
                return;
            }
        }
        json(out, 404, "{}");
    }

    private List<AppInfo> appList() {
        AppLoader.Result r = AppLoader.load(context);
        List<AppInfo> all = new java.util.ArrayList<>(r.dock);
        all.addAll(r.grid);
        return all;
    }

    private void launch(String pkg) {
        for (AppInfo app : appList()) {
            if (app.packageName.equals(pkg)) {
                Usage.record(context, pkg);
                main.post(() -> {
                    try {
                        context.startActivity(new Intent(app.launchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException ignored) {
                    }
                });
                return;
            }
        }
    }

    private void action(String a) {
        if (a == null) return;
        switch (a) {
            case "home":
                main.post(HomeButtonService::goHome);
                break;
            case "lock":
                main.post(() -> HomeButtonService.lockUntil(TvLock.FOREVER));
                break;
            case "focus":
                main.post(() -> HomeButtonService.startTimer(25, "Focus"));
                break;
            case "break":
                main.post(() -> HomeButtonService.startTimer(5, "Break"));
                break;
            case "stoptimer":
                main.post(HomeButtonService::stopTimer);
                break;
            case "free":
                Memory.freeBackground(context);
                break;
            case "paintings":
                main.post(() -> PaintingsActivity.open(context));
                break;
        }
    }
}
