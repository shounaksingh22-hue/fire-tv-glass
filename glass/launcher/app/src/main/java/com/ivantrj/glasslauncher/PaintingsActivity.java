package com.ivantrj.glasslauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Paintings on demand (Tools > Paintings, or the phone remote): the same framed painting as the
 * screensaver, kept on screen until any button is pressed. The screen stays on meanwhile, so
 * the TV neither sleeps nor starts the screensaver; the paintings still change on the Customize
 * schedule and drift, against burn-in.
 */
public class PaintingsActivity extends Activity {

    private PaintingWall wall;

    /** True when there are paintings to show (pushed by tv-kit: setup.sh paintings). */
    static boolean available(Context context) {
        return !PaintingsDream.names(context).isEmpty();
    }

    static void open(Context context) {
        if (!available(context)) return;
        context.startActivity(new Intent(context, PaintingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        wall = new PaintingWall(this);
        setContentView(wall.view(getWindowManager().getDefaultDisplay()));
    }

    /** Any button puts the TV back as it was, like waking from the screensaver. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean system = key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN
                || key == KeyEvent.KEYCODE_VOLUME_MUTE || key == KeyEvent.KEYCODE_SLEEP || key == KeyEvent.KEYCODE_POWER
                || key == KeyEvent.KEYCODE_HOME;
        if (system) return super.dispatchKeyEvent(event); // volume and power keep working
        if (event.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        finish(); // Home, an app or sleep took over: don't come back to it later by surprise
    }

    @Override
    protected void onDestroy() {
        if (wall != null) wall.release();
        wall = null;
        super.onDestroy();
    }
}
