package com.ivantrj.glasslauncher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.Collator;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen.
 *
 * Focus is handled here, not by Android's focus search, so D-pad movement is
 * deterministic: three zones (top bar, dock, grid), Up/Down move exactly one
 * row, and the ends of a row never fall through to another zone.
 */
public class HomeActivity extends Activity {

    private static final int TOP = 0, DOCK = 1, GRID = 2;
    private static final int COLUMNS = 4;
    private static final int PILL_APPS = 0, PILL_TOOLS = 1, PILL_INPUTS = 2, PILL_WIFI = 3, PILL_LOOK = 4, PILL_SETTINGS = 5;
    /** Content scrolls up to just below the pinned top bar (pills end at 82dp). */
    private static final float TOP_BAR_DP = 100;
    private static final int NEUTRAL_GLOW = 0xFFAFC3FF;

    private View root, page, band, glow, pillRow;
    private View[] pills;
    private GlassHighlight highlight;
    private TextView greeting, dockTitle, wifiName, wallCaption;
    private LinearLayout dockRow, inputsMenu;
    private RecyclerView grid;
    private ImageView wallpaper;
    private GridAdapter adapter;

    private final List<AppInfo> dockApps = new ArrayList<>();
    private List<AppInfo> gridApps = new ArrayList<>();
    /** The left dock (Apps button): every app not already shown as a home tile, most used first. */
    private final List<AppInfo> drawerApps = new ArrayList<>();
    private AppDrawer drawer;
    private SettingsSheet settingsSheet;
    private GuideSheet guideSheet;

    private int zone = DOCK, topIdx = 0, dockIdx = 0, gridIdx = 0;
    private String restoreGridPkg;
    private int gridScrollTarget, gridScrollActual;
    private SpringValue pageSpring;

    private GradientDrawable glowDrawable;
    private int glowColor = NEUTRAL_GLOW, glowTarget = NEUTRAL_GLOW;
    private ValueAnimator glowAnimator;

    private boolean menuOpen;
    private int menuIdx;
    private List<InputsMenu.Entry> menuEntries = new ArrayList<>();

    private Look look;
    private boolean lookOpen;
    private LookDrawer lookDrawer;
    private final List<Look.Option> lookOptions = new ArrayList<>();
    private Look.Option wallpaperOption;

    private boolean stopped = true;
    private boolean usageChanged;
    private long wallpaperStamp = -1;
    private long loadedConfigStamp;
    private float density;
    private SharedPreferences prefs;
    private WifiManager wifiManager;
    private ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final DecelerateInterpolator ease = new DecelerateInterpolator(1.8f);

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("focus", MODE_PRIVATE);
        wifiManager = getApplicationContext().getSystemService(WifiManager.class);
        connectivity = getSystemService(ConnectivityManager.class);

        root = findViewById(R.id.root);
        page = findViewById(R.id.page);
        band = findViewById(R.id.band);
        glow = findViewById(R.id.glow);
        highlight = findViewById(R.id.highlight);
        greeting = findViewById(R.id.greeting);
        dockTitle = findViewById(R.id.dockTitle);
        wallCaption = findViewById(R.id.wallCaption);
        wallCaption.setTypeface(Fonts.outfit(this, 350));
        ((TextView) findViewById(R.id.weather)).setTypeface(Fonts.outfit(this, 300));
        wallCaption.setShadowLayer(dp(6), 0, dp(1), 0x99000000); // readable on bright photos
        wifiName = findViewById(R.id.wifiName);
        dockRow = findViewById(R.id.dockRow);
        grid = findViewById(R.id.grid);
        inputsMenu = findViewById(R.id.inputsMenu);
        look = new Look(this);
        wallpaper = findViewById(R.id.wallpaper);
        pillRow = findViewById(R.id.pills);
        pills = new View[] {findViewById(R.id.pillApps), findViewById(R.id.pillTools), findViewById(R.id.pillInputs), findViewById(R.id.pillWifi),
                findViewById(R.id.pillLook), findViewById(R.id.pillSettings)};

        greeting.setTypeface(Fonts.outfit(this, 300));
        ((TextClock) findViewById(R.id.date)).setTypeface(Fonts.outfit(this, 300));
        dockTitle.setTypeface(Fonts.outfit(this, 250));
        for (View pill : pills) {
            View label = ((LinearLayout) pill).getChildAt(1); // icon-only pills have none
            if (label instanceof TextView) ((TextView) label).setTypeface(Fonts.outfit(this, 400));
        }

        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(480 * density);
        glowDrawable.setGradientCenter(0.5f, 0.5f);
        setGlowColors(glowColor);
        glow.setBackground(glowDrawable);
        glow.setScaleY(0.42f); // squash the circle into a wide ellipse behind the dock

