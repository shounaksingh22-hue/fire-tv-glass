package com.ivantrj.glasslauncher;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

/**
 * Lets Glass turn the screen off when the phone remote's key helper isn't running. Fire OS has
 * no lock screen, so the accessibility lock action does nothing there; lockNow() sleeps the TV.
 * Fire OS's own "add device admin" screen is a stub, so the computer turns this on once:
 * adb shell dpm set-active-admin com.ivantrj.glasslauncher/.SleepAdmin
 */
public class SleepAdmin extends DeviceAdminReceiver {

    /** True if the TV went to sleep. */
    static boolean sleep(Context context) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        if (dpm == null || !dpm.isAdminActive(new ComponentName(context, SleepAdmin.class))) return false;
        dpm.lockNow();
        return true;
    }
}
