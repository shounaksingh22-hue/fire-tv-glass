package com.ivantrj.glasslauncher;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Bundle;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Fire OS protects its launcher, so it can't be disabled and stays the system
 * home. This service makes Glass the effective home screen instead:
 *  - the remote's Home button is consumed and opens Glass (where Fire OS passes it on);
 *  - whenever the Amazon launcher comes to the front anyway (after boot, or when
 *    an app exits), Glass is brought straight back on top.
 * While Glass starts, a black cover hides the Amazon home so it never flashes;
 * HomeActivity removes it once its window has focus.
 * Event-driven only: it receives window changes from the Amazon launcher's
 * package and key events, and does nothing in between.
 */
public class HomeButtonService extends AccessibilityService {

    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final long COVER_TIMEOUT_MS = 1500; // never leave the screen black for long

    private static HomeButtonService instance;
    private Remote remote;
    private View codeCard;
    private static final Runnable HIDE_CODE = HomeButtonService::hideRemoteCode;
    /** Set by HomeActivity: no cover is needed when Glass is already in front. */
    static boolean glassResumed;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::hideCover;
    private View cover;
    private long lastLaunch;

    /** TV idle (screensaver started or screen off): free memory automatically, at most every 10 min. */
    private final BroadcastReceiver idleReceiver = new BroadcastReceiver() {
        private long last;
        @Override public void onReceive(Context c, Intent i) {
            long now = SystemClock.elapsedRealtime();
            if (last != 0 && now - last < 10 * 60_000) return;
            last = now;
            Context app = getApplicationContext();
            new Thread(() -> Memory.freeBackground(app), "glass-free-memory").start();
        }
    };