        applyLook();
        drawer = new AppDrawer(this, (FrameLayout) root, app -> {
            Usage.record(this, app.packageName);
            usageChanged = true;
            launch(app.launchIntent);
        }, app -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.packageName, null))));

        settingsSheet = new SettingsSheet(this, (FrameLayout) root, this::launch);
        guideSheet = new GuideSheet(this, (FrameLayout) root);
        lookDrawer = new LookDrawer(this, (FrameLayout) root, lookHost);

        // Clock, date, caption and top bar stay put; only the dock/grid area moves beneath them
        View header = findViewById(R.id.header);
        pageSpring = new SpringValue(240f, 0.92f, v -> {
            page.setTranslationY(-v);
            pillRow.setTranslationY(v);
            pills[PILL_APPS].setTranslationY(v);
            header.setTranslationY(v);
            wallCaption.setTranslationY(v);
        });

        adapter = new GridAdapter(grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setItemAnimator(null);
        grid.setHasFixedSize(true);
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                gridScrollActual += dy;
                fadeRowsAbove();
            }
        });
        // Recycled rows keep their old alpha: re-apply the fade once they're laid out again
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override public void onChildViewAttachedToWindow(View v) { grid.post(HomeActivity.this::fadeRowsAbove); }
            @Override public void onChildViewDetachedFromWindow(View v) { v.setAlpha(1f); }
        });

        // The Wi-Fi pill changes width with the network name: keep the highlight on it
        pillRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (zone == TOP) moveHighlight();
        });

        zone = prefs.getInt("zone", DOCK) == GRID ? GRID : DOCK;
        dockIdx = prefs.getInt("dockIdx", 0);
        topIdx = clamp(prefs.getInt("topIdx", 0), 0, pills.length - 1);
        restoreGridPkg = prefs.getString("gridPkg", null);

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packageReceiver, pkgFilter);

        loadApps();
        handleAction(getIntent());
    }

    /**
     * Remote control for Shortcuts/Siri via the Mac (tvctl):
     *   am start -n com.ivantrj.glasslauncher/.HomeActivity --es action lock --ei minutes 60
     * Actions: lock (minutes; 0 = until unlocked), timer (minutes, label), stoptimer, refresh.
     * There is deliberately no unlock action: unlocking stays the 10-arrow challenge on the TV.
     */
    private boolean handleAction(Intent intent) {
        String action = intent == null ? null : intent.getStringExtra("action");
        if (action == null) return false;
        intent.removeExtra("action"); // don't repeat it on recreation
        int minutes = intent.getIntExtra("minutes", 0);
        switch (action) {
            case "lock":
                lockTv(minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : TvLock.FOREVER);
                break;
            case "timer":
                String label = intent.getStringExtra("label");
                startTimer(minutes > 0 ? minutes : 25, label != null ? label : "Focus");
                break;
            case "stoptimer":
                HomeButtonService.stopTimer();
                break;
            case "refresh":
                main.post(this::refreshAll);
                break;
            case "paintings": // tvctl paintings
                PaintingsActivity.open(this);
                break;
            case "set": // tvctl set <option> <index>, e.g. set clockSize 0
                String key = intent.getStringExtra("key");
                for (Look.Option o : Look.FIXED) {
                    if (o.key.equals(key) && minutes >= 0 && minutes < o.choices.length) look.set(o, minutes);
                }
                applyLook();
                break;
            case "newwallpaper": // tvctl-style: show this photo now
                String name = intent.getStringExtra("name");
                if (name != null && look.get(Look.ROTATE) != 0) {
                    look.setWallpaperName(name);
                    loadWallpaper();
                }
                break;
            default:
                return false;
        }
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_TICK);
        timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, timeFilter);
        connectivity.registerDefaultNetworkCallback(networkCallback);
        updateGreeting();
        updateWeather();
        updateWifi();
        if (look.rotateIfDue(this)) applyLook();
        refreshSuggestions();
        loadWallpaper();
        // dock.txt / hidden.txt edited, or a launch changed the most-used order
        if (configStamp() != loadedConfigStamp || (usageChanged && look.get(Look.ARRANGE) == 0)) loadApps();
        usageChanged = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
        unregisterReceiver(timeReceiver);
        connectivity.unregisterNetworkCallback(networkCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeButtonService.glassResumed = true;
        resumed = this;
    }

    /** Glass on screen: the phone remote's keys go straight to it, as if pressed on the TV remote. */
    private static HomeActivity resumed;

    static boolean remoteKey(int code) {
        HomeActivity a = resumed;
        if (a == null) return false;
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        // These presses don't reset the TV's idle timer: stay awake for 5 min after each one
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        a.main.removeCallbacks(a.allowSleep);
        a.main.postDelayed(a.allowSleep, 5 * 60_000);
        return true;
    }

    private final Runnable allowSleep = () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    /** Glass is on screen: drop the Home service's cover over the Amazon launcher. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) HomeButtonService.glassShown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        HomeButtonService.glassResumed = false;
        if (resumed == this) resumed = null;
        SharedPreferences.Editor e = prefs.edit()
                .putInt("zone", zone)
                .putInt("dockIdx", dockIdx)
                .putInt("topIdx", topIdx);
        if (gridIdx < gridApps.size()) e.putString("gridPkg", gridApps.get(gridIdx).packageName);
        e.apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        io.shutdownNow();
    }

    /** Home pressed. From an app: keep the remembered focus. Already home: go back to the dock. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (handleAction(intent)) return;
        if (intent.getBooleanExtra("reload", false)) { // sent by configure.sh
            loadApps();
            wallpaperStamp = -1;
            loadWallpaper();
            return;
        }
        boolean wasVisible = !stopped;
        closeMenu();
        closeLook();
        drawer.close();
        settingsSheet.close();
        guideSheet.close();
        dimHome(false);
        if (renameDialog != null && renameDialog.isShowing()) renameDialog.dismiss();
        endMove();
        closeLockSheet();
        if (wasVisible && !dockApps.isEmpty()) {
            zone = DOCK;
            applyFocus(true);
        }
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        loadedConfigStamp = configStamp();
        Context app = getApplicationContext();
        io.execute(() -> {
            AppLoader.Result result = AppLoader.load(app);
            main.post(() -> onAppsLoaded(result));
        });
    }

    private void onAppsLoaded(AppLoader.Result result) {
        String gridPkg = restoreGridPkg;
        if (gridPkg == null && gridIdx < gridApps.size()) gridPkg = gridApps.get(gridIdx).packageName;
        restoreGridPkg = null;

        // Home rows "Recent + For you": the top row is the four apps opened most recently,
        // the grid is just the For you cards, and everything else lives in the left dock
        boolean allApps = look.get(Look.TILES) == 1;
        List<AppInfo> all = new ArrayList<>(result.dock);
        all.addAll(result.grid);
        dockApps.clear();
        if (allApps) {
            dockApps.addAll(result.dock);
        } else {
            List<AppInfo> recent = new ArrayList<>(all); // dock, then most used: the fill-in order before anything is opened
            recent.sort((a, b) -> Long.compare(Usage.lastUsed(this, b.packageName), Usage.lastUsed(this, a.packageName)));
            dockApps.addAll(recent.subList(0, Math.min(4, recent.size())));
        }
        dockRow.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (AppInfo app : dockApps) {
            View cell = inflater.inflate(R.layout.item_tile, dockRow, false);
            Tiles.bind(cell, app);
            cell.setTag(Tiles.DOCK); // dock names show on focus only, whatever the App names setting
            dockRow.addView(cell);
        }
        // "For you" cards lead the grid, so the first press down from the dock lands on them
        gridApps = new ArrayList<>(result.suggestions);
        if (allApps) gridApps.addAll(result.grid);
        adapter.setApps(gridApps);
        drawerApps.clear();
        for (AppInfo a : all) {
            boolean seen = false; // one icon per app, never one already on the home screen
            for (AppInfo b : dockApps) seen |= b.packageName.equals(a.packageName);
            for (AppInfo b : drawerApps) seen |= b.packageName.equals(a.packageName) || b.label.equalsIgnoreCase(a.label);
            if (!seen) drawerApps.add(a);
        }
        long now = System.currentTimeMillis();
        Collator collator = Collator.getInstance();
        drawerApps.sort((a, b) -> {
            int c = Double.compare(Usage.score(this, b.packageName, now), Usage.score(this, a.packageName, now));
            return c != 0 ? c : collator.compare(a.label, b.label);
        });

        dockIdx = clamp(dockIdx, 0, dockApps.size() - 1);
        int found = -1;
        for (int i = 0; gridPkg != null && i < gridApps.size(); i++) {
            if (gridApps.get(i).packageName.equals(gridPkg)) found = i;
        }
        gridIdx = clamp(found >= 0 ? found : gridIdx, 0, gridApps.size() - 1);
        if (zone == GRID && gridApps.isEmpty()) zone = DOCK;
        if (zone == DOCK && dockApps.isEmpty()) zone = TOP;

        // Place the highlight only once the new tiles have real positions
        afterLayout(() -> {
            // Rows scrolled past the grid's top must not draw under the pinned top bar
            int pad = Math.round(dp(40));
            grid.setClipBounds(new Rect(-pad, -rowHeight(), grid.getWidth() + pad, grid.getHeight() + Math.round(dp(600))));
            // Bottom padding lets every row, even the last, scroll up into the focused slot
            grid.setPadding(grid.getPaddingLeft(), grid.getPaddingTop(), grid.getPaddingRight(),
                    Math.max(0, grid.getHeight() - rowHeight()));
            applyFocus(false);
        });
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (!handles(key)) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (guideSheet.isOpen()) {
            guideSheet.close();
            return true;
        }
        if (settingsSheet.isOpen()) {
            settingsSheet.key(key);
            return true;
        }
        if (drawer.isOpen()) {
            drawer.key(key);
            if (!drawer.isOpen()) dimHome(false);
            return true;
        }
        if (menuOpen) {
            menuKey(key);
            return true;
        }
        if (lookOpen) {
            lookDrawer.key(key);
            return true;
        }
        if (moving) {
            moveKey(key);
            return true;
        }
        if (lockSheet != null) {
            lockSheetKey(key);
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: left(); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right(); break;
            case KeyEvent.KEYCODE_DPAD_UP: up(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down(); break;
            case KeyEvent.KEYCODE_BACK: back(); break;
            case KeyEvent.KEYCODE_MENU: openAppActions(); break;
            default: if (event.getRepeatCount() == 0) activate(); break;
        }
        return true;
    }

    private static boolean handles(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                return true;
            default:
                return false;
        }
    }

    private void left() {
        if (zone == TOP && topIdx > 0) topIdx--;
        else if (zone == DOCK && dockIdx > 0) dockIdx--;
        else if (zone == GRID && gridIdx % COLUMNS > 0) gridIdx--;
        else return;
        applyFocus(true);
    }

    private void right() {
        if (zone == TOP && topIdx < pills.length - 1) topIdx++;
        else if (zone == DOCK && dockIdx < dockApps.size() - 1) dockIdx++;
        else if (zone == GRID && gridIdx % COLUMNS < COLUMNS - 1 && gridIdx + 1 < gridApps.size()) gridIdx++;
        else return;
        applyFocus(true);
    }

    private void up() {
        if (zone == TOP) return;
        if (zone == DOCK) {
            zone = TOP;
        } else if (gridIdx >= COLUMNS) {
            gridIdx -= COLUMNS;
        } else if (!dockApps.isEmpty()) {
            zone = DOCK;
            dockIdx = Math.min(gridIdx % COLUMNS, dockApps.size() - 1);
        } else {
            zone = TOP;
        }
        applyFocus(true);
    }

    private void down() {
        if (zone == TOP && !dockApps.isEmpty()) {
            zone = DOCK;
        } else if ((zone == TOP || zone == DOCK) && !gridApps.isEmpty()) {
            gridIdx = Math.min(zone == DOCK ? dockIdx : 0, gridApps.size() - 1);
            zone = GRID;
        } else if (zone == GRID && gridIdx / COLUMNS < (gridApps.size() - 1) / COLUMNS) {
            // One row down; a short last row catches the focus on its last tile
            gridIdx = Math.min(gridIdx + COLUMNS, gridApps.size() - 1);
        } else {
            return;
        }
        applyFocus(true);
    }

    private void back() {
        if (zone == DOCK || dockApps.isEmpty()) return;
        zone = DOCK;
        applyFocus(true);
    }

    private void activate() {
        if (zone == TOP) {
            if (topIdx == PILL_APPS) {
                View b = pills[PILL_APPS];
                drawer.open(drawerApps, b.getLeft() + b.getWidth() / 2, b.getBottom() + Math.round(dp(10)));
                if (drawer.isOpen()) dimHome(true);
            }
            else if (topIdx == PILL_TOOLS) openTools();
            else if (topIdx == PILL_INPUTS) openInputs();
            else if (topIdx == PILL_WIFI) launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
            else if (topIdx == PILL_LOOK) openLook();
            else {
                View b = pills[PILL_SETTINGS];
                settingsSheet.open(pillRow.getLeft() + b.getRight(), pillRow.getTop() + b.getBottom() + Math.round(dp(12)));
            }
        } else {
            AppInfo app = focusedApp();
            if (app != null) {
                if (!Suggestions.isSuggestion(app)) Usage.record(this, app.packageName);
                usageChanged = true;
                launch(app.launchIntent);
            }
        }
    }

    /** The home tiles step back while the left dock is open, so its icons and names stand out. */
    private void dimHome(boolean dim) {
        float a = dim ? 0.3f : 1f;
        for (View v : new View[] {dockRow, grid, band, wallCaption}) v.animate().alpha(a).setDuration(200).start();
    }

    /** Menu button on a tile opens that app's system info page (force stop, clear cache...). */
    private void openAppInfo() {
        AppInfo app = focusedApp();
        if (app == null) return;
        launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.packageName, null)));
    }

    private AppInfo focusedApp() {
        if (zone == DOCK && dockIdx < dockApps.size()) return dockApps.get(dockIdx);
        if (zone == GRID && gridIdx < gridApps.size()) return gridApps.get(gridIdx);
        return null;
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Can't open that here", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- focus visuals

    private void applyFocus(boolean animate) {
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            Tiles.setFocused(dockRow.getChildAt(i), zone == DOCK && i == dockIdx, animate);
        }
        adapter.setFocused(zone == GRID ? gridIdx : RecyclerView.NO_POSITION);
        for (int i = 0; i < pills.length; i++) {
            boolean on = zone == TOP && i == topIdx;
            float s = on ? 1.06f : 1f;
            pills[i].animate().scaleX(s).scaleY(s).setDuration(animate ? 200 : 0).setInterpolator(ease).start();
        }

        // Down from the dock slides the whole page up; deeper rows scroll the grid itself
        float pageTarget = 0;
        int scrollTarget = 0;
        if (zone == GRID) {
            // The grid slides into the dock's place; the focused row always sits there
            pageTarget = grid.getTop() - dockRow.getTop();
            scrollTarget = clamp((gridIdx / COLUMNS) * rowHeight(), 0, maxGridScroll());
        }
        if (animate) pageSpring.animateTo(pageTarget); else pageSpring.snapTo(pageTarget);
        // Deeper grid rows: the dock fades instead of sliding up behind the pinned top bar
        float dockAlpha = zone == GRID ? 0f : 1f;
        for (View v : new View[] {band, dockRow}) {
            v.animate().alpha(dockAlpha).setDuration(animate ? 220 : 0).start();
        }
        gridScrollTarget = scrollTarget;
        int dy = scrollTarget - gridScrollActual;
        if (dy != 0) {
            if (animate) grid.smoothScrollBy(0, dy, ease, 420);
            else grid.scrollBy(0, dy);
        }

        updateDockTitle(animate);
        AppInfo focused = focusedApp();
        if (focused != null && focused.glowColor != glowTarget) animateGlow(focused.glowColor, animate);
        moveHighlight();
    }

    private void moveHighlight() {
        float l, t, w, h;
        if (zone == TOP) {
            View p = pills[topIdx];
            float s = 1.06f, pad = dp(3);
            w = p.getWidth() * s + 2 * pad;
            h = p.getHeight() * s + 2 * pad;
            View row = (View) p.getParent(); // the top-left Apps button sits outside the pill row
            l = row.getLeft() + p.getLeft() + p.getWidth() / 2f - w / 2f;
            t = row.getTop() + p.getTop() + p.getHeight() / 2f - h / 2f;
            highlight.moveTo(l, t, w, h, h / 2f);
            return;
        }
        float tileW = dp(180), tileH = dp(101);
        float[] at = tileOrigin();
        if (at == null) return;
        l = at[0];
        t = at[1];
        float s = Tiles.FOCUS_SCALE, pad = dp(6);
        w = tileW * s + 2 * pad;
        h = tileH * s + 2 * pad;
        highlight.moveTo(l + tileW / 2f - w / 2f, t + tileH / 2f - h / 2f, w, h, dp(14) * s + pad);
    }

    /** Top-left of the focused dock/grid tile in page coordinates, or null. */
    private float[] tileOrigin() {
        if (zone == DOCK) {
            if (dockIdx >= dockRow.getChildCount()) return null;
            View cell = dockRow.getChildAt(dockIdx);
            View tile = cell.findViewById(R.id.tile);
            return new float[] {dockRow.getLeft() + cell.getLeft() + tile.getLeft(),
                    dockRow.getTop() + cell.getTop() + tile.getTop()};
        }
        if (zone != GRID) return null;
        // Computed from the grid geometry and the scroll target, so the highlight
        // heads straight for the final spot while the grid is still scrolling
        int col = gridIdx % COLUMNS, row = gridIdx / COLUMNS;
        return new float[] {grid.getLeft() + grid.getPaddingLeft() + col * dp(204) + dp(12),
                grid.getTop() + grid.getPaddingTop() + row * rowHeight() - gridScrollTarget + dp(10)};
    }

    private void updateDockTitle(boolean animate) {
        // Full in the dock, dimmed under the top bar, gone once the page slides up
        float alpha = zone == DOCK ? 1f : zone == TOP ? 0.4f : 0f;
        glow.animate().alpha(zone == TOP ? 0.6f : 1f).setDuration(animate ? 300 : 0).start();
        if (dockApps.isEmpty()) {
            dockTitle.setText("");
            return;
        }
        AppInfo app = dockApps.get(dockIdx);
        alpha = 0f; // names live under the focused tile now; the big title is only used by Move mode
        dockTitle.animate().cancel();
        if (!app.label.contentEquals(dockTitle.getText())) {
            dockTitle.setText(app.label);
            if (animate) {
                dockTitle.setAlpha(0f);
                dockTitle.setTranslationX(dp(10));
            }
            animateGlow(app.glowColor, animate);
        }
        if (animate) {
            dockTitle.animate().alpha(alpha).translationX(0).setDuration(260).setInterpolator(ease).start();
        } else {
            dockTitle.setAlpha(alpha);
            dockTitle.setTranslationX(0);
        }
    }

    private void animateGlow(int color, boolean animate) {
        glowTarget = color;
        if (glowAnimator != null) glowAnimator.cancel();
        if (!animate) {
            glowColor = color;
            setGlowColors(color);
            return;
        }
        glowAnimator = ValueAnimator.ofArgb(glowColor, color);
        glowAnimator.setDuration(500);
        glowAnimator.addUpdateListener(a -> {
            glowColor = (int) a.getAnimatedValue();
            setGlowColors(glowColor);
        });
        glowAnimator.start();
    }

    private void setGlowColors(int c) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        glowDrawable.setColors(new int[] {Color.argb(155, r, g, b), Color.argb(64, r, g, b), Color.argb(0, r, g, b)});
    }

    // ---------------------------------------------------------------- inputs menu

    private void openInputs() {
        View pill = pills[PILL_INPUTS];
        openMenu(InputsMenu.load(this), pillRow.getLeft() + pill.getLeft(), pillRow.getTop() + pill.getBottom() + dp(12));
    }

    /** Popup list (Inputs, app actions) at x/y; rows without an intent or action are info only. */
    private void openMenu(List<InputsMenu.Entry> entries, float x, float y) {
        menuEntries = entries;
        inputsMenu.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        menuIdx = -1;
        for (int i = 0; i < menuEntries.size(); i++) {
            InputsMenu.Entry entry = menuEntries.get(i);
            TextView row = (TextView) inflater.inflate(R.layout.item_input, inputsMenu, false);
            row.setText(entry.label);
            row.setTypeface(Fonts.outfit(this, 400));
            if (!entry.selectable()) row.setAlpha(0.55f);
            else if (menuIdx < 0) menuIdx = i;
            inputsMenu.addView(row);
        }
        updateMenuSelection();

        inputsMenu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        x = Math.max(dp(24), Math.min(x, root.getWidth() - inputsMenu.getMeasuredWidth() - dp(24)));
        y = Math.min(y, root.getHeight() - inputsMenu.getMeasuredHeight() - dp(24));
        inputsMenu.setX(x);
        inputsMenu.setY(y - dp(8));
        inputsMenu.setAlpha(0f);
        inputsMenu.setVisibility(View.VISIBLE);
        inputsMenu.animate().alpha(1f).y(y).setDuration(180).setInterpolator(ease).start();
        menuOpen = true;
    }

    private void closeMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        inputsMenu.animate().alpha(0f).setDuration(120)
                .withEndAction(() -> inputsMenu.setVisibility(View.GONE)).start();
    }

    private void menuKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                for (int i = menuIdx - 1; i >= 0; i--) if (menuEntries.get(i).selectable()) { menuIdx = i; break; }
                updateMenuSelection();
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                for (int i = menuIdx + 1; i < menuEntries.size(); i++) if (menuEntries.get(i).selectable()) { menuIdx = i; break; }
                updateMenuSelection();
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (menuIdx >= 0) {
                    InputsMenu.Entry entry = menuEntries.get(menuIdx);
                    closeMenu();
                    if (entry.action != null) entry.action.run();
                    else launch(entry.intent);
                }
                break;
            case KeyEvent.KEYCODE_MENU:
                if (menuIdx >= 0 && menuEntries.get(menuIdx).inputId != null) renameInput(menuEntries.get(menuIdx));
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                InputsMenu.Entry row = menuIdx >= 0 ? menuEntries.get(menuIdx) : null;
                if (row != null && row.adjust != null) {
                    String text = row.adjust.apply(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    ((TextView) inputsMenu.getChildAt(menuIdx)).setText(text);
                } else {
                    closeMenu();
                }
                break;
            default: // Back, Menu close it
                closeMenu();
        }
    }

    /** Menu on an input: type a new name with the on-screen keyboard; blank resets it. */
    private AlertDialog renameDialog;

    private void renameInput(InputsMenu.Entry entry) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(entry.label);
        field.setSelectAllOnFocus(true);
        field.setHint(entry.systemLabel);
        int pad = Math.round(dp(20));
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, Math.round(dp(8)), pad, 0);
        box.addView(field);
        int keep = menuIdx;
        renameDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Rename " + entry.systemLabel)
                .setView(box)
                .setPositiveButton("Save", (d, w) -> saveInputName(entry, field.getText().toString().trim(), keep))
                .setNeutralButton("Reset", (d, w) -> saveInputName(entry, "", keep))
                .setNegativeButton("Cancel", null)
                .show();
        field.requestFocus();
    }

    private void saveInputName(InputsMenu.Entry entry, String name, int keepIdx) {
        SharedPreferences.Editor e = InputsMenu.names(this).edit();
        if (name.isEmpty() || name.equals(entry.systemLabel)) e.remove(entry.inputId);
        else e.putString(entry.inputId, name);
        e.apply();
        closeMenu();
        openInputs();
        menuIdx = keepIdx;
        updateMenuSelection();
    }

    private void updateMenuSelection() {
        for (int i = 0; i < inputsMenu.getChildCount(); i++) {
            inputsMenu.getChildAt(i).setBackgroundResource(i == menuIdx ? R.drawable.menu_row_selected : 0);
        }
    }

    // ---------------------------------------------------------------- tools menu

    /**
     * Free memory (with what's left now), Pomodoro presets and the lock. The countdown
     * itself lives in HomeButtonService so it shows over any app.
     */
    private void openTools() {
        List<InputsMenu.Entry> entries = new ArrayList<>();
        entries.add(new InputsMenu.Entry(Memory.summary(this), (Intent) null));
        entries.add(new InputsMenu.Entry("Free up memory", this::refreshAll));
        entries.add(new InputsMenu.Entry("Phone remote", () -> guideSheet.open(true)));
        entries.add(new InputsMenu.Entry("Focus | 25 min", () -> startTimer(25, "Focus")));
        entries.add(new InputsMenu.Entry("Short break | 5 min", () -> startTimer(5, "Break")));
        entries.add(new InputsMenu.Entry("Long break | 15 min", () -> startTimer(15, "Break")));
        if (HomeButtonService.timerRunning()) entries.add(new InputsMenu.Entry("Stop timer", HomeButtonService::stopTimer));
        entries.add(new InputsMenu.Entry("Lock TV", this::openLockSheet));
        if (PaintingsActivity.available(this)) entries.add(new InputsMenu.Entry("Paintings", () -> PaintingsActivity.open(this)));
        View pill = pills[PILL_TOOLS];
        openMenu(entries, pillRow.getLeft() + pill.getLeft(), pillRow.getTop() + pill.getBottom() + dp(12));
    }

    private static final String[] LOCK_CHOICES =
            {"30 min", "1 hour", "2 hours", "3 hours", "4 hours", "Until 7 AM", "Until unlocked"};
    private static final long[] LOCK_MINUTES = {30, 60, 120, 180, 240, -1, 0};
    private int lockChoice = 1;

    // ---------------------------------------------------------------- lock sheet

    private View lockSheet;
    private TextView lockValue, lockEnds;

    /** A small centred sheet: the duration is the one thing to change, so it's big, with its arrows. */
    private void openLockSheet() {
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setGravity(Gravity.CENTER_HORIZONTAL);
        sheet.setBackgroundResource(R.drawable.panel_bg);
        int p = Math.round(dp(28));
        sheet.setPadding(p, Math.round(dp(22)), p, Math.round(dp(18)));

        TextView title = sheetText(20, 400, 0xFFFFFFFF);
        title.setText("Lock TV");
        lockValue = sheetText(34, 250, 0xFFFFFFFF);
        lockValue.setPadding(0, Math.round(dp(14)), 0, 0);
        lockEnds = sheetText(15, 300, 0x99FFFFFF);
        lockEnds.setPadding(0, Math.round(dp(2)), 0, Math.round(dp(18)));
        TextView button = sheetText(17, 500, 0xFF000000);
        button.setText("Lock");
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xF2FFFFFF);
        pill.setCornerRadius(dp(20));
        button.setBackground(pill);
        button.setPadding(Math.round(dp(44)), Math.round(dp(9)), Math.round(dp(44)), Math.round(dp(10)));
        TextView hint = sheetText(13, 300, 0x73FFFFFF);
        hint.setText("\u25C0 \u25B6 change  |  OK lock  |  Back cancel");
        hint.setPadding(0, Math.round(dp(14)), 0, 0);
        for (View v : new View[] {title, lockValue, lockEnds, button, hint}) sheet.addView(v);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(dp(380)), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        ((FrameLayout) root).addView(sheet, lp);
        lockSheet = sheet;
        renderLockSheet();
        sheet.setAlpha(0f);
        sheet.setScaleX(0.96f);
        sheet.setScaleY(0.96f);
        sheet.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(ease).start();
    }

    private TextView sheetText(int sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Fonts.outfit(this, weight));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void renderLockSheet() {
        lockValue.setText("\u2039   " + LOCK_CHOICES[lockChoice] + "   \u203A");
        long until = lockUntilFor(lockChoice);
        lockEnds.setText(until == TvLock.FOREVER ? "Until you unlock it"
                : "Unlocks at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(until)));
    }

    private void lockSheetKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                int dir = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                lockChoice = (lockChoice + dir + LOCK_CHOICES.length) % LOCK_CHOICES.length;
                renderLockSheet();
                lockValue.setTranslationX(dp(8) * dir);
                lockValue.animate().translationX(0).setDuration(160).setInterpolator(ease).start();
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                long until = lockUntilFor(lockChoice);
                closeLockSheet();
                lockTv(until);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                break;
            default: // Back, Menu
                closeLockSheet();
        }
    }

    private void closeLockSheet() {
        if (lockSheet == null) return;
        View v = lockSheet;
        lockSheet = null;
        v.animate().alpha(0f).setDuration(140).withEndAction(() -> ((FrameLayout) root).removeView(v)).start();
    }

    private static long lockUntilFor(int choice) {
        long m = LOCK_MINUTES[choice];
        if (m == 0) return TvLock.FOREVER;
        if (m > 0) return System.currentTimeMillis() + m * 60_000L;
        Calendar c = Calendar.getInstance(); // next 7:00 AM
        c.set(Calendar.HOUR_OF_DAY, 7);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    private void lockTv(long until) {
        if (!HomeButtonService.lockUntil(until)) {
            Toast.makeText(this, "Turn on Glass Home button in Accessibility settings to use the lock", Toast.LENGTH_LONG).show();
        }
    }

    private void startTimer(int minutes, String label) {
        if (!HomeButtonService.startTimer(minutes, label)) {
            Toast.makeText(this, "Turn on Glass Home button in Accessibility settings to use the timer", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- app actions (Menu on a tile)

    /** Dock = your priority order (saved to files/dock.txt, which AppLoader reads). */
    private void openAppActions() {
        AppInfo app = focusedApp();
        if (app == null || Suggestions.isSuggestion(app)) return;
        if (look.get(Look.TILES) == 0) { // recent row: it orders itself, so no dock editing
            List<InputsMenu.Entry> info = new ArrayList<>();
            info.add(new InputsMenu.Entry(app.label, (Intent) null));
            info.add(new InputsMenu.Entry("App info", this::openAppInfo));
            float[] o = tileOrigin();
            openMenu(info, o[0] + page.getTranslationX() + dp(180) * Tiles.FOCUS_SCALE + dp(16), o[1] + page.getTranslationY());
            return;
        }
        List<String> dock = new ArrayList<>();
        for (AppInfo a : dockApps) dock.add(a.packageName);
        int at = dock.indexOf(app.packageName);
        List<InputsMenu.Entry> entries = new ArrayList<>();
        entries.add(new InputsMenu.Entry(app.label, (Intent) null));
        if (at >= 0) {
            if (dock.size() > 1) entries.add(new InputsMenu.Entry("Move", this::startMove));
            entries.add(new InputsMenu.Entry("Remove from dock", () -> {
                dock.remove(at);
                saveDock(dock);
            }));
        } else {
            String label = dock.size() < 4 ? "Add to dock"
                    : "Add to dock (replaces " + dockApps.get(3).label + ")";
            entries.add(new InputsMenu.Entry(label, () -> {
                if (dock.size() >= 4) dock.remove(3);
                dock.add(app.packageName);
                saveDock(dock);
            }));
        }
        entries.add(new InputsMenu.Entry("App info", this::openAppInfo));
        float[] o = tileOrigin();
        float tileW = dp(180) * Tiles.FOCUS_SCALE;
        float left = o[0] + page.getTranslationX(), top = o[1] + page.getTranslationY();
        openMenu(entries, left + tileW + dp(16), top);
        // Not enough room on the right (openMenu measured it): open on the tile's left instead
        float menuW = inputsMenu.getMeasuredWidth();
        if (left + tileW + dp(16) + menuW > root.getWidth() - dp(24)) {
            inputsMenu.setX(Math.max(dp(24), left - menuW - dp(16)));
        }
    }

    // ---------------------------------------------------------------- move mode (dock)

    private boolean moving;

    /** The focused dock tile lifts; Left/Right slide it, OK/Back drops it and saves the order. */
    private void startMove() {
        if (zone != DOCK || dockIdx >= dockRow.getChildCount()) return;
        moving = true;
        highlight.animate().alpha(0f).setDuration(120).start();
        View tile = dockRow.getChildAt(dockIdx).findViewById(R.id.tile);
        tile.animate().scaleX(1.16f).scaleY(1.16f).translationY(-dp(6)).setDuration(180).setInterpolator(ease).start();
        dockTitle.setText("\u2039  Move " + dockApps.get(dockIdx).label + "  \u203A");
        dockTitle.animate().cancel();
        dockTitle.animate().alpha(1f).setDuration(180).start();
        wallCaption.animate().alpha(0f).setDuration(120).start();
    }

    private void moveKey(int key) {
        int dir = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        if (dir == 0) {
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) return; // stay in the dock
            endMove();
            return;
        }
        int a = dockIdx, b = dockIdx + dir;
        if (b < 0 || b >= dockRow.getChildCount()) return;
        View va = dockRow.getChildAt(a), vb = dockRow.getChildAt(b);
        float la = va.getLeft(), lb = vb.getLeft();
        dockRow.removeViewAt(a);
        dockRow.addView(va, b);
        dockApps.add(b, dockApps.remove(a));
        dockIdx = b;
        // After the swap each tile is laid out in the other's slot: start it where it was and glide over
        va.setTranslationX(la - lb);
        vb.setTranslationX(lb - la);
        va.animate().translationX(0).setDuration(220).setInterpolator(ease).start();
        vb.animate().translationX(0).setDuration(220).setInterpolator(ease).start();
    }

    private void endMove() {
        if (!moving) return;
        moving = false;
        View tile = dockRow.getChildAt(dockIdx).findViewById(R.id.tile);
        tile.animate().translationY(0).setDuration(180).setInterpolator(ease).start();
        List<String> dock = new ArrayList<>();
        for (AppInfo a : dockApps) dock.add(a.packageName);
        writeDock(dock);
        loadedConfigStamp = configStamp(); // views already match: no reload needed
        dockTitle.setText(""); // updateDockTitle resets the glow and hides the title again
        wallCaption.animate().alpha(1f).setDuration(200).start();
        afterLayout(() -> {
            applyFocus(true);
            highlight.animate().alpha(1f).setDuration(160).start();
        });
    }

    private void saveDock(List<String> dock) {
        if (writeDock(dock)) loadApps();
    }

    private boolean writeDock(List<String> dock) {
        try (FileWriter w = new FileWriter(new File(getFilesDir(), "dock.txt"))) {
            for (String pkg : dock) w.write(pkg + "\n");
            return true;
        } catch (IOException e) {
            Toast.makeText(this, "Couldn't save the dock", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    // ---------------------------------------------------------------- refresh (Tools > Free up memory)

    private boolean freeing;

    /**
     * One button that refreshes everything: closes cached background apps (Memory), then
     * reloads the app list, look and wallpaper, and shows what it gained.
     */
    private void refreshAll() {
        if (freeing) return;
        freeing = true;
        TextView text = findViewById(R.id.boostText);
        text.setText("Refreshing");
        text.setVisibility(View.VISIBLE);
        Context app = getApplicationContext();
        io.execute(() -> {
            long freedMb;
            try {
                freedMb = Memory.freeBackground(app);
            } catch (RuntimeException e) {
                freedMb = 0;
            }
            long result = freedMb;
            main.post(() -> {
                loadApps();
                wallpaperStamp = -1; // re-read the wallpaper file even if unchanged
                applyLook();
                text.setText(result > 0 ? result + " MB freed" : "All fresh"); // short: must not reach the clock
                main.postDelayed(() -> {
                    text.setVisibility(View.GONE);
                    freeing = false;
                }, 2500);
            });
        });
    }

    // ---------------------------------------------------------------- customize panel

    /** Colour and glyph per setting, iPhone Settings style; unknown keys get a neutral grey. */
    private static final Object[][] LOOK_TILES = {
            {Look.WALLPAPER_KEY, 0xFF32ADE6, R.drawable.ic_look_wallpaper},
            {"tiles", 0xFF007AFF, R.drawable.ic_look_rows},
            {"rotate", 0xFF30B0C7, R.drawable.ic_look_rotate},
            {"dim", 0xFF8E8E93, R.drawable.ic_look_dim},
            {"screensaver", 0xFF5856D6, R.drawable.ic_look_screensaver},
            {"paintTime", 0xFFAF52DE, R.drawable.ic_look_painttime},
            {"clockFormat", 0xFFFF9500, R.drawable.ic_look_clock},
            {"clockSize", 0xFFFF9F0A, R.drawable.ic_look_clocksize},
            {"clockWeight", 0xFFF08A00, R.drawable.ic_look_clockstyle},
            {"date", 0xFFFF3B30, R.drawable.ic_look_date},
            {"greeting", 0xFFFFB800, R.drawable.ic_look_greeting},
            {"weather", 0xFF0A84FF, R.drawable.ic_look_weather},
            {"glow", 0xFFFF2D55, R.drawable.ic_look_glow},
            {"labels", 0xFF34C759, R.drawable.ic_look_labels},
            {"arrange", 0xFF5AC8FA, R.drawable.ic_look_arrange},
            {"suggest", 0xFFE5484D, R.drawable.ic_look_suggest},
            {"bedtime", 0xFF5E5CE6, R.drawable.ic_look_bedtime},
    };

    /** Customize: the settings as a dock of icons hanging from the Customize button (LookDrawer). */
    private void openLook() {
        List<String> names = Look.wallpaperNames(this);
        wallpaperOption = new Look.Option(Look.WALLPAPER_KEY, "Wallpaper", 0, names.toArray(new String[0]));
        lookOptions.clear();
        lookOptions.add(wallpaperOption);
        lookOptions.addAll(Look.FIXED);
        if (PaintingsDream.available(this)) {
            int at = lookOptions.indexOf(Look.DIM) + 1;
            lookOptions.add(at, Look.SCREENSAVER);
            lookOptions.add(at + 1, Look.PAINT_TIME);
        }
        List<LookDrawer.Item> items = new ArrayList<>();
        for (Look.Option o : lookOptions) {
            int color = 0xFF8E8E93, glyph = R.drawable.ic_customize;
            for (Object[] t : LOOK_TILES) {
                if (t[0].equals(o.key)) { color = (int) t[1]; glyph = (int) t[2]; }
            }
            items.add(new LookDrawer.Item(o.title, color, glyph));
        }
        // Last: who made it; OK shows the guide's QR
        items.add(new LookDrawer.Item("About  |  Made by Shounak with Claude", 0xFF8E8E93, R.drawable.ic_look_about));
        View b = pills[PILL_LOOK];
        lookDrawer.open(items, pillRow.getLeft() + b.getLeft() + b.getWidth() / 2, pillRow.getTop() + b.getBottom() + Math.round(dp(10)));
        if (lookDrawer.isOpen()) {
            lookOpen = true;
            dimHome(true);
        }
    }

    private void closeLook() {
        lookDrawer.close();
    }

    private final LookDrawer.Host lookHost = new LookDrawer.Host() {
        @Override public String value(int i) {
            return i < lookOptions.size() ? lookOptions.get(i).choices[lookValue(lookOptions.get(i))] : null;
        }

        @Override public void change(int i, int dir) {
            if (i >= lookOptions.size()) { // About
                guideSheet.open(false);
                return;
            }
            Look.Option o = lookOptions.get(i);
            int n = o.choices.length;
            int next = (lookValue(o) + (dir < 0 ? n - 1 : 1)) % n;
            if (o == wallpaperOption) look.setWallpaperName(o.choices[next], false);
            else if (o == Look.SCREENSAVER) PaintingsDream.use(HomeActivity.this, next == 1);
            else look.set(o, next);
            if (o == Look.BEDTIME) HomeButtonService.recheckLock();
            if (o == Look.SUGGEST || o == Look.TILES) { loadApps(); refreshSuggestions(); }
            applyLook();
        }

        @Override public void closed() {
            if (!lookOpen) return;
            lookOpen = false;
            look.markShown(look.wallpaperName(HomeActivity.this));
            dimHome(false);
        }
    };

    private int lookValue(Look.Option o) {
        if (o == wallpaperOption) {
            return Math.max(0, Arrays.asList(o.choices).indexOf(look.wallpaperName(this)));
        }
        if (o == Look.SCREENSAVER) return PaintingsDream.active(this) ? 1 : 0;
        return look.get(o);
    }

    /** Applies every Customize choice to the views. Cheap enough to run on each change. */
    private void applyLook() {
        TextClock clock = findViewById(R.id.clock);
        String fmt = look.get(Look.CLOCK_FORMAT) == 1 ? "H:mm" : "h:mm";
        clock.setFormat12Hour(fmt);
        clock.setFormat24Hour(fmt);
        int size = Look.CLOCK_SP[look.get(Look.CLOCK_SIZE)];
        clock.setVisibility(size == 0 ? View.GONE : View.VISIBLE);
        if (size > 0) clock.setTextSize(size);
        clock.setTypeface(Fonts.outfit(this, Look.CLOCK_WEIGHTS[look.get(Look.CLOCK_WEIGHT)]));
        findViewById(R.id.date).setVisibility(look.get(Look.DATE) == 0 ? View.VISIBLE : View.GONE);
        greeting.setVisibility(look.get(Look.GREETING) == 0 ? View.VISIBLE : View.GONE);
        updateWeather();
        glow.setVisibility(look.get(Look.GLOW) == 0 ? View.VISIBLE : View.INVISIBLE);
        boolean always = look.get(Look.LABELS) == 1;
        if (Tiles.alwaysLabels != always) {
            Tiles.alwaysLabels = always;
            if (adapter != null) {
                adapter.notifyDataSetChanged(); // rebinds every grid tile with the new label rule
                applyFocus(false);
            }
        }
        wallpaper.setColorFilter(Color.argb(Look.DIM_ALPHA[look.get(Look.DIM)], 0, 0, 0), PorterDuff.Mode.SRC_ATOP);
        loadWallpaper(); // no-op unless a different wallpaper was picked
    }

    // ---------------------------------------------------------------- clock, greeting, wifi, wallpaper

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateGreeting();
            updateWeather();
            if (look.rotateIfDue(HomeActivity.this)) loadWallpaper();
        }
    };

    /** Shows the cached weather at once; refetches in the background when it's over 30 min old. */
    private void updateWeather() {
        TextView w = findViewById(R.id.weather);
        if (look.get(Look.WEATHER) != 0) {
            w.setVisibility(View.GONE);
            return;
        }
        String text = Weather.cachedText(this);
        w.setText(text);
        w.setVisibility(text == null ? View.GONE : View.VISIBLE);
        if (Weather.due(this) && !weatherFetching) {
            weatherFetching = true;
            Context app = getApplicationContext();
            io.execute(() -> {
                boolean fresh = Weather.fetch(app);
                main.post(() -> {
                    weatherFetching = false;
                    if (fresh) updateWeather();
                });
            });
        }
    }

    private boolean weatherFetching, suggestFetching;

    /** Twice a day, in the background: new "For you" cards, then a quiet reload of the grid. */
    private void refreshSuggestions() {
        if (look.get(Look.SUGGEST) != 0 || suggestFetching || !Suggestions.due(this)) return;
        suggestFetching = true;
        Context app = getApplicationContext();
        io.execute(() -> {
            boolean fresh = Suggestions.fetch(app);
            main.post(() -> {
                suggestFetching = false;
                if (fresh) loadApps();
            });
        });
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String text = hour < 5 ? "Good night" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon"
                : hour < 22 ? "Good evening" : "Good night";
        greeting.setText(text);
    }

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { main.post(HomeActivity.this::updateWifi); }
        @Override public void onLost(Network n) { main.post(HomeActivity.this::updateWifi); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { main.post(HomeActivity.this::updateWifi); }
    };

    private void updateWifi() {
        Network network = connectivity.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : connectivity.getNetworkCapabilities(network);
        String text;
        if (caps == null) {
            text = "Offline";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            text = "Ethernet";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            WifiInfo info = wifiManager.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid == null || ssid.equals(WifiManager.UNKNOWN_SSID) || ssid.isEmpty()) {
                text = "Wi-Fi"; // name hidden: location permission or location mode is off
            } else {
                text = ssid.startsWith("\"") && ssid.endsWith("\"") ? ssid.substring(1, ssid.length() - 1) : ssid;
            }
        } else {
            text = "Connected";
        }
        if (!text.contentEquals(wifiName.getText())) wifiName.setText(text);
    }

    /** Optional wallpaper: the one picked in Customize (files/wallpapers), else files/wallpaper.jpg. */
    /** The midnight alarm changed the wallpaper: show it now if Home is on screen (else onStart will). */
    static void wallpaperChanged() {
        HomeActivity a = resumed;
        if (a != null) a.loadWallpaper();
    }

    private void loadWallpaper() {
        File file = look.wallpaperFile(this);
        long stamp = file == null ? 0 : file.getAbsolutePath().hashCode() * 31L + file.lastModified() + file.length();
        if (stamp == wallpaperStamp) return;
        wallpaperStamp = stamp;
        String caption = file == null ? null : look.caption(this);
        wallCaption.setText(caption == null ? "" : caption);
        wallCaption.setVisibility(caption == null ? View.GONE : View.VISIBLE);
        if (file == null) {
            wallpaper.setImageDrawable(null);
            wallpaper.setVisibility(View.GONE);
            return;
        }
        String path = file.getAbsolutePath();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        io.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= screenW) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory, fine for a dimmed photo
            Bitmap bmp = BitmapFactory.decodeFile(path, o);
            main.post(() -> {
                wallpaper.setImageBitmap(bmp);
                wallpaper.setColorFilter(Color.argb(Look.DIM_ALPHA[look.get(Look.DIM)], 0, 0, 0), PorterDuff.Mode.SRC_ATOP);
                wallpaper.setVisibility(bmp == null ? View.GONE : View.VISIBLE);
            });
        });
    }

    // ---------------------------------------------------------------- helpers

    /** Changes whenever dock.txt or hidden.txt is added, edited or removed. */
    private long configStamp() {
        long stamp = 0;
        for (String name : new String[] {"dock.txt", "hidden.txt"}) {
            File f = new File(getFilesDir(), name);
            if (f.isFile()) stamp = stamp * 31 + f.lastModified() + f.length();
        }
        return stamp;
    }

    private void afterLayout(Runnable r) {
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                r.run();
            }
        });
        root.requestLayout();
    }

    private int rowHeight() { return Math.round(dp(144)); }

    /** Rows moving up past the focused slot dissolve instead of sliding under the clock. */
    private void fadeRowsAbove() {
        float fade = rowHeight() * 0.7f;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            float top = c.getTop() + c.getTranslationY();
            c.setAlpha(top >= -dp(6) ? 1f : Math.max(0f, 1f + (top + dp(6)) / fade));
        }
    }

    private int maxGridScroll() {
        int rows = (gridApps.size() + COLUMNS - 1) / COLUMNS;
        // The grid is sized to the screen below the pinned top bar (440dp), so its own height is the viewport
        return Math.max(0, rows * rowHeight() + grid.getPaddingBottom() - grid.getHeight());
    }

    private float dp(float v) { return v * density; }

    private static int clamp(int v, int min, int max) { return max < min ? min : Math.max(min, Math.min(max, v)); }
}
