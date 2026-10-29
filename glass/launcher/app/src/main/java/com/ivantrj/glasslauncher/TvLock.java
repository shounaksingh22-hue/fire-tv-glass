package com.ivantrj.glasslauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;

/**
 * Screen-time lock, owned by HomeButtonService so it sits above every app and survives Home.
 * Locked when a manual lock is active ("until" in prefs) or during the bedtime window from
 * Customize. Force unlock: type a random 10-arrow sequence; one mistake deals a new one.
 * adb can always clear it: run-as com.ivantrj.glasslauncher rm shared_prefs/lock.xml, then reboot.
 */
final class TvLock {

    static final long FOREVER = Long.MAX_VALUE;
    private static final int CHALLENGE_LENGTH = 10;
    private static final int[] ARROW_KEYS = {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT};
    private static final String[] ARROWS = {"↑", "↓", "←", "→"};

    private final AccessibilityService service;
    private final Random random = new Random();
    private View view;
    private TextView subtitle, challenge;
    private int[] sequence;
    private int typed;

    TvLock(AccessibilityService service) {
        this.service = service;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("lock", Context.MODE_PRIVATE);
    }

    /** Manual lock until the given wall-clock time (FOREVER = until unlocked). */
    static void lockUntil(Context c, long until) {
        prefs(c).edit().putLong("until", until).putLong("override", 0).apply();
    }

    // ---------------------------------------------------------------- schedule

    private boolean manualActive(long now) { return now < prefs(service).getLong("until", 0); }

    /** {start hour, end hour} of the bedtime window, or null when off. */
    private int[] bedtime() {
        int i = new Look(service).get(Look.BEDTIME);
        return i == 0 ? null : Look.BEDTIME_HOURS[i];
    }

    private boolean inBedtime(long now) {
        int[] b = bedtime();
        if (b == null) return false;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int h = c.get(Calendar.HOUR_OF_DAY);
        return b[0] < b[1] ? h >= b[0] && h < b[1] : h >= b[0] || h < b[1];
    }

    /** Next time the bedtime window ends, as wall-clock millis. */
    private long bedtimeEnd(long now) {
        int[] b = bedtime();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, b == null ? 0 : b[1]);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    private boolean shouldLock(long now) {
        if (manualActive(now)) return true;
        return inBedtime(now) && now >= prefs(service).getLong("override", 0);
    }

    /** Called every 30 s and whenever a lock is set: shows or removes the lock screen. */
    void check() {
        long now = System.currentTimeMillis();
        boolean lock = shouldLock(now);
        if (lock && view == null) show();
        else if (!lock && view != null) hide();
        if (view != null) subtitle.setText(untilText(now));
    }

    private String untilText(long now) {
        long until = manualActive(now) ? prefs(service).getLong("until", 0) : bedtimeEnd(now);
        if (until == FOREVER) return "Locked until you unlock it";
        return "Unlocks at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(until));
    }

    // ---------------------------------------------------------------- screen

    private void show() {
        float d = service.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(service);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xF7000000);

        TextView title = text(42, 200, 0xFFFFFFFF);
        title.setText("TV locked");
        subtitle = text(18, 300, 0x99FFFFFF);
        subtitle.setPadding(0, Math.round(6 * d), 0, Math.round(48 * d));
        TextView hint = text(15, 300, 0x73FFFFFF);
        hint.setText("To unlock now, press these arrows in order");
        challenge = text(30, 300, 0xFFFFFFFF);
        challenge.setPadding(0, Math.round(10 * d), 0, 0);
        challenge.setLetterSpacing(0.35f);
        box.addView(title);
        box.addView(subtitle);
        box.addView(hint);
        box.addView(challenge);

        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setOnKeyListener((v, key, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN) onKey(key);
            return true; // the lock screen eats every key it receives
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        try {
            service.getSystemService(WindowManager.class).addView(box, lp);
        } catch (RuntimeException e) {
            return;
        }
        view = box;
        newChallenge();
        box.requestFocus();
        box.setAlpha(0f);
        box.animate().alpha(1f).setDuration(300).start();
        // Leave whatever is playing; Glass comes up underneath
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
    }

    private TextView text(int sp, int weight, int color) {
        TextView t = new TextView(service);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Fonts.outfit(service, weight));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void hide() {
        if (view == null) return;
        View v = view;
        view = null;
        v.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            try {
                service.getSystemService(WindowManager.class).removeView(v);
            } catch (RuntimeException ignored) {
            }
        }).start();
    }

    void destroy() {
        if (view == null) return;
        try {
            service.getSystemService(WindowManager.class).removeView(view);
        } catch (RuntimeException ignored) {
        }
        view = null;
    }

    // ---------------------------------------------------------------- force unlock

    private void newChallenge() {
        sequence = new int[CHALLENGE_LENGTH];
        for (int i = 0; i < sequence.length; i++) sequence[i] = random.nextInt(4);
        typed = 0;
        renderChallenge();
    }

    private void renderChallenge() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sequence.length; i++) sb.append(i < typed ? "•" : ARROWS[sequence[i]]);
        challenge.setText(sb);
    }

    private void onKey(int key) {
        if (sequence == null || view == null) return;
        int arrow = -1;
        for (int i = 0; i < ARROW_KEYS.length; i++) if (ARROW_KEYS[i] == key) arrow = i;
        if (arrow < 0) return; // OK, Back, Menu do nothing here
        if (arrow != sequence[typed]) {
            View v = view;
            v.animate().translationX(18).setDuration(60).withEndAction(() ->
                    v.animate().translationX(0).setDuration(120).start()).start();
            newChallenge();
            return;
        }
        typed++;
        if (typed < sequence.length) {
            renderChallenge();
            return;
        }
        // Solved: clear a manual lock; inside bedtime, stay unlocked until the window ends
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = prefs(service).edit().putLong("until", 0);
        if (inBedtime(now)) e.putLong("override", bedtimeEnd(now));
        e.apply();
        hide();
    }
}