    /** The screensaver is showing (the screen counts as on then): the phone remote's first press wakes. */
    static volatile boolean dreaming;
    private final BroadcastReceiver dreamReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            dreaming = Intent.ACTION_DREAMING_STARTED.equals(i.getAction()); // screen off clears it too
        }
    };

    /**
     * Nightly wallpaper: an alarm at 12 AM, so it changes on time even when Home isn't on screen
     * (asleep, screensaver, another app). Home's own clock tick still catches up on its own.
     */
    private static final String MIDNIGHT = "com.ivantrj.glasslauncher.MIDNIGHT";
    private final BroadcastReceiver midnightReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (new Look(c).rotateIfDue(c)) HomeActivity.wallpaperChanged();
            scheduleMidnight();
        }
    };

    private void scheduleMidnight() {
        long next = Look.midnight(System.currentTimeMillis()) + 24 * 60 * 60_000L + 5_000; // 12:00:05 AM
        PendingIntent pi = PendingIntent.getBroadcast(this, 1, new Intent(MIDNIGHT).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
    }

    private TvLock lock;
    private final Runnable lockCheck = new Runnable() {
        @Override public void run() {
            if (lock != null) lock.check();
            handler.postDelayed(this, 30_000);
        }
    };

    /** Locks now: until the given wall-clock time, or TvLock.FOREVER. False if the service isn't running. */
    static boolean lockUntil(long until) {
        HomeButtonService s = instance;
        if (s == null) return false;
        TvLock.lockUntil(s, until);
        s.lock.check();
        return true;
    }

    /** Re-evaluates the bedtime window right after it changes in Customize. */
    static void recheckLock() {
        HomeButtonService s = instance;
        if (s != null && s.lock != null) s.lock.check();
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        lock = new TvLock(this);
        prepareCover();
        lockCheck.run(); // also restores a lock after reboot
        IntentFilter idle = new IntentFilter(Intent.ACTION_DREAMING_STARTED);
        idle.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(idleReceiver, idle);
        IntentFilter dream = new IntentFilter(Intent.ACTION_DREAMING_STARTED);
        dream.addAction(Intent.ACTION_DREAMING_STOPPED);
        dream.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(dreamReceiver, dream);
        registerReceiver(midnightReceiver, new IntentFilter(MIDNIGHT));
        scheduleMidnight();
        if (remote != null) remote.stop(); // reconnected: don't leave the old one holding the key helper
        remote = new Remote(this);
        remote.start();
    }

    /**
     * The phone remote's keys when the Injector isn't running (it needs the computer to
     * start it after a reboot, as Fire OS blocks apps from the TV's own ADB). Glass gets
     * keys directly; elsewhere Back and Sleep are system actions, media and volume go
     * through the audio service, and arrows/OK move and click accessibility focus, which
     * works in apps built from standard views. Main thread.
     */
    static void remoteKey(int code) {
        HomeButtonService s = instance;
        if (code == KeyEvent.KEYCODE_SLEEP) { // before Glass and the screensaver, which would swallow it
            if (s != null && !SleepAdmin.sleep(s)) s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            return;
        }
        if (code == KeyEvent.KEYCODE_WAKEUP) {
            if (s != null && !s.wakeFromScreensaver()) {
                PowerManager.WakeLock w = s.getSystemService(PowerManager.class).newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "glass:wake");
                w.acquire(1000); // turns the screen on; the TV's own idle timer takes over after
            }
            return;
        }
        if (s != null && s.wakeFromScreensaver()) return; // like a real remote: the first press just wakes
        if (code == KeyEvent.KEYCODE_HOME) { goHome(); return; }
        if (HomeActivity.remoteKey(code) || s == null) return;
        AudioManager audio = s.getSystemService(AudioManager.class);
        switch (code) {
            case KeyEvent.KEYCODE_BACK: s.performGlobalAction(GLOBAL_ACTION_BACK); return;
            case KeyEvent.KEYCODE_VOLUME_UP:
                audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                return;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                return;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                return;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                return;
        }
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: move(root, focused, View.FOCUS_UP); return;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(root, focused, View.FOCUS_DOWN); return;
            case KeyEvent.KEYCODE_DPAD_LEFT: move(root, focused, View.FOCUS_LEFT); return;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(root, focused, View.FOCUS_RIGHT); return;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                for (AccessibilityNodeInfo n = focused; n != null; n = n.getParent()) {
                    if (n.isClickable()) { n.performAction(AccessibilityNodeInfo.ACTION_CLICK); return; }
                }
                return;
            case KeyEvent.KEYCODE_DEL:
                if (focused != null && focused.isEditable() && focused.getText() != null && focused.getText().length() > 0) {
                    CharSequence t = focused.getText();
                    setText(focused, t.subSequence(0, t.length() - 1));
                }
        }
    }

    /** True if the screensaver was showing; dismissed with Back, which only the screensaver sees. */
    private boolean wakeFromScreensaver() {
        // Glass's own Paintings screensaver shares Glass's package, so the window check below
        // can't tell it from the home screen: rely on the dream broadcasts for that one
        if (PaintingsDream.active(this) && !dreaming) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String dream = Settings.Secure.getString(getContentResolver(), "screensaver_components");
        if (root == null || dream == null || root.getPackageName() == null) return false;
        if (!dream.startsWith(root.getPackageName() + "/")) return false;
        performGlobalAction(GLOBAL_ACTION_BACK);
        return true;
    }

    private static void move(AccessibilityNodeInfo root, AccessibilityNodeInfo focused, int direction) {
        AccessibilityNodeInfo next = focused != null ? focused.focusSearch(direction) : firstFocusable(root);
        if (next != null) next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    }

    private static AccessibilityNodeInfo firstFocusable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isFocusable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = firstFocusable(n.getChild(i));
            if (f != null) return f;
        }
        return null;
    }

    /** Typed on the phone: added to the focused text box. */
    static void remoteText(String text) {
        HomeButtonService s = instance;
        AccessibilityNodeInfo root = s == null ? null : s.getRootInActiveWindow();
        AccessibilityNodeInfo focused = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null || !focused.isEditable()) return;
        CharSequence t = focused.getText();
        setText(focused, (t == null ? "" : t.toString()) + text);
    }

    private static void setText(AccessibilityNodeInfo n, CharSequence text) {
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    /** The iPhone remote's Home button. */
    static void goHome() {
        HomeButtonService s = instance;
        if (s != null) s.openGlass();
    }

    /** Pairing: the code a phone must enter, in a card at the top of the screen, for two minutes. */
    static void showRemoteCode(String code) {
        HomeButtonService s = instance;
        if (s == null) return;
        hideRemoteCode();
        float d = s.getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(s);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackgroundResource(R.drawable.panel_bg);
        card.setPadding(Math.round(36 * d), Math.round(18 * d), Math.round(36 * d), Math.round(20 * d));
        TextView title = new TextView(s);
        title.setText("Pair your phone");
        title.setTextColor(0xB3FFFFFF);
        title.setTextSize(15);
        title.setTypeface(Fonts.outfit(s, 400));
        TextView digits = new TextView(s);
        digits.setText(code.replace("", "  ").trim());
        digits.setTextColor(0xFFFFFFFF);
        digits.setTextSize(44);
        digits.setTypeface(Fonts.outfit(s, 300));
        card.addView(title);
        card.addView(digits);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.round(40 * d);
        try {
            s.getSystemService(WindowManager.class).addView(card, lp);
            s.codeCard = card;
            card.setAlpha(0f);
            card.animate().alpha(1f).setDuration(200).start();
            s.handler.postDelayed(HIDE_CODE, 120_000);
        } catch (RuntimeException ignored) {
        }
    }

    static void hideRemoteCode() {
        HomeButtonService s = instance;
        if (s == null || s.codeCard == null) return;
        s.handler.removeCallbacks(HIDE_CODE);
        try {
            s.getSystemService(WindowManager.class).removeView(s.codeCard);
        } catch (RuntimeException ignored) {
        }
        s.codeCard = null;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(idleReceiver);
            unregisterReceiver(dreamReceiver);
            unregisterReceiver(midnightReceiver);
        } catch (IllegalArgumentException ignored) { // never connected
        }
        removeCover();
        handler.removeCallbacks(lockCheck);
        if (lock != null) lock.destroy();
        handler.removeCallbacks(tick);
        removeChip();
        hideRemoteCode();
        if (remote != null) remote.stop();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // Keyboards/remotes with an Esc key: make it Back everywhere (Amazon's keyboard ignores Esc)
        if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) performGlobalAction(GLOBAL_ACTION_BACK);
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openGlass();
        return true; // swallow both down and up so Fire OS never sees Home
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Only Amazon's home screen is replaced: its Settings live in the same app and must stay open
        CharSequence cls = event.getClassName();
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && AMAZON_LAUNCHER.contentEquals(event.getPackageName())
                && (cls == null || cls.toString().contains("HomeActivity"))) {
            openGlass();
        }
    }

    private void openGlass() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < 600) return; // one launch per Home press / launcher flash
        lastLaunch = now;
        if (!glassResumed) showCover();
        Intent intent = new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
        // Android 9 holds back activity starts for 5 s after Home; alarm launches are exempt
        // (install sets alarm_manager_constants min_futurity=0 so this fires immediately).
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        getSystemService(AlarmManager.class).setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), pi);
    }

    /**
     * Adds the cover window once, hidden. A hidden window has no visible surface (no cost
     * while you watch), and showing it is a visibility flip instead of a window creation,
     * so it lands a frame or two sooner after the Amazon launcher appears.
     */
    private void prepareCover() {
        if (cover != null) return;
        View v = new View(this);
        v.setBackgroundColor(Color.BLACK);
        v.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        try {
            getSystemService(WindowManager.class).addView(v, lp);
            cover = v;
        } catch (RuntimeException e) {
            cover = null; // no overlay: Glass still opens, just with the old flash
        }
    }

    private void showCover() {
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, COVER_TIMEOUT_MS);
        if (cover == null) prepareCover();
        if (cover == null) return;
        cover.animate().cancel();
        cover.setAlpha(1f);
        cover.setVisibility(View.VISIBLE);
    }

    /** Called by HomeActivity once Glass is on screen: fade the cover away. */
    static void glassShown() {
        HomeButtonService s = instance;
        if (s == null || s.cover == null) return;
        View v = s.cover;
        if (v.getVisibility() != View.VISIBLE) return;
        v.animate().alpha(0f).setDuration(160).withEndAction(s::hideCover).start();
    }

    /** Back to hidden and ready for the next Home press. */
    private void hideCover() {
        handler.removeCallbacks(timeout);
        if (cover != null) cover.setVisibility(View.GONE);
    }

    private void removeCover() {
        handler.removeCallbacks(timeout);
        if (cover == null) return;
        try {
            getSystemService(WindowManager.class).removeView(cover);
        } catch (RuntimeException ignored) {
        }
        cover = null;
    }

    // ---------------------------------------------------------------- focus timer chip

    private TextView chip;
    private long timerEnd;
    private String timerLabel;
    private final Runnable tick = this::tick;
    private final Runnable hideChip = this::removeChip;

    static boolean timerRunning() {
        HomeButtonService s = instance;
        return s != null && s.timerEnd > SystemClock.elapsedRealtime();
    }

    /** Starts (or restarts) the countdown chip. False when the service isn't running. */
    static boolean startTimer(int minutes, String label) {
        HomeButtonService s = instance;
        if (s == null) return false;
        s.timerEnd = SystemClock.elapsedRealtime() + minutes * 60_000L;
        s.timerLabel = label;
        s.handler.removeCallbacks(s.hideChip);
        s.handler.removeCallbacks(s.tick);
        s.tick();
        return true;
    }

    static void stopTimer() {
        HomeButtonService s = instance;
        if (s == null) return;
        s.timerEnd = 0;
        s.handler.removeCallbacks(s.tick);
        s.removeChip();
    }

    private void tick() {
        long left = timerEnd - SystemClock.elapsedRealtime();
        if (left <= 0) {
            showChip("Focus".equals(timerLabel) ? "Focus done | time for a break" : "Break over | back to it");
            timerEnd = 0;
            handler.postDelayed(hideChip, 20_000);
            return;
        }
        long secs = (left + 999) / 1000;
        showChip(timerLabel + "  " + secs / 60 + ":" + String.format(Locale.US, "%02d", secs % 60));
        handler.postDelayed(tick, 1000 - left % 1000); // stay aligned to whole seconds
    }

    private void showChip(String text) {
        if (chip == null) {
            TextView v = new TextView(this);
            v.setTextColor(0xF2FFFFFF);
            v.setTextSize(15);
            v.setTypeface(Fonts.outfit(this, 400));
            v.setBackgroundResource(R.drawable.timer_chip_bg);
            float d = getResources().getDisplayMetrics().density;
            v.setPadding(Math.round(16 * d), Math.round(7 * d), Math.round(16 * d), Math.round(8 * d));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = Math.round(28 * d);
            lp.y = chipY();
            try {
                getSystemService(WindowManager.class).addView(v, lp);
                chip = v;
                v.setAlpha(0f);
                v.animate().alpha(1f).setDuration(220).start();
            } catch (RuntimeException e) {
                return;
            }
        }
        chip.setText(text);
        // Follow Glass coming and going: under its top bar at home, in the corner over apps
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) chip.getLayoutParams();
        if (lp != null && lp.y != chipY()) {
            lp.y = chipY();
            try {
                getSystemService(WindowManager.class).updateViewLayout(chip, lp);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Top-right corner over apps (clear of subtitles and playback bars); below the top bar on Glass. */
    private int chipY() {
        return Math.round((glassResumed ? 100 : 24) * getResources().getDisplayMetrics().density);
    }

    private void removeChip() {
        handler.removeCallbacks(hideChip);
        if (chip == null) return;
        try {
            getSystemService(WindowManager.class).removeView(chip);
        } catch (RuntimeException ignored) {
        }
        chip = null;
    }

    @Override public void onInterrupt() {}
}
